package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

/**
 * Audit log write + read path: creating a store records `store.created` in its own
 * transaction, and GET /activities?entityId=<store> reads it back (enveloped page).
 */
class ActivitySpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject

    Given("a mutation that records an audit entry") {
        When("creating a store and reading /activities for it") {
            Then("the audit log has the store.created entry") {
                testApplication {
                    application { testModule() }

                    val token = signIn().token

                    // reserve an attachment (installation proof) via presign upload
                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"proof.pdf","contentType":"application/pdf","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson()["data"]!!.jsonObject["attachmentId"]!!.jsonPrimitive.content

                    // create a store (records store.created)
                    val branch = "AUD-${System.nanoTime()}"
                    val store = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"$branch","name":"Audit Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }
                    store.status shouldBe HttpStatusCode.Created
                    val storeId = store.bodyAsText().asJson()["data"]!!.jsonObject["id"]!!.jsonPrimitive.content

                    // the audit log for that store contains store.created
                    val acts = client.get("/activities?entityId=$storeId") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    acts.status shouldBe HttpStatusCode.OK
                    val items = acts.bodyAsText().asJson()["data"]!!.jsonObject["items"]!!.jsonArray
                    items.any { it.jsonObject["action"]!!.jsonPrimitive.content == "store.created" } shouldBe true
                }
            }
        }
    }
})
