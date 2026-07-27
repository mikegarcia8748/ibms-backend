package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
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
 * summary counts (providers/stores/accounts created, skipped rows), and re-uploads
 * the same file to prove idempotency. Also covers multi-provider imports and
 * case-insensitive header matching.
 */
class BulkImportSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject

    /** Builds a small XLSX fixture with multiple providers and 3 skip cases. */
    fun buildTestXlsx(): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")

            // Header
            val header = sheet.createRow(0)
            listOf(
                "Store Code", "Store Name", "ISP/Provider", "Service Type",
                "Account No", "Circuit ID", "Start Date", "Monthly Recurring Amount",
            ).forEachIndexed { i, name -> header.createCell(i).setCellValue(name) }

            // Row 1: store 118, Globe, SDWAN
            var r = sheet.createRow(1)
            r.createCell(0).setCellValue("118")
            r.createCell(1).setCellValue("PUREGOLD - QI CENTRAL")
            r.createCell(2).setCellValue("Globe")
            r.createCell(3).setCellValue("SDWAN")
            r.createCell(4).setCellValue("ACC001")
            r.createCell(5).setCellValue("IC-AWZ-001")
            r.createCell(6).setCellValue("11/20/2024")
            r.createCell(7).setCellValue(5598.0)

            // Row 2: same store 118, Globe, different service type
            r = sheet.createRow(2)
            r.createCell(0).setCellValue("118")
            r.createCell(1).setCellValue("PUREGOLD - QI CENTRAL")
            r.createCell(2).setCellValue("Globe")
            r.createCell(3).setCellValue("Broadband")
            r.createCell(4).setCellValue("ACC002")
            r.createCell(5).setCellValue("IC-AWZ-002")
            r.createCell(6).setCellValue("2/8/2022")
            r.createCell(7).setCellValue("2,798.00")

            // Row 3: store 8041, Globe, GPON, NO circuit ID, no start date
            // (exercises both the empty-circuit import path and the missing-date notes path)
            r = sheet.createRow(3)
            r.createCell(0).setCellValue("8041")
            r.createCell(1).setCellValue("PUREGOLD - BRANCH 2")
            r.createCell(2).setCellValue("Globe")
            r.createCell(3).setCellValue("GPON")
            r.createCell(4).setCellValue("ACC003")
            r.createCell(7).setCellValue(1500.0)

            // Row 4: store 200, Converge, Broadband
            r = sheet.createRow(4)
            r.createCell(0).setCellValue("200")
            r.createCell(1).setCellValue("PUREGOLD - CONVERGE BRANCH")
            r.createCell(2).setCellValue("Converge")
            r.createCell(3).setCellValue("Broadband")
            r.createCell(4).setCellValue("ACC004")
            r.createCell(5).setCellValue("IC-CONV-001")
            r.createCell(6).setCellValue("1/15/2025")
            r.createCell(7).setCellValue(2000.0)

            // Row 5: store 201, PLDT, DIA
            r = sheet.createRow(5)
            r.createCell(0).setCellValue("201")
            r.createCell(1).setCellValue("PUREGOLD - PLDT BRANCH")
            r.createCell(2).setCellValue("PLDT")
            r.createCell(3).setCellValue("DIA")
            r.createCell(4).setCellValue("ACC005")
            r.createCell(5).setCellValue("IC-PLDT-001")
            r.createCell(6).setCellValue("3/10/2025")
            r.createCell(7).setCellValue(3000.0)

            // Row 6: missing Store Code -> skip
            r = sheet.createRow(6)
            r.createCell(1).setCellValue("No Store")
            r.createCell(2).setCellValue("Globe")
            r.createCell(4).setCellValue("ACC006")
            r.createCell(7).setCellValue(500.0)

            // Row 7: zero MRC -> skip
            r = sheet.createRow(7)
            r.createCell(0).setCellValue("999")
            r.createCell(1).setCellValue("Zero MRC Store")
            r.createCell(2).setCellValue("Globe")
            r.createCell(4).setCellValue("ACC007")
            r.createCell(7).setCellValue(0.0)

            // Row 8: blank ISP/Provider -> skip
            r = sheet.createRow(8)
            r.createCell(0).setCellValue("300")
            r.createCell(1).setCellValue("No Provider Store")
            r.createCell(4).setCellValue("ACC008")
            r.createCell(7).setCellValue(1000.0)

            // Row 9: no Circuit ID -> still imports (circuit is optional; store scopes identity)
            r = sheet.createRow(9)
            r.createCell(0).setCellValue("8042")
            r.createCell(1).setCellValue("PUREGOLD - BRANCH 3")
            r.createCell(2).setCellValue("Globe")
            r.createCell(3).setCellValue("GPON")
            r.createCell(4).setCellValue("ACC009")
            r.createCell(7).setCellValue(1500.0)

            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }

    /** Builds an XLSX with ALL-CAPS headers to test case-insensitive matching. */
    fun buildUpperCaseHeaderXlsx(): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")

            // Header — all uppercase
            val header = sheet.createRow(0)
            listOf(
                "STORE CODE", "STORE NAME", "ISP/Provider", "SERVICE TYPE",
                "ACCOUNT NO", "CIRCUIT ID", "START DATE", "MONTHLY RECURRING AMOUNT",
            ).forEachIndexed { i, name -> header.createCell(i).setCellValue(name) }

            // Single data row
            val r = sheet.createRow(1)
            r.createCell(0).setCellValue("500")
            r.createCell(1).setCellValue("UPPERCASE HEADER STORE")
            r.createCell(2).setCellValue("Radius")
            r.createCell(3).setCellValue("SDWAN")
            r.createCell(4).setCellValue("ACC-UPPER-001")
            r.createCell(5).setCellValue("IC-RAD-001")
            r.createCell(6).setCellValue("6/1/2025")
            r.createCell(7).setCellValue(2500.0)

            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }

    /**
     * Builds an XLSX with the standard header and the given data rows. Each row is a list of up to 8
     * cell values in column order (Store Code, Store Name, ISP/Provider, Service Type, Account No,
     * Circuit ID, Start Date, Monthly Recurring Amount); a null value leaves that cell empty, a Double
     * writes a numeric cell, anything else writes a string cell.
     */
    fun buildXlsx(rows: List<List<Any?>>): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")
            val header = sheet.createRow(0)
            listOf(
                "Store Code", "Store Name", "ISP/Provider", "Service Type",
                "Account No", "Circuit ID", "Start Date", "Monthly Recurring Amount",
            ).forEachIndexed { i, name -> header.createCell(i).setCellValue(name) }

            rows.forEachIndexed { rIdx, cells ->
                val r = sheet.createRow(rIdx + 1)
                cells.forEachIndexed { cIdx, value ->
                    when (value) {
                        null -> {}
                        is Double -> r.createCell(cIdx).setCellValue(value)
                        else -> r.createCell(cIdx).setCellValue(value.toString())
                    }
                }
            }

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

    Given("a sysadmin and a multi-provider XLSX file") {
        When("uploading the XLSX via POST /accounts/bulk-import") {
            Then("providers, stores, and accounts are created with correct counts and skipped rows reported") {
                testApplication {
                    application { testModule() }
                    val token = signIn(UserRole.SYSADMIN).token

                    val xlsx = buildTestXlsx()
                    val response = uploadXlsx(token, xlsx)

                    response.status shouldBe HttpStatusCode.OK
                    val data = response.bodyAsText().asJson().data()

                    // Providers (sorted alphabetically: Converge, Globe, PLDT)
                    val providers = data["providers"]!!.jsonArray
                    providers.size shouldBe 3

                    val converge = providers[0].jsonObject
                    converge["name"]!!.jsonPrimitive.content shouldBe "Converge"
                    converge["created"]!!.jsonPrimitive.boolean shouldBe true
                    converge["accountsCreated"]!!.jsonPrimitive.int shouldBe 1
                    converge["accountsReused"]!!.jsonPrimitive.int shouldBe 0

                    // Globe: ACC001, ACC002, ACC003 (empty circuit), ACC009 (empty circuit) = 4.
                    val globe = providers[1].jsonObject
                    globe["name"]!!.jsonPrimitive.content shouldBe "Globe"
                    globe["created"]!!.jsonPrimitive.boolean shouldBe true
                    globe["accountsCreated"]!!.jsonPrimitive.int shouldBe 4
                    globe["accountsReused"]!!.jsonPrimitive.int shouldBe 0

                    val pldt = providers[2].jsonObject
                    pldt["name"]!!.jsonPrimitive.content shouldBe "PLDT"
                    pldt["created"]!!.jsonPrimitive.boolean shouldBe true
                    pldt["accountsCreated"]!!.jsonPrimitive.int shouldBe 1
                    pldt["accountsReused"]!!.jsonPrimitive.int shouldBe 0

                    // Stores 118, 8041, 200, 201, 8042 = 5.
                    data["storesCreated"]!!.jsonPrimitive.int shouldBe 5
                    data["storesReused"]!!.jsonPrimitive.int shouldBe 0
                    data["accountsCreated"]!!.jsonPrimitive.int shouldBe 6
                    data["accountsReused"]!!.jsonPrimitive.int shouldBe 0
                    // 3 skips: missing store code, zero MRC, blank provider. (Empty circuit is NOT a skip.)
                    data["rowsSkipped"]!!.jsonPrimitive.int shouldBe 3
                    data["totalRows"]!!.jsonPrimitive.int shouldBe 9
                    data["rowsFailed"]!!.jsonPrimitive.int shouldBe 0
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

                    // All providers reused, none created
                    val providers2 = data2["providers"]!!.jsonArray
                    providers2.size shouldBe 3
                    providers2.forEach { p ->
                        p.jsonObject["created"]!!.jsonPrimitive.boolean shouldBe false
                        p.jsonObject["accountsCreated"]!!.jsonPrimitive.int shouldBe 0
                    }

                    // Converge: 1 reused, Globe: 4 reused, PLDT: 1 reused
                    providers2[0].jsonObject["accountsReused"]!!.jsonPrimitive.int shouldBe 1
                    providers2[1].jsonObject["accountsReused"]!!.jsonPrimitive.int shouldBe 4
                    providers2[2].jsonObject["accountsReused"]!!.jsonPrimitive.int shouldBe 1

                    data2["storesCreated"]!!.jsonPrimitive.int shouldBe 0
                    data2["storesReused"]!!.jsonPrimitive.int shouldBe 5
                    data2["accountsCreated"]!!.jsonPrimitive.int shouldBe 0
                    data2["accountsReused"]!!.jsonPrimitive.int shouldBe 6
                    data2["rowsSkipped"]!!.jsonPrimitive.int shouldBe 3
                    data2["rowsFailed"]!!.jsonPrimitive.int shouldBe 0
                }
            }
        }
    }

    Given("a sysadmin and an XLSX file with uppercase headers") {
        When("uploading the XLSX via POST /accounts/bulk-import") {
            Then("case-insensitive header matching allows successful import") {
                testApplication {
                    application { testModule() }
                    val token = signIn(UserRole.SYSADMIN).token

                    val xlsx = buildUpperCaseHeaderXlsx()
                    val response = uploadXlsx(token, xlsx)

                    response.status shouldBe HttpStatusCode.OK
                    val data = response.bodyAsText().asJson().data()

                    val providers = data["providers"]!!.jsonArray
                    providers.size shouldBe 1
                    providers[0].jsonObject["name"]!!.jsonPrimitive.content shouldBe "Radius"
                    providers[0].jsonObject["created"]!!.jsonPrimitive.boolean shouldBe true
                    providers[0].jsonObject["accountsCreated"]!!.jsonPrimitive.int shouldBe 1

                    data["storesCreated"]!!.jsonPrimitive.int shouldBe 1
                    data["accountsCreated"]!!.jsonPrimitive.int shouldBe 1
                    data["rowsSkipped"]!!.jsonPrimitive.int shouldBe 0
                    data["totalRows"]!!.jsonPrimitive.int shouldBe 1
                }
            }
        }
    }

    Given("a sysadmin and an XLSX where one account number carries multiple distinct circuits") {
        When("uploading it, then re-uploading the same file") {
            Then("each distinct circuit becomes its own account and the re-upload reuses them") {
                testApplication {
                    application { testModule() }
                    val token = signIn(UserRole.SYSADMIN).token

                    // Same store + provider + account number; three different circuit IDs. Before the fix,
                    // only the first row imported (1 created, 2 reused).
                    val xlsx = buildXlsx(
                        listOf(
                            listOf("CS-777", "CIRCUIT SHARE STORE", "CircuitShareISP", "SDWAN", "CS-ACC-777", "CID-A", "1/1/2025", 1000.0),
                            listOf("CS-777", "CIRCUIT SHARE STORE", "CircuitShareISP", "SDWAN", "CS-ACC-777", "CID-B", "1/1/2025", 1000.0),
                            listOf("CS-777", "CIRCUIT SHARE STORE", "CircuitShareISP", "SDWAN", "CS-ACC-777", "CID-C", "1/1/2025", 1000.0),
                        ),
                    )

                    val resp = uploadXlsx(token, xlsx)
                    resp.status shouldBe HttpStatusCode.OK
                    val data = resp.bodyAsText().asJson().data()
                    data["accountsCreated"]!!.jsonPrimitive.int shouldBe 3
                    data["accountsReused"]!!.jsonPrimitive.int shouldBe 0
                    data["storesCreated"]!!.jsonPrimitive.int shouldBe 1
                    data["rowsSkipped"]!!.jsonPrimitive.int shouldBe 0
                    data["rowsFailed"]!!.jsonPrimitive.int shouldBe 0
                    data["totalRows"]!!.jsonPrimitive.int shouldBe 3

                    val data2 = uploadXlsx(token, xlsx).bodyAsText().asJson().data()
                    data2["accountsCreated"]!!.jsonPrimitive.int shouldBe 0
                    data2["accountsReused"]!!.jsonPrimitive.int shouldBe 3
                    data2["storesReused"]!!.jsonPrimitive.int shouldBe 1
                    data2["rowsFailed"]!!.jsonPrimitive.int shouldBe 0
                }
            }
        }
    }

    Given("a sysadmin and an XLSX where the same account number + empty circuit appears at different stores") {
        When("uploading it") {
            Then("store scopes identity: each store gets its own account, and a same-store duplicate is reused") {
                testApplication {
                    application { testModule() }
                    val token = signIn(UserRole.SYSADMIN).token

                    // Same provider + account number + EMPTY circuit; rows 1 & 2 differ only by store (so they
                    // are distinct accounts); row 3 duplicates row 1 exactly (so it dedupes).
                    val xlsx = buildXlsx(
                        listOf(
                            listOf("SS-STORE-A", "STORE A", "StoreScopeISP", "SDWAN", "SS-ACC-1", null, "1/1/2025", 1000.0),
                            listOf("SS-STORE-B", "STORE B", "StoreScopeISP", "SDWAN", "SS-ACC-1", null, "1/1/2025", 1000.0),
                            listOf("SS-STORE-A", "STORE A", "StoreScopeISP", "SDWAN", "SS-ACC-1", null, "1/1/2025", 1000.0),
                        ),
                    )

                    val resp = uploadXlsx(token, xlsx)
                    resp.status shouldBe HttpStatusCode.OK
                    val data = resp.bodyAsText().asJson().data()
                    // Row 1 (store A) + row 2 (store B) = 2 distinct accounts; row 3 = same-store dup -> reused.
                    data["accountsCreated"]!!.jsonPrimitive.int shouldBe 2
                    data["accountsReused"]!!.jsonPrimitive.int shouldBe 1
                    data["storesCreated"]!!.jsonPrimitive.int shouldBe 2
                    data["rowsSkipped"]!!.jsonPrimitive.int shouldBe 0
                    data["rowsFailed"]!!.jsonPrimitive.int shouldBe 0
                    data["totalRows"]!!.jsonPrimitive.int shouldBe 3
                }
            }
        }
    }

    Given("a sysadmin and an XLSX containing a malformed Monthly Recurring Amount") {
        When("uploading it") {
            Then("the bad row is skipped-and-reported and valid rows still import (no 500 / rollback)") {
                testApplication {
                    application { testModule() }
                    val token = signIn(UserRole.SYSADMIN).token

                    val xlsx = buildXlsx(
                        listOf(
                            listOf("MRC-OK", "MRC OK STORE", "MalformISP", "SDWAN", "MRC-ACC-1", "MC-1", "1/1/2025", 1200.0),
                            listOf("MRC-BAD", "MRC BAD STORE", "MalformISP", "SDWAN", "MRC-ACC-2", "MC-2", "1/1/2025", "abc"),
                        ),
                    )

                    val resp = uploadXlsx(token, xlsx)
                    resp.status shouldBe HttpStatusCode.OK
                    val data = resp.bodyAsText().asJson().data()
                    data["accountsCreated"]!!.jsonPrimitive.int shouldBe 1
                    data["rowsSkipped"]!!.jsonPrimitive.int shouldBeGreaterThanOrEqual 1
                    data["rowsFailed"]!!.jsonPrimitive.int shouldBe 0
                    val skipReasons = data["skipReasons"]!!.jsonArray.map { it.jsonPrimitive.content }
                    skipReasons.any { it.contains("invalid or zero Monthly Recurring Amount") } shouldBe true
                }
            }
        }
    }

    Given("a sysadmin and an XLSX where one row fails at the DB layer") {
        When("uploading it") {
            Then("valid rows commit and the failing row is reported without rolling back the others") {
                testApplication {
                    application { testModule() }
                    val token = signIn(UserRole.SYSADMIN).token

                    // Second row passes app validation but overflows numeric(14,2) at insert (16 integer
                    // digits >> the 12-digit cap), forcing a per-row DB failure.
                    val xlsx = buildXlsx(
                        listOf(
                            listOf("PC-OK", "PC OK STORE", "PartISP", "SDWAN", "PC-ACC-1", "PC-1", "1/1/2025", 1000.0),
                            listOf("PC-BAD", "PC BAD STORE", "PartISP", "SDWAN", "PC-ACC-2", "PC-2", "1/1/2025", "9999999999999999"),
                        ),
                    )

                    val resp = uploadXlsx(token, xlsx)
                    resp.status shouldBe HttpStatusCode.OK
                    val data = resp.bodyAsText().asJson().data()
                    data["accountsCreated"]!!.jsonPrimitive.int shouldBe 1
                    data["rowsFailed"]!!.jsonPrimitive.int shouldBe 1
                    data["failureReasons"]!!.jsonArray.size shouldBeGreaterThanOrEqual 1

                    // Re-upload: the good row is now reused; the bad row fails again — proving the good
                    // row committed and the bad row's store + account rolled back on the first run.
                    val data2 = uploadXlsx(token, xlsx).bodyAsText().asJson().data()
                    data2["accountsCreated"]!!.jsonPrimitive.int shouldBe 0
                    data2["accountsReused"]!!.jsonPrimitive.int shouldBe 1
                    data2["rowsFailed"]!!.jsonPrimitive.int shouldBe 1
                }
            }
        }
    }
})
