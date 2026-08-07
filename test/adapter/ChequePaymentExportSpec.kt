package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.rfpFlowTestModule
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import com.puregoldbe.ibms.support.uploadPdfProof
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

/**
 * End-to-end integration spec for the cheque-payment close + document exports:
 * drive a topsheet to APPROVED, pay it with a cheque number (APPROVED → PAID), then
 * download the Cheque Payment Voucher PDF and CSV. Also pins the guards: the exports
 * 409 before the cheque is recorded, a blank cheque is rejected, and auth is required.
 *
 * Payment and the cheque exports sit behind TOPSHEET_RFP_FLOW_ENABLED, which a
 * deployment gets as off. The happy path therefore opts in via [rfpFlowTestModule];
 * the last two Given blocks stay on the default config and pin what a caller sees
 * while the feature is switched off.
 */
class ChequePaymentExportSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    val now = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Manila"))
    val currentPeriod = "${now.year}-${now.monthNumber.toString().padStart(2, '0')}"

    Given("an APPROVED topsheet, with the RFP/finance flow enabled") {
        When("recording a cheque number and downloading the payment documents") {
            Then("pay stores the cheque and the PDF/CSV exports are produced") {
                testApplication {
                    application { rfpFlowTestModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    // --- seed: provider, store, 2 accounts ---
                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"Converge-$s","paymentScheduleDay":15}""")
                    }.bodyAsText().asJson().data().str("id")

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"proof.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"CHQ-$s","name":"Cheque Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    suspend fun createAccount(num: String) {
                        val proofId = uploadPdfProof(token, "subscription_proof")
                        client.post("/accounts") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"accountNumber":"$num","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01","subscriptionProofIds":["$proofId"]}""",
                            )
                        }.status shouldBe HttpStatusCode.Created
                    }
                    createAccount("chq-$s-1")
                    createAccount("chq-$s-2")

                    // --- drive to APPROVED: draft → confirm → generate-rfp → release-to-finance ---
                    val draftId = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$providerId","billingPeriod":"$currentPeriod"}""")
                    }.bodyAsText().asJson().data().str("id")

                    client.post("/topsheets/$draftId/confirm") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status shouldBe HttpStatusCode.OK
                    client.post("/topsheets/$draftId/generate-rfp") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status shouldBe HttpStatusCode.OK
                    client.post("/topsheets/$draftId/release-to-finance") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.bodyAsText().asJson().data().str("status") shouldBe "approved"

                    // Exports 409 before the cheque is recorded.
                    client.get("/exports/topsheet/$draftId/cheque.pdf") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status shouldBe HttpStatusCode.Conflict

                    // Blank cheque body → 400; leaves the topsheet APPROVED.
                    client.post("/topsheets/$draftId/pay") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"chequeNumber":"   "}""")
                    }.status shouldBe HttpStatusCode.BadRequest

                    // Pay with a real cheque number → PAID, cheque stored.
                    val pay = client.post("/topsheets/$draftId/pay") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"chequeNumber":"CHQ-0001"}""")
                    }
                    pay.status shouldBe HttpStatusCode.OK
                    val paid = pay.bodyAsText().asJson().data()
                    paid.str("status") shouldBe "paid"
                    paid.str("chequeNumber") shouldBe "CHQ-0001"

                    // Cheque PDF.
                    val pdf = client.get("/exports/topsheet/$draftId/cheque.pdf") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    pdf.status shouldBe HttpStatusCode.OK
                    pdf.headers[HttpHeaders.ContentType]!! shouldContain "application/pdf"
                    pdf.headers[HttpHeaders.ContentDisposition]!! shouldContain ".pdf"
                    val pdfBytes = pdf.readRawBytes()
                    (pdfBytes.size > 0) shouldBe true
                    pdfBytes.take(4).toByteArray().decodeToString() shouldBe "%PDF"

                    // Cheque CSV.
                    val csv = client.get("/exports/topsheet/$draftId/cheque.csv") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    csv.status shouldBe HttpStatusCode.OK
                    csv.headers[HttpHeaders.ContentType]!! shouldContain "text/csv"
                    csv.headers[HttpHeaders.ContentDisposition]!! shouldContain ".csv"
                    val csvText = csv.readRawBytes().decodeToString()
                    csvText shouldContain "CHQ-0001"
                    csvText shouldContain "GRAND TOTAL"
                }
            }
        }
    }

    Given("the cheque flow disabled — what a deployment gets by default") {
        When("an authenticated caller GETs the cheque PDF/CSV") {
            Then("both answer 503, not the 409 'pay it first' that could never be satisfied") {
                testApplication {
                    application { testModule() }
                    val token = signIn(UserRole.SYSADMIN).token
                    // An id that does not exist: the feature guard runs before any lookup,
                    // so this never reaches the database and never 404s.
                    val id = "00000000-0000-0000-0000-000000000000"

                    listOf("cheque.pdf", "cheque.csv").forEach { doc ->
                        val res = client.get("/exports/topsheet/$id/$doc") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                        res.status shouldBe HttpStatusCode.ServiceUnavailable
                        res.bodyAsText() shouldContain "temporarily disabled"
                    }
                }
            }
        }
    }

    // Runs on the default (disabled) config on purpose: the routes stay registered while
    // the feature is off, so authentication still fires ahead of the feature guard. Were
    // they unregistered instead these would be 404s.
    Given("no authentication") {
        When("GET the cheque PDF/CSV export endpoints") {
            Then("both return 401 — auth still wins over the feature guard") {
                testApplication {
                    application { testModule() }
                    val id = "00000000-0000-0000-0000-000000000000"
                    client.get("/exports/topsheet/$id/cheque.pdf").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/exports/topsheet/$id/cheque.csv").status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    }
})
