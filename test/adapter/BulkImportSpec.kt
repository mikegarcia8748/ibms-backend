package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream

/**
 * End-to-end spec for the bulk-import endpoint: uploads an XLSX, verifies the
 * summary counts (provider/stores/accounts created, skipped rows), and re-uploads
 * the same file to prove idempotency.
 */
class BulkImportSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject

    /** Builds a small XLSX fixture (5 data rows incl. a repeat store code and 2 skip cases). */
    fun buildTestXlsx(): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")

            // Header
            val header = sheet.createRow(0)
            listOf(
                "Store Code", "Store Name", "ISP/Provider", "Service Type",
                "Account No", "Circuit ID", "Start Date", "Monthly Recurring Amount",
            ).forEachIndexed { i, name -> header.createCell(i).setCellValue(name) }

            // Row 1: store 118, SDWAN
            var r = sheet.createRow(1)
            r.createCell(0).setCellValue("118")
            r.createCell(1).setCellValue("PUREGOLD - QI CENTRAL")
            r.createCell(2).setCellValue("Globe")
            r.createCell(3).setCellValue("SDWAN")
            r.createCell(4).setCellValue("ACC001")
            r.createCell(5).setCellValue("IC-AWZ-001")
            r.createCell(6).setCellValue("11/20/2024")
            r.createCell(7).setCellValue(5598.0)

            // Row 2: same store 118, different service type
            r = sheet.createRow(2)
            r.createCell(0).setCellValue("118")
            r.createCell(1).setCellValue("PUREGOLD - QI CENTRAL")
            r.createCell(2).setCellValue("Globe")
            r.createCell(3).setCellValue("Broadband")
            r.createCell(4).setCellValue("ACC002")
            r.createCell(5).setCellValue("IC-AWZ-002")
            r.createCell(6).setCellValue("2/8/2022")
            r.createCell(7).setCellValue("2,798.00")

            // Row 3: store 8041, GPON, no circuit ID, no start date
            r = sheet.createRow(3)
            r.createCell(0).setCellValue("8041")
            r.createCell(1).setCellValue("PUREGOLD - BRANCH 2")
            r.createCell(2).setCellValue("Globe")
            r.createCell(3).setCellValue("GPON")
            r.createCell(4).setCellValue("ACC003")
            r.createCell(7).setCellValue(1500.0)

            // Row 4: missing Store Code -> skip
            r = sheet.createRow(4)
            r.createCell(1).setCellValue("No Store")
            r.createCell(4).setCellValue("ACC004")
            r.createCell(7).setCellValue(500.0)

            // Row 5: zero MRC -> skip
            r = sheet.createRow(5)
            r.createCell(0).setCellValue("999")
            r.createCell(1).setCellValue("Zero MRC Store")
            r.createCell(4).setCellValue("ACC005")
            r.createCell(7).setCellValue(0.0)

            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }

    /** Helper: upload the given bytes as a multipart file to the bulk-import endpoint. */
    suspend fun ApplicationTestBuilder.uploadXlsx(token: String, bytes: ByteArray): HttpResponse {
        return client.post("/accounts/bulk-import") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", bytes, Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=bulk-import.xlsx")
                        })
                    },
                ),
            )
        }
    }

    Given("a sysadmin and an XLSX file") {
        When("uploading the XLSX via POST /accounts/bulk-import") {
            Then("provider, stores, and accounts are created with correct counts and skipped rows reported") {
                testApplication {
                    application { testModule() }
                    val token = signIn(UserRole.SYSADMIN).token

                    val xlsx = buildTestXlsx()
                    val response = uploadXlsx(token, xlsx)

                    response.status shouldBe HttpStatusCode.OK
                    val data = response.bodyAsText().asJson().data()
                    data["providerName"]!!.jsonPrimitive.content shouldBe "Globe"
                    data["providerCreated"]!!.jsonPrimitive.boolean shouldBe true
                    data["storesCreated"]!!.jsonPrimitive.int shouldBe 2
                    data["storesReused"]!!.jsonPrimitive.int shouldBe 0
                    data["accountsCreated"]!!.jsonPrimitive.int shouldBe 3
                    data["accountsReused"]!!.jsonPrimitive.int shouldBe 0
                    data["rowsSkipped"]!!.jsonPrimitive.int shouldBe 2
                    data["totalRows"]!!.jsonPrimitive.int shouldBe 5
                }
            }
        }

        When("re-uploading the same XLSX") {
            Then("no new records are created and reused counts increment") {
                testApplication {
                    application { testModule() }
                    val token = signIn(UserRole.SYSADMIN).token

                    val xlsx = buildTestXlsx()

                    // First upload
                    uploadXlsx(token, xlsx)

                    // Second upload (idempotency)
                    val response2 = uploadXlsx(token, xlsx)

                    response2.status shouldBe HttpStatusCode.OK
                    val data2 = response2.bodyAsText().asJson().data()
                    data2["providerCreated"]!!.jsonPrimitive.boolean shouldBe false
                    data2["storesCreated"]!!.jsonPrimitive.int shouldBe 0
                    data2["storesReused"]!!.jsonPrimitive.int shouldBe 2
                    data2["accountsCreated"]!!.jsonPrimitive.int shouldBe 0
                    data2["accountsReused"]!!.jsonPrimitive.int shouldBe 3
                    data2["rowsSkipped"]!!.jsonPrimitive.int shouldBe 2
                }
            }
        }
    }
})
