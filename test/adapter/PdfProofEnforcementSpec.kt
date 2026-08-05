package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.NOT_PDF_BYTES
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
 * Enforcement spec for the PDF-proof rule. Proof files (subscription / transfer /
 * deactivation) must be real, fully-uploaded PDFs, and the account create / transfer /
 * deactivate operations refuse anything else. Non-proof purposes (e.g. OCR) stay
 * unrestricted.
 */
class PdfProofEnforcementSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    fun String.toRelative(): String = removePrefix("http://localhost:8080")

    /** Presign a proof WITHOUT uploading bytes; returns (attachmentId, uploadUrl). */
    suspend fun ApplicationTestBuilder.presignOnly(token: String, purpose: String): Pair<String, String> {
        val res = client.post("/attachments/presign/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"fileName":"f.pdf","contentType":"application/pdf","purpose":"$purpose"}""")
        }
        res.status shouldBe HttpStatusCode.OK
        val d = res.bodyAsText().asJson().data()
        return d.str("attachmentId") to d.str("url").toRelative()
    }

    suspend fun ApplicationTestBuilder.createProvider(token: String): String {
        val res = client.post("/providers") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Prov-${System.nanoTime()}","paymentScheduleDay":5}""")
        }
        res.status shouldBe HttpStatusCode.Created
        return res.bodyAsText().asJson().data().str("id")
    }

    suspend fun ApplicationTestBuilder.createStore(token: String): String {
        val proof = uploadPdfProof(token, "installation_proof", fileName = "inst.pdf")
        val res = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"storeType":"puregold","branchCode":"BC-${System.nanoTime()}","name":"Store","proofOfInstallationId":"$proof"}""")
        }
        res.status shouldBe HttpStatusCode.Created
        return res.bodyAsText().asJson().data().str("id")
    }

    suspend fun ApplicationTestBuilder.createActiveAccount(token: String, providerId: String, storeId: String): String {
        val proof = uploadPdfProof(token, "subscription_proof")
        val res = client.post("/accounts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"accountNumber":"ACC-${System.nanoTime()}","providerId":"$providerId","storeId":"$storeId","rate":"1000.00","installationDate":"2025-01-01","subscriptionProofIds":["$proof"]}""")
        }
        res.status shouldBe HttpStatusCode.Created
        return res.bodyAsText().asJson().data().str("id")
    }

    // ---------------- Blob-level enforcement ----------------

    Given("the token-gated blob upload route") {
        When("PUTting non-PDF bytes to a subscription_proof attachment") {
            Then("it is rejected with 400") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token
                    val (_, url) = presignOnly(token, "subscription_proof")
                    client.put(url) { setBody(NOT_PDF_BYTES) }.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        When("PUTting real PDF bytes to a subscription_proof attachment") {
            Then("it succeeds") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token
                    val (_, url) = presignOnly(token, "subscription_proof")
                    client.put(url) { setBody(PDF_BYTES) }.status shouldBe HttpStatusCode.OK
                }
            }
        }

        When("PUTting non-PDF bytes to a non-proof purpose (ocr_source)") {
            Then("it still succeeds — only proof purposes are PDF-restricted") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token
                    val (_, url) = presignOnly(token, "ocr_source")
                    client.put(url) { setBody(NOT_PDF_BYTES) }.status shouldBe HttpStatusCode.OK
                }
            }
        }
    }

    // ---------------- Operation-level enforcement ----------------

    Given("creating an account via POST /accounts") {
        When("no subscription proof is supplied") {
            Then("it returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    val res = client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"NP-${System.nanoTime()}","providerId":"$providerId","storeId":"$storeId","rate":"1000.00","installationDate":"2025-01-01"}""")
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        When("the proof was presigned but never uploaded") {
            Then("it returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    val (proofId, _) = presignOnly(admin.token, "subscription_proof")
                    val res = client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"NU-${System.nanoTime()}","providerId":"$providerId","storeId":"$storeId","rate":"1000.00","installationDate":"2025-01-01","subscriptionProofIds":["$proofId"]}""")
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        When("a real uploaded PDF proof is supplied") {
            Then("it returns 201") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    createActiveAccount(admin.token, providerId, storeId).isNotBlank() shouldBe true
                }
            }
        }
    }

    Given("deactivating an account") {
        When("the deactivation proof was presigned but never uploaded") {
            Then("it returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    val accountId = createActiveAccount(admin.token, providerId, storeId)
                    val (proofId, _) = presignOnly(sec.token, "deactivation_proof")
                    val res = client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$proofId"}""")
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    }

    Given("transferring an account") {
        When("the transfer proof was presigned but never uploaded") {
            Then("it returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    val destStoreId = createStore(admin.token)
                    val accountId = createActiveAccount(admin.token, providerId, storeId)
                    val (proofId, _) = presignOnly(sec.token, "transfer_proof")
                    val res = client.post("/accounts/$accountId/transfer") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"newStoreId":"$destStoreId","proofId":"$proofId"}""")
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    }

    Given("presigning an upload") {
        When("a proof purpose declares a non-PDF content type") {
            Then("it is rejected up front, before any bytes travel") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token
                    listOf("subscription_proof", "transfer_proof", "deactivation_proof").forEach { purpose ->
                        val res = client.post("/attachments/presign/upload") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody("""{"fileName":"f.png","contentType":"image/png","purpose":"$purpose"}""")
                        }
                        res.status shouldBe HttpStatusCode.BadRequest
                        res.bodyAsText().asJson().str("message") shouldBe
                            "a $purpose must be uploaded as application/pdf"
                    }
                }
            }
        }

        When("a non-proof purpose declares a non-PDF content type") {
            Then("it is allowed — installation photos and OCR sources stay unrestricted") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token
                    val res = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"f.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }
                    res.status shouldBe HttpStatusCode.OK
                }
            }
        }
    }

    Given("uploading proof bytes") {
        When("the declared Content-Length exceeds the 10 MB cap") {
            Then("it returns 400 without the body ever being buffered") {
                testApplication {
                    application { testModule() }
                    val token = signIn().token
                    val (_, url) = presignOnly(token, "subscription_proof")
                    // 11 MB of zeros: rejected from the header, so this never reaches the heap
                    // as an attachment.
                    val res = client.put(url) { setBody(ByteArray(11 * 1024 * 1024)) }
                    res.status shouldBe HttpStatusCode.BadRequest
                    res.bodyAsText().asJson().str("message") shouldBe "PDF exceeds the 10 MB limit"
                }
            }
        }
    }
})
