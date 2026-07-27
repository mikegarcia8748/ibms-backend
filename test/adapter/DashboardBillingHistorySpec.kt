package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import com.puregoldbe.ibms.support.uploadPdfProof
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

/**
 * Integration spec for `GET /dashboard/billing-history` (feature 5).
 *
 * Compiles one top sheet through the real DRAFT → confirm flow and leaves another
 * provider with a DRAFT only, then asserts the compiled one is listed and the draft
 * is excluded (the "billing history" contract). Also pins the role gating.
 */
class DashboardBillingHistorySpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.dataArr(): JsonArray = this["data"]!!.jsonArray
    fun JsonObject.items(): List<JsonObject> = this.data()["items"]!!.jsonArray.map { it.jsonObject }
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    val now = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Manila"))
    val currentPeriod = "${now.year}-${now.monthNumber.toString().padStart(2, '0')}"

    Given("a compiled top sheet and a separate draft") {
        When("GET /dashboard/billing-history is called") {
            Then("the compiled top sheet is listed and the draft is excluded") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token
                    val managerToken = signIn(UserRole.MANAGER).token
                    val s = System.nanoTime().toString()

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    suspend fun seedProviderStoreAccount(prefix: String): String {
                        val providerId = client.post("/providers") {
                            header(HttpHeaders.Authorization, "Bearer $adminToken")
                            contentType(ContentType.Application.Json)
                            setBody("""{"name":"$prefix-$s","paymentScheduleDay":15}""")
                        }.bodyAsText().asJson().data().str("id")
                        val storeId = client.post("/stores") {
                            header(HttpHeaders.Authorization, "Bearer $adminToken")
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"storeType":"puregold","branchCode":"$prefix-$s","name":"$prefix Store","proofOfInstallationId":"$attachmentId"}""",
                            )
                        }.bodyAsText().asJson().data().str("id")
                        val proofId = uploadPdfProof(adminToken, "subscription_proof")
                        client.post("/accounts") {
                            header(HttpHeaders.Authorization, "Bearer $adminToken")
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"accountNumber":"$prefix-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01","subscriptionProofIds":["$proofId"]}""",
                            )
                        }.status shouldBe HttpStatusCode.Created
                        return providerId
                    }

                    // --- Provider 1: full draft -> confirm (COMPILED) ---
                    val provider1 = seedProviderStoreAccount("BH1")
                    val draftId = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$provider1","billingPeriod":"$currentPeriod"}""")
                    }.bodyAsText().asJson().data().str("id")

                    // Confirm directly — RFP numbers are assigned by the external system
                    // after confirm, so they are not required to reach COMPILED.
                    client.post("/topsheets/$draftId/confirm") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.status shouldBe HttpStatusCode.OK

                    // --- Provider 2: DRAFT only (must NOT appear in billing history) ---
                    val provider2 = seedProviderStoreAccount("BH2")
                    client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$provider2","billingPeriod":"$currentPeriod"}""")
                    }.status shouldBe HttpStatusCode.Created

                    // Provider 1 → the compiled top sheet is listed.
                    val hist1 = client.get("/dashboard/billing-history?providerId=$provider1") {
                        header(HttpHeaders.Authorization, "Bearer $managerToken")
                    }
                    hist1.status shouldBe HttpStatusCode.OK
                    val items1 = hist1.bodyAsText().asJson().items()
                    items1.size shouldBe 1
                    items1.first().str("status") shouldBe "compiled"
                    items1.first().str("providerId") shouldBe provider1

                    // Provider 2 → the draft is excluded, so no history.
                    val hist2 = client.get("/dashboard/billing-history?providerId=$provider2") {
                        header(HttpHeaders.Authorization, "Bearer $managerToken")
                    }
                    hist2.status shouldBe HttpStatusCode.OK
                    hist2.bodyAsText().asJson().items().size shouldBe 0
                }
            }
        }

        When("GET /dashboard/billing-history is called by a secretary") {
            Then("it returns 403") {
                testApplication {
                    application { testModule() }
                    val secretaryToken = signIn(UserRole.SECRETARY).token
                    client.get("/dashboard/billing-history") {
                        header(HttpHeaders.Authorization, "Bearer $secretaryToken")
                    }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    }
})
