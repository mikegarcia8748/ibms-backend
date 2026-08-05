package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.support.PDF_BYTES
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import com.puregoldbe.ibms.support.uploadPdfProof
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

/**
 * End-to-end spec for the up-to-3-PDF proof flow across account setup, deactivation and
 * transfer, plus the read endpoints. The pointed case here is the regression guard: a
 * deactivation proof must never surface as a subscription proof, and a transfer must not
 * copy foreign proofs onto the account it creates at the destination store.
 */
class AccountProofsSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.arr(): JsonArray = this["data"]!!.jsonArray
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    suspend fun ApplicationTestBuilder.newProvider(token: String): String =
        client.post("/providers") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Proof-${System.nanoTime()}","paymentScheduleDay":5}""")
        }.bodyAsText().asJson().data().str("id")

    suspend fun ApplicationTestBuilder.newStore(token: String): String {
        val installProof = client.post("/attachments/presign/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"fileName":"inst.txt","contentType":"text/plain","purpose":"installation_proof"}""")
        }.bodyAsText().asJson().data().str("attachmentId")
        return client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"storeType":"puregold","branchCode":"PB-${System.nanoTime()}","name":"Store",""" +
                    """"proofOfInstallationId":"$installProof"}""",
            )
        }.bodyAsText().asJson().data().str("id")
    }

    suspend fun ApplicationTestBuilder.createAccount(token: String, proofIds: List<String>): HttpResponse {
        val providerId = newProvider(token)
        val storeId = newStore(token)
        return client.post("/accounts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"accountNumber":"PA-${System.nanoTime()}","providerId":"$providerId","storeId":"$storeId",""" +
                    """"rate":"1000.00","installationDate":"2025-01-01",""" +
                    """"subscriptionProofIds":[${proofIds.joinToString(",") { "\"$it\"" }}]}""",
            )
        }
    }

    suspend fun ApplicationTestBuilder.proofsOf(token: String, accountId: String, purpose: String? = null): JsonArray {
        val q = if (purpose != null) "?purpose=$purpose" else ""
        val res = client.get("/accounts/$accountId/attachments$q") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        res.status shouldBe HttpStatusCode.OK
        return res.bodyAsText().asJson().arr()
    }

    Given("an account created with three subscription PDFs") {
        When("listing its attachments") {
            Then("all three come back with file names, slots and working download URLs") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token

                    val proofs = listOf("contract.pdf", "soa.pdf", "annex.pdf").map {
                        uploadPdfProof(token, "subscription_proof", it)
                    }
                    val created = createAccount(token, proofs)
                    created.status shouldBe HttpStatusCode.Created
                    val accountId = created.bodyAsText().asJson().data().str("id")

                    val listed = proofsOf(token, accountId)
                    listed.size shouldBe 3
                    listed.map { it.jsonObject.str("purpose") }.toSet() shouldBe setOf("subscription_proof")
                    listed.map { it.jsonObject.str("fileName") }.toSet() shouldBe
                        setOf("contract.pdf", "soa.pdf", "annex.pdf")
                    listed.map { it.jsonObject["sortOrder"]!!.jsonPrimitive.int }.toSet() shouldBe setOf(0, 1, 2)

                    // All three belong to one activity, so they share a linkedAt exactly.
                    listed.map { it.jsonObject.str("linkedAt") }.toSet().size shouldBe 1

                    // The download URL actually resolves to the bytes we uploaded.
                    val url = listed.first().jsonObject.str("downloadUrl").removePrefix("http://localhost:8080")
                    val blob = client.get(url)
                    blob.status shouldBe HttpStatusCode.OK
                    blob.readRawBytes() shouldBe PDF_BYTES
                }
            }
        }
    }

    Given("an account that is later deactivated with two more PDFs") {
        When("listing by purpose") {
            Then("subscription and deactivation proofs stay separate") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token

                    val subs = (1..3).map { uploadPdfProof(token, "subscription_proof", "sub-$it.pdf") }
                    val accountId = createAccount(token, subs).bodyAsText().asJson().data().str("id")

                    val deacts = (1..2).map { uploadPdfProof(token, "deactivation_proof", "deact-$it.pdf") }
                    val res = client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofIds":["${deacts[0]}","${deacts[1]}"]}""")
                    }
                    res.status shouldBe HttpStatusCode.OK

                    // The defect this guards: before V23 the deactivation proofs were
                    // appended to subscriptionProofIds.
                    res.bodyAsText().asJson().data()["subscriptionProofIds"]!!.jsonArray
                        .map { it.jsonPrimitive.content }.toSet() shouldBe subs.toSet()

                    proofsOf(token, accountId, "subscription_proof").size shouldBe 3
                    proofsOf(token, accountId, "deactivation_proof")
                        .map { it.jsonObject.str("attachmentId") }.toSet() shouldBe deacts.toSet()
                    proofsOf(token, accountId).size shouldBe 5
                }
            }
        }
    }

    Given("an account transferred to another store with three PDFs") {
        When("listing the transfer's attachments and the destination account") {
            Then("the transfer carries all three and the new account inherits only its subscription proofs") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token

                    val sub = uploadPdfProof(token, "subscription_proof", "sub.pdf")
                    val sourceId = createAccount(token, listOf(sub)).bodyAsText().asJson().data().str("id")
                    val destStore = newStore(token)

                    val transferProofs = (1..3).map { uploadPdfProof(token, "transfer_proof", "trn-$it.pdf") }
                    val moved = client.post("/accounts/$sourceId/transfer") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"newStoreId":"$destStore","proofIds":""" +
                                """[${transferProofs.joinToString(",") { "\"$it\"" }}]}""",
                        )
                    }
                    moved.status shouldBe HttpStatusCode.Created
                    val movedBody = moved.bodyAsText().asJson().data()
                    val movedId = movedBody.str("id")

                    movedBody["subscriptionProofIds"]!!.jsonArray
                        .map { it.jsonPrimitive.content } shouldBe listOf(sub)

                    // The proofs hang off both accounts, so either side can show them.
                    proofsOf(token, sourceId, "transfer_proof")
                        .map { it.jsonObject.str("attachmentId") }.toSet() shouldBe transferProofs.toSet()
                    proofsOf(token, movedId, "transfer_proof").size shouldBe 3

                    val transferId = client.get("/transfers?accountId=$movedId") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.bodyAsText().asJson().data()["items"]!!.jsonArray.first().jsonObject.str("id")

                    val viaTransfer = client.get("/transfers/$transferId/attachments") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    viaTransfer.status shouldBe HttpStatusCode.OK
                    viaTransfer.bodyAsText().asJson().arr()
                        .map { it.jsonObject.str("attachmentId") }.toSet() shouldBe transferProofs.toSet()
                }
            }
        }
    }

    Given("proof sets the server must reject") {
        When("submitting them") {
            Then("four proofs, an unknown id and a duplicate all return 400") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token
                    val proofs = (1..4).map { uploadPdfProof(token, "subscription_proof", "p-$it.pdf") }

                    createAccount(token, proofs).status shouldBe HttpStatusCode.BadRequest

                    // An unknown attachment id used to reach the insert and come back 500.
                    createAccount(token, listOf(proofs[0], "11111111-2222-3333-4444-555555555555"))
                        .status shouldBe HttpStatusCode.BadRequest

                    createAccount(token, listOf(proofs[0], proofs[0])).status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    }

    Given("a client still sending the deprecated single proofId") {
        When("deactivating") {
            Then("the request still succeeds") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token

                    val sub = uploadPdfProof(token, "subscription_proof", "sub.pdf")
                    val accountId = createAccount(token, listOf(sub)).bodyAsText().asJson().data().str("id")
                    val deact = uploadPdfProof(token, "deactivation_proof", "deact.pdf")

                    client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$deact"}""")
                    }.status shouldBe HttpStatusCode.OK

                    proofsOf(token, accountId, "deactivation_proof").size shouldBe 1
                }
            }
        }
    }
})
