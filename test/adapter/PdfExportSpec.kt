package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
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
 * Integration spec for the PDF export endpoint `GET /exports/topsheet/{id}.pdf`.
 *
 * Seeds a provider, store, accounts, creates and confirms a topsheet draft (so it
 * reaches the COMPILED status with an invoice number), then downloads the PDF and
 * asserts headers and body magic bytes.
 */
class PdfExportSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.dataArr(): JsonArray = this["data"]!!.jsonArray
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    val now = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Manila"))
    val currentPeriod = "${now.year}-${now.monthNumber.toString().padStart(2, '0')}"

    Given("a compiled topsheet") {
        When("GET /exports/topsheet/{id}.pdf is called with a SECRETARY token") {
            Then("it returns 200 with a valid PDF binary") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token
                    val secretaryToken = signIn(UserRole.SECRETARY).token
                    val s = System.nanoTime().toString()

                    // --- seed: provider ---
                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"PdfProv-$s","paymentScheduleDay":15}""")
                    }.bodyAsText().asJson().data().str("id")

                    // --- seed: attachment (store proof) ---
                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"proof.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    // --- seed: store ---
                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"PDF-$s","name":"PDF Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    // --- seed: 2 accounts ---
                    suspend fun createAccount(num: String) = client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"$num","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01"}""",
                        )
                    }
                    createAccount("pdf-$s-1")
                    createAccount("pdf-$s-2")

                    // --- create draft ---
                    val draftId = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$providerId","billingPeriod":"$currentPeriod"}""")
                    }.bodyAsText().asJson().data().str("id")

                    // --- assign RFP numbers to all lines so confirmation succeeds ---
                    val lines = client.get("/topsheets/$draftId/lines") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.bodyAsText().asJson().dataArr().map { it.jsonObject }

                    lines.forEachIndexed { i, line ->
                        client.patch("/topsheets/$draftId/lines/${line.str("id")}") {
                            header(HttpHeaders.Authorization, "Bearer $adminToken")
                            contentType(ContentType.Application.Json)
                            setBody("""{"rfpNumber":"${(900001 + i).toString().padStart(6, '0')}"}""")
                        }.status shouldBe HttpStatusCode.OK
                    }

                    // --- confirm draft -> compiled ---
                    val confirm = client.post("/topsheets/$draftId/confirm") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }
                    confirm.status shouldBe HttpStatusCode.OK
                    confirm.bodyAsText().asJson().data().str("status") shouldBe "compiled"

                    // --- download PDF with SECRETARY token ---
                    val pdfResp = client.get("/exports/topsheet/$draftId.pdf") {
                        header(HttpHeaders.Authorization, "Bearer $secretaryToken")
                    }

                    // --- assertions ---
                    pdfResp.status shouldBe HttpStatusCode.OK

                    val contentType = pdfResp.headers[HttpHeaders.ContentType] ?: ""
                    contentType shouldContain "application/pdf"

                    val contentDisposition = pdfResp.headers[HttpHeaders.ContentDisposition] ?: ""
                    contentDisposition shouldContain ".pdf"

                    val bytes = pdfResp.readRawBytes()
                    (bytes.size > 0) shouldBe true

                    // PDF magic number: first 4 bytes are %PDF
                    val magic = bytes.take(4).toByteArray().decodeToString()
                    magic shouldBe "%PDF"
                }
            }
        }

        When("GET /exports/topsheet/{id}.pdf is called with a FINANCE token") {
            Then("it also returns 200 with a valid PDF") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token
                    val financeToken = signIn(UserRole.FINANCE).token
                    val s = System.nanoTime().toString()

                    // --- seed: provider ---
                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"PdfFin-$s","paymentScheduleDay":10}""")
                    }.bodyAsText().asJson().data().str("id")

                    // --- seed: attachment ---
                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    // --- seed: store ---
                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"FIN-$s","name":"Fin Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    // --- seed: account ---
                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"fin-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"2000","installationDate":"2020-01-01"}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    // --- create draft ---
                    val draftId = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$providerId","billingPeriod":"$currentPeriod"}""")
                    }.bodyAsText().asJson().data().str("id")

                    // --- assign RFP ---
                    val lines = client.get("/topsheets/$draftId/lines") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.bodyAsText().asJson().dataArr().map { it.jsonObject }

                    lines.forEachIndexed { i, line ->
                        client.patch("/topsheets/$draftId/lines/${line.str("id")}") {
                            header(HttpHeaders.Authorization, "Bearer $adminToken")
                            contentType(ContentType.Application.Json)
                            setBody("""{"rfpNumber":"${(800001 + i).toString().padStart(6, '0')}"}""")
                        }.status shouldBe HttpStatusCode.OK
                    }

                    // --- confirm ---
                    client.post("/topsheets/$draftId/confirm") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.status shouldBe HttpStatusCode.OK

                    // --- download PDF with FINANCE token ---
                    val pdfResp = client.get("/exports/topsheet/$draftId.pdf") {
                        header(HttpHeaders.Authorization, "Bearer $financeToken")
                    }

                    pdfResp.status shouldBe HttpStatusCode.OK
                    pdfResp.headers[HttpHeaders.ContentType]!! shouldContain "application/pdf"
                    pdfResp.headers[HttpHeaders.ContentDisposition]!! shouldContain ".pdf"

                    val bytes = pdfResp.readRawBytes()
                    (bytes.size > 0) shouldBe true
                    bytes.take(4).toByteArray().decodeToString() shouldBe "%PDF"
                }
            }
        }
    }
})
