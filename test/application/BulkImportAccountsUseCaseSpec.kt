package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.BulkImportAccountsUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.Attachment
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.Provider
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.model.StoreUpsertRequest
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.BatchSequenceRepository
import com.puregoldbe.ibms.domain.port.InvoiceSequenceRepository
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream

/** Canonical 8-column header expected by the importer. */
private val HEADERS = listOf(
    "Store Code", "Store Name", "ISP/Provider", "Service Type",
    "Account No", "Circuit ID", "Start Date", "Monthly Recurring Amount",
)

private fun proofAttachment(id: String = "att-proof") = Attachment(
    id = id,
    purpose = AttachmentPurpose.INSTALLATION_PROOF,
    entityType = "store",
    storageKey = "bulk-import/placeholder-installation-proof",
    uploadedBy = "actor",
    createdAt = Instant.fromEpochSeconds(0),
)

private fun sampleProvider(name: String, id: String = "prov-$name") = Provider(
    id = id, name = name, paymentScheduleDay = 5, createdAt = Instant.fromEpochSeconds(0),
)

private fun sampleStore(req: StoreUpsertRequest) = Store(
    id = "store-${req.branchCode}",
    storeType = req.storeType,
    branchCode = req.branchCode,
    name = req.name,
    proofOfInstallationId = req.proofOfInstallationId,
    createdAt = Instant.fromEpochSeconds(0),
)

private fun sampleAccount(req: AccountUpsertRequest) = Account(
    id = "acc-${req.accountNumber}",
    accountNumber = req.accountNumber,
    circuitId = req.circuitId,
    providerId = req.providerId,
    storeId = req.storeId,
    serviceType = req.serviceType,
    rate = req.rate,
    installationDate = req.installationDate,
    contractStartDate = req.contractStartDate,
    notes = req.notes,
    createdAt = Instant.fromEpochSeconds(0),
)

/**
 * Unit spec for [BulkImportAccountsUseCase]: exercises the parsing/validation control
 * flow against real Apache POI XLSX bytes with all repositories mocked (MockK) and an
 * immediate transaction runner. Complements the DB-backed adapter test BulkImportSpec,
 * focusing on negative scenarios, edge cases, and the two hardened gaps (non-numeric
 * MRC skip, unparseable-date note).
 */
class BulkImportAccountsUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val providers = mockk<ProviderRepository>()
    val sequences = mockk<InvoiceSequenceRepository>(relaxed = true)
    val batchSequences = mockk<BatchSequenceRepository>(relaxed = true)
    val stores = mockk<StoreRepository>()
    val accounts = mockk<AccountRepository>()
    val attachments = mockk<AttachmentRepository>()
    val activity = mockk<ActivityRecorder>(relaxed = true)

    val useCase = BulkImportAccountsUseCase(
        providers, sequences, batchSequences, stores, accounts, attachments, activity,
        ImmediateTransactionRunner(),
    )

    // Captured requests, populated by the default create stubs below.
    val storeReq = slot<StoreUpsertRequest>()
    val accountReq = slot<AccountUpsertRequest>()

    // ---- Default "everything is new" stubs (overridden per-Given as needed) ----
    every { attachments.create(any(), any(), any(), any(), any(), any(), any()) } returns proofAttachment()
    every { providers.findByName(any()) } returns null
    every { providers.create(any(), any()) } answers { sampleProvider(firstArg()) }
    every { stores.findByBranchCode(any()) } returns null
    every { stores.create(capture(storeReq), any()) } answers { sampleStore(firstArg()) }
    every { accounts.existsByProviderAndNumber(any(), any()) } returns false
    every { accounts.create(capture(accountReq), any()) } answers { sampleAccount(firstArg()) }

    // ---- XLSX fixture builders ----

    /** One data row aligned to [HEADERS]; a null cell is left blank. */
    fun row(
        storeCode: Any? = "118",
        storeName: Any? = "PUREGOLD QI",
        provider: Any? = "Globe",
        serviceType: Any? = "SDWAN",
        accountNo: Any? = "ACC001",
        circuitId: Any? = "IC-001",
        startDate: Any? = "11/20/2024",
        mrc: Any? = 1500.0,
    ): List<Any?> = listOf(storeCode, storeName, provider, serviceType, accountNo, circuitId, startDate, mrc)

    /**
     * Builds an XLSX. [rows] entries map to sheet rows 1..N; a null entry leaves a gap
     * (POI getRow returns null there) so interior-blank-row behaviour can be exercised.
     */
    fun xlsx(headers: List<String> = HEADERS, rows: List<List<Any?>?> = emptyList()): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")
            val header = sheet.createRow(0)
            headers.forEachIndexed { i, name -> header.createCell(i).setCellValue(name) }
            rows.forEachIndexed { ri, cells ->
                if (cells == null) return@forEachIndexed
                val r = sheet.createRow(ri + 1)
                cells.forEachIndexed { ci, v ->
                    when (v) {
                        null -> {} // blank cell
                        is String -> r.createCell(ci).setCellValue(v)
                        is Double -> r.createCell(ci).setCellValue(v)
                        is Int -> r.createCell(ci).setCellValue(v.toDouble())
                        else -> r.createCell(ci).setCellValue(v.toString())
                    }
                }
            }
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }

    /** A workbook whose first sheet has no rows at all (getRow(0) == null). */
    fun sheetWithNoRows(): ByteArray {
        XSSFWorkbook().use { wb ->
            wb.createSheet("Sheet1")
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }

    /** A row whose Start Date is a genuine date-formatted numeric cell (not a string). */
    fun xlsxWithDateFormattedStartDate(date: java.time.LocalDate): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")
            val header = sheet.createRow(0)
            HEADERS.forEachIndexed { i, name -> header.createCell(i).setCellValue(name) }
            val r = sheet.createRow(1)
            r.createCell(0).setCellValue("118")
            r.createCell(1).setCellValue("Branch")
            r.createCell(2).setCellValue("Globe")
            r.createCell(3).setCellValue("SDWAN")
            r.createCell(4).setCellValue("ACC001")
            r.createCell(5).setCellValue("IC-001")
            val dateStyle = wb.createCellStyle().apply {
                dataFormat = wb.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
            }
            val dateCell = r.createCell(6)
            dateCell.cellStyle = dateStyle
            dateCell.setCellValue(date)
            r.createCell(7).setCellValue(1500.0)
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }

    // ======================================================================
    //  A. File / structure validation  →  DomainError.Validation("invalid_file")
    // ======================================================================
    Given("bytes that are not a valid XLSX") {
        When("importing") {
            Then("it throws Validation with code invalid_file") {
                val err = shouldThrow<DomainError.Validation> {
                    useCase("this is not a spreadsheet".toByteArray(), "actor")
                }
                err.code shouldBe "invalid_file"
            }
        }
    }

    Given("an empty byte array") {
        When("importing") {
            Then("it throws Validation with code invalid_file") {
                val err = shouldThrow<DomainError.Validation> { useCase(ByteArray(0), "actor") }
                err.code shouldBe "invalid_file"
            }
        }
    }

    Given("a workbook whose first sheet has no header row") {
        When("importing") {
            Then("it throws Validation: no header row") {
                val err = shouldThrow<DomainError.Validation> { useCase(sheetWithNoRows(), "actor") }
                err.message shouldBe "spreadsheet has no header row"
                err.code shouldBe "invalid_file"
            }
        }
    }

    Given("a header row missing the required Account No column") {
        val headers = HEADERS.filterNot { it == "Account No" }
        When("importing") {
            Then("it throws Validation naming the missing column") {
                val err = shouldThrow<DomainError.Validation> {
                    useCase(xlsx(headers = headers, rows = listOf(row())), "actor")
                }
                err.message shouldBe "missing required column 'Account No' in header row"
                err.code shouldBe "invalid_file"
            }
        }
    }

    Given("a header row missing the required Monthly Recurring Amount column") {
        val headers = HEADERS.filterNot { it == "Monthly Recurring Amount" }
        When("importing") {
            Then("it throws Validation naming the missing column") {
                val err = shouldThrow<DomainError.Validation> {
                    useCase(xlsx(headers = headers, rows = listOf(row())), "actor")
                }
                err.message shouldBe "missing required column 'Monthly Recurring Amount' in header row"
            }
        }
    }

    // ======================================================================
    //  B. Row-level skips  (counted in rowsSkipped + totalRows; no side effects)
    // ======================================================================
    Given("a row missing Store Code") {
        val bytes = xlsx(rows = listOf(row(storeCode = null)))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the row is skipped and reported, nothing is created") {
                result.rowsSkipped shouldBe 1
                result.totalRows shouldBe 1
                result.skipReasons shouldBe listOf("Row 2: missing Store Code")
                result.accountsCreated shouldBe 0
                result.storesCreated shouldBe 0
                result.providers.size shouldBe 0
                verify(exactly = 0) { accounts.create(any(), any()) }
            }
        }
    }

    Given("a row missing ISP/Provider") {
        val bytes = xlsx(rows = listOf(row(provider = null)))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the row is skipped with the provider reason") {
                result.rowsSkipped shouldBe 1
                result.skipReasons shouldBe listOf("Row 2: missing ISP/Provider")
                verify(exactly = 0) { accounts.create(any(), any()) }
            }
        }
    }

    Given("a row missing Account No") {
        val bytes = xlsx(rows = listOf(row(accountNo = null)))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the row is skipped with the account reason") {
                result.rowsSkipped shouldBe 1
                result.skipReasons shouldBe listOf("Row 2: missing Account No")
                verify(exactly = 0) { accounts.create(any(), any()) }
            }
        }
    }

    Given("a row with a zero Monthly Recurring Amount") {
        val bytes = xlsx(rows = listOf(row(mrc = 0.0)))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the row is skipped as invalid/zero MRC") {
                result.rowsSkipped shouldBe 1
                result.skipReasons shouldBe listOf("Row 2: invalid or zero Monthly Recurring Amount")
                verify(exactly = 0) { accounts.create(any(), any()) }
            }
        }
    }

    Given("a row with a negative Monthly Recurring Amount") {
        val bytes = xlsx(rows = listOf(row(mrc = -100.0)))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the row is skipped as invalid/zero MRC") {
                result.rowsSkipped shouldBe 1
                result.skipReasons shouldBe listOf("Row 2: invalid or zero Monthly Recurring Amount")
                verify(exactly = 0) { accounts.create(any(), any()) }
            }
        }
    }

    // Bug-fix #1: a non-numeric MRC must skip the row, not crash the whole import.
    Given("a row with a non-numeric Monthly Recurring Amount") {
        val bytes = xlsx(rows = listOf(row(mrc = "abc")))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the row is skipped gracefully without throwing") {
                result.rowsSkipped shouldBe 1
                result.totalRows shouldBe 1
                result.skipReasons shouldBe listOf("Row 2: invalid or zero Monthly Recurring Amount")
                result.accountsCreated shouldBe 0
                verify(exactly = 0) { accounts.create(any(), any()) }
            }
        }
    }

    Given("a row with a blank Monthly Recurring Amount cell") {
        val bytes = xlsx(rows = listOf(row(mrc = null)))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the row is skipped as invalid/zero MRC") {
                result.rowsSkipped shouldBe 1
                result.skipReasons shouldBe listOf("Row 2: invalid or zero Monthly Recurring Amount")
            }
        }
    }

    // ======================================================================
    //  C. Interior blank rows are excluded from totalRows and rowsSkipped
    // ======================================================================
    Given("two valid rows separated by a blank row") {
        val bytes = xlsx(
            rows = listOf(
                row(accountNo = "ACC001"),
                null, // gap -> POI getRow returns null
                row(accountNo = "ACC002"),
            ),
        )
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("only the two real rows are counted; the gap is invisible") {
                result.totalRows shouldBe 2
                result.rowsSkipped shouldBe 0
                result.accountsCreated shouldBe 2
                result.storesCreated shouldBe 1
            }
        }
    }

    // ======================================================================
    //  D. Idempotency / duplicate detection
    // ======================================================================
    Given("an ISP/Provider that already exists") {
        every { providers.findByName("Globe") } returns sampleProvider("Globe", id = "prov-existing")
        val bytes = xlsx(rows = listOf(row()))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the provider is reused, not created, and sequences are not seeded") {
                result.providers.size shouldBe 1
                result.providers[0].created shouldBe false
                verify(exactly = 0) { providers.create(any(), any()) }
                verify(exactly = 0) { sequences.seed(any(), any()) }
                verify(exactly = 0) { batchSequences.seed(any()) }
            }
        }
    }

    Given("a store branch code that already exists") {
        every { stores.findByBranchCode("118") } returns sampleStore(
            StoreUpsertRequest(
                storeType = StoreType.PUREGOLD, branchCode = "118", name = "Existing",
                proofOfInstallationId = "att-x",
            ),
        )
        val bytes = xlsx(rows = listOf(row()))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the store is reused, not created") {
                result.storesReused shouldBe 1
                result.storesCreated shouldBe 0
                verify(exactly = 0) { stores.create(any(), any()) }
            }
        }
    }

    Given("an account that already exists for the provider") {
        every { accounts.existsByProviderAndNumber(any(), "ACC001") } returns true
        val bytes = xlsx(rows = listOf(row(accountNo = "ACC001")))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the account is reused; no create, no activity recorded") {
                result.accountsReused shouldBe 1
                result.accountsCreated shouldBe 0
                result.providers[0].accountsReused shouldBe 1
                verify(exactly = 0) { accounts.create(any(), any()) }
                verify(exactly = 0) { activity.record(any(), any(), any(), any(), any()) }
            }
        }
    }

    // ======================================================================
    //  E. Creation details & defaults
    // ======================================================================
    Given("a new provider") {
        val bytes = xlsx(rows = listOf(row(provider = "Globe")))
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("it is created and both invoice + batch sequences are seeded once") {
                result.providers[0].created shouldBe true
                verify(exactly = 1) { sequences.seed("prov-Globe", any()) }
                verify(exactly = 1) { batchSequences.seed("prov-Globe") }
            }
        }
    }

    Given("a row whose Store Name cell is blank") {
        val bytes = xlsx(rows = listOf(row(storeCode = "118", storeName = null)))
        When("importing") {
            useCase(bytes, "actor")
            Then("the store name defaults to the store code, type PUREGOLD, shared proof") {
                storeReq.captured.name shouldBe "118"
                storeReq.captured.storeType shouldBe StoreType.PUREGOLD
                storeReq.captured.proofOfInstallationId shouldBe "att-proof"
            }
        }
    }

    Given("a row with a blank Start Date") {
        val bytes = xlsx(rows = listOf(row(startDate = null)))
        When("importing") {
            useCase(bytes, "actor")
            Then("installation date falls back to epoch, note recorded, contractStartDate null") {
                accountReq.captured.installationDate shouldBe LocalDate(1970, 1, 1)
                accountReq.captured.contractStartDate shouldBe null
                accountReq.captured.notes shouldBe "Installation date unavailable from source (bulk import)"
            }
        }
    }

    // Bug-fix #2: an unparseable (but present) date must also carry the note.
    Given("a row with a present but unparseable Start Date") {
        val bytes = xlsx(rows = listOf(row(startDate = "not-a-date")))
        When("importing") {
            useCase(bytes, "actor")
            Then("epoch fallback AND the unavailable-note are applied") {
                accountReq.captured.installationDate shouldBe LocalDate(1970, 1, 1)
                accountReq.captured.contractStartDate shouldBe null
                accountReq.captured.notes shouldBe "Installation date unavailable from source (bulk import)"
            }
        }
    }

    Given("a row with blank Circuit ID and Service Type") {
        val bytes = xlsx(rows = listOf(row(circuitId = null, serviceType = null)))
        When("importing") {
            useCase(bytes, "actor")
            Then("those optional fields are null on the account request") {
                accountReq.captured.circuitId shouldBe null
                accountReq.captured.serviceType shouldBe null
            }
        }
    }

    Given("a valid new account row") {
        val bytes = xlsx(rows = listOf(row(accountNo = "ACC001")))
        When("importing") {
            useCase(bytes, "actor")
            Then("a bulk_imported activity is recorded for the new account") {
                verify(exactly = 1) {
                    activity.record("actor", "account.bulk_imported", "account", "acc-ACC001")
                }
            }
        }
    }

    // ======================================================================
    //  F. Caching / aggregation
    // ======================================================================
    Given("two rows for the same store code") {
        val bytes = xlsx(
            rows = listOf(
                row(storeCode = "118", accountNo = "ACC001"),
                row(storeCode = "118", accountNo = "ACC002"),
            ),
        )
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("the store is looked up and created only once") {
                result.storesCreated shouldBe 1
                verify(exactly = 1) { stores.findByBranchCode("118") }
                verify(exactly = 1) { stores.create(any(), any()) }
            }
        }
    }

    Given("two rows for the same provider") {
        val bytes = xlsx(
            rows = listOf(
                row(provider = "Globe", storeCode = "118", accountNo = "ACC001"),
                row(provider = "Globe", storeCode = "200", accountNo = "ACC002"),
            ),
        )
        When("importing") {
            useCase(bytes, "actor")
            Then("the provider is resolved only once (cached)") {
                verify(exactly = 1) { providers.findByName("Globe") }
            }
        }
    }

    Given("rows for three different providers") {
        val bytes = xlsx(
            rows = listOf(
                row(provider = "PLDT", storeCode = "1", accountNo = "A1"),
                row(provider = "Converge", storeCode = "2", accountNo = "A2"),
                row(provider = "Globe", storeCode = "3", accountNo = "A3"),
            ),
        )
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("provider summaries are sorted alphabetically by name") {
                result.providers.map { it.name } shouldBe listOf("Converge", "Globe", "PLDT")
            }
        }
    }

    // ======================================================================
    //  G. Cell / value parsing through invoke
    // ======================================================================
    Given("an MRC string with a currency symbol and thousands separator") {
        val bytes = xlsx(rows = listOf(row(mrc = "₱2,798.00")))
        When("importing") {
            useCase(bytes, "actor")
            Then("the rate is normalised to a plain decimal string") {
                accountReq.captured.rate shouldBe "2798.00"
            }
        }
    }

    Given("a string-formatted Start Date") {
        val bytes = xlsx(rows = listOf(row(startDate = "11/20/2024")))
        When("importing") {
            useCase(bytes, "actor")
            Then("it parses to the expected date with no fallback note") {
                accountReq.captured.contractStartDate shouldBe LocalDate(2024, 11, 20)
                accountReq.captured.installationDate shouldBe LocalDate(2024, 11, 20)
                accountReq.captured.notes shouldBe null
            }
        }
    }

    Given("a Start Date given as an Excel serial number in a plain numeric cell") {
        val bytes = xlsx(rows = listOf(row(startDate = 45572.0)))
        When("importing") {
            useCase(bytes, "actor")
            Then("the serial is converted to the correct calendar date") {
                accountReq.captured.contractStartDate shouldBe LocalDate(2024, 10, 7)
                accountReq.captured.installationDate shouldBe LocalDate(2024, 10, 7)
            }
        }
    }

    Given("a Start Date in a genuine date-formatted numeric cell") {
        val bytes = xlsxWithDateFormattedStartDate(java.time.LocalDate.of(2024, 11, 20))
        When("importing") {
            useCase(bytes, "actor")
            Then("the date cell is read as an ISO date and parsed") {
                accountReq.captured.contractStartDate shouldBe LocalDate(2024, 11, 20)
            }
        }
    }

    Given("an Account No stored as a whole number in a numeric cell") {
        val bytes = xlsx(rows = listOf(row(accountNo = 123456.0)))
        When("importing") {
            useCase(bytes, "actor")
            Then("it is read without a trailing .0") {
                accountReq.captured.accountNumber shouldBe "123456"
                verify { accounts.existsByProviderAndNumber(any(), "123456") }
            }
        }
    }

    // ======================================================================
    //  H. Empty data (header only)
    // ======================================================================
    Given("a file with a header row but no data rows") {
        val bytes = xlsx(rows = emptyList())
        When("importing") {
            val result = useCase(bytes, "actor")
            Then("all counts are zero but the placeholder attachment is still created") {
                result.totalRows shouldBe 0
                result.rowsSkipped shouldBe 0
                result.storesCreated shouldBe 0
                result.accountsCreated shouldBe 0
                result.providers.size shouldBe 0
                verify(exactly = 1) {
                    attachments.create(
                        AttachmentPurpose.INSTALLATION_PROOF, "store", null,
                        "bulk-import/placeholder-installation-proof", null, null, "actor",
                    )
                }
            }
        }
    }

    // ======================================================================
    //  I. Transaction / exception propagation
    // ======================================================================
    Given("a repository that fails mid-import") {
        every { accounts.create(any(), any()) } throws RuntimeException("db down")
        val bytes = xlsx(rows = listOf(row()))
        When("importing") {
            Then("the exception propagates out of invoke (transaction would roll back)") {
                shouldThrow<RuntimeException> { useCase(bytes, "actor") }
            }
        }
    }
})
