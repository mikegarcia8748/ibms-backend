package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.adapter.controller.BulkImportSummary
import com.puregoldbe.ibms.adapter.controller.ProviderImportSummary
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.Provider
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.model.StoreUpsertRequest
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.BatchSequenceRepository
import com.puregoldbe.ibms.domain.port.InvoiceSequenceRepository
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.service.InvoiceNumberFormatter
import com.puregoldbe.ibms.domain.valueobject.Money
import kotlinx.datetime.LocalDate
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bulk-imports stores, providers, and accounts from an XLSX spreadsheet.
 *
 * The spreadsheet must have a header row with column names: Store Code, Store
 * Name, ISP/Provider, Service Type, Account No, Circuit ID, Start Date,
 * Monthly Recurring Amount. Column order does not matter — headers are matched
 * by name (case-insensitive).
 *
 * The import is idempotent: re-running with the same file creates no duplicates.
 * Providers are read from the ISP/Provider column and matched by name — a single
 * file may contain rows for multiple providers (e.g. PLDT, Globe, Radius, Converge).
 * Stores are matched by branchCode, accounts by (providerId, accountNumber). Rows
 * missing required fields (including ISP/Provider) or with a zero MRC are skipped
 * and reported in the summary.
 *
 * A single shared placeholder attachment (purpose = installation_proof) is
 * created to satisfy the NOT NULL FK on stores.proof_of_installation_id,
 * since bulk-imported stores do not carry individual proof documents.
 */
class BulkImportAccountsUseCase(
    private val providers: ProviderRepository,
    private val sequences: InvoiceSequenceRepository,
    private val batchSequences: BatchSequenceRepository,
    private val stores: StoreRepository,
    private val accounts: AccountRepository,
    private val attachments: AttachmentRepository,
    private val activity: ActivityRecorder,
    private val tx: TransactionRunner,
) {
    companion object {
        private const val DEFAULT_PAYMENT_SCHEDULE_DAY = 5
        private val DATE_FORMATS = listOf(
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
        )
    }

    private class ProviderStat(val created: Boolean) {
        var accountsCreated: Int = 0
        var accountsReused: Int = 0
    }

    suspend operator fun invoke(fileBytes: ByteArray, actorId: String): BulkImportSummary = tx.inTransaction {
        // 1. Shared placeholder attachment for store installation proofs.
        val proof = attachments.create(
            purpose = AttachmentPurpose.INSTALLATION_PROOF,
            entityType = "store",
            entityId = null,
            storageKey = "bulk-import/placeholder-installation-proof",
            contentType = null,
            sizeBytes = null,
            uploadedBy = actorId,
        )

        // 2. Open the XLSX workbook
        val workbook = try {
            XSSFWorkbook(ByteArrayInputStream(fileBytes))
        } catch (e: Exception) {
            throw DomainError.Validation("file is not a valid XLSX: ${e.message}", "invalid_file")
        }

        workbook.use { workbook ->
            val sheet = workbook.getSheetAt(0)

            // 3. Build a header-name -> column-index map from row 0 (case-insensitive)
            val headerRow = sheet.getRow(0)
                ?: throw DomainError.Validation("spreadsheet has no header row", "invalid_file")
            val headerMap = mutableMapOf<String, Int>()
            for (col in 0 until headerRow.lastCellNum) {
                val name = cellString(headerRow.getCell(col))?.trim()?.lowercase()
                if (name != null) headerMap[name] = col
            }

            fun colOf(name: String): Int = headerMap[name.lowercase()]
                ?: throw DomainError.Validation("missing required column '$name' in header row", "invalid_file")

            val colStoreCode = colOf("Store Code")
            val colStoreName = colOf("Store Name")
            val colProvider = colOf("ISP/Provider")
            val colAccountNo = colOf("Account No")
            val colMra = colOf("Monthly Recurring Amount")
            val colServiceType = headerMap["service type"]
            val colCircuitId = headerMap["circuit id"]
            val colStartDate = headerMap["start date"]

            // 4. Process each data row
            var storesCreated = 0
            var storesReused = 0
            var accountsCreated = 0
            var accountsReused = 0
            var rowsSkipped = 0
            val skipReasons = mutableListOf<String>()
            var totalRows = 0

            val storeCache = mutableMapOf<String, String>() // branchCode -> storeId
            val providerCache = mutableMapOf<String, Provider>() // name -> Provider
            val providerStats = mutableMapOf<String, ProviderStat>() // name -> stats

            for (rowIdx in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIdx) ?: continue
                totalRows++

                val storeCode = cellString(row.getCell(colStoreCode))
                val storeName = cellString(row.getCell(colStoreName))
                val providerName = cellString(row.getCell(colProvider))
                val accountNo = cellString(row.getCell(colAccountNo))
                val serviceType = colServiceType?.let { cellString(row.getCell(it)) }
                val circuitId = colCircuitId?.let { cellString(row.getCell(it)) }
                val startDate = colStartDate?.let { cellString(row.getCell(it)) }
                val mraRaw = cellString(row.getCell(colMra))

                if (storeCode.isNullOrBlank()) {
                    rowsSkipped++
                    skipReasons.add("Row ${rowIdx + 1}: missing Store Code")
                    continue
                }
                if (providerName.isNullOrBlank()) {
                    rowsSkipped++
                    skipReasons.add("Row ${rowIdx + 1}: missing ISP/Provider")
                    continue
                }
                if (accountNo.isNullOrBlank()) {
                    rowsSkipped++
                    skipReasons.add("Row ${rowIdx + 1}: missing Account No")
                    continue
                }
                val rate = parseAmount(mraRaw)
                if (rate == null || !Money.isPositive(rate)) {
                    rowsSkipped++
                    skipReasons.add("Row ${rowIdx + 1}: invalid or zero Monthly Recurring Amount")
                    continue
                }

                // Find or create provider (cached by name)
                val provider = providerCache.getOrPut(providerName) {
                    val existing = providers.findByName(providerName)
                    if (existing != null) {
                        providerStats[providerName] = ProviderStat(created = false)
                        existing
                    } else {
                        val created = providers.create(providerName, DEFAULT_PAYMENT_SCHEDULE_DAY)
                        sequences.seed(created.id, InvoiceNumberFormatter.prefix(created.name))
                        batchSequences.seed(created.id)
                        providerStats[providerName] = ProviderStat(created = true)
                        created
                    }
                }

                // Find or create store (cached by branchCode)
                val storeId = storeCache.getOrPut(storeCode) {
                    val existing = stores.findByBranchCode(storeCode)
                    if (existing != null) {
                        storesReused++
                        existing.id
                    } else {
                        storesCreated++
                        stores.create(
                            StoreUpsertRequest(
                                storeType = StoreType.PUREGOLD,
                                branchCode = storeCode,
                                name = storeName ?: storeCode,
                                proofOfInstallationId = proof.id,
                            ),
                            createdBy = actorId,
                        ).id
                    }
                }

                // Find or create account
                if (accounts.existsByProviderAndNumber(provider.id, accountNo)) {
                    accountsReused++
                    providerStats[providerName]!!.accountsReused++
                } else {
                    val parsedStart = parseDate(startDate)
                    val installationDate = parsedStart ?: LocalDate(1970, 1, 1)
                    // Note whenever we fall back to the epoch date — the source date was
                    // either blank or present-but-unparseable. Gating on blankness alone
                    // left unparseable dates silently stamped 1970 with no explanation.
                    val notes = if (parsedStart == null) {
                        "Installation date unavailable from source (bulk import)"
                    } else {
                        null
                    }
                    val created = accounts.create(
                        AccountUpsertRequest(
                            accountNumber = accountNo,
                            circuitId = circuitId?.takeIf { it.isNotBlank() },
                            providerId = provider.id,
                            storeId = storeId,
                            serviceType = serviceType?.takeIf { it.isNotBlank() },
                            rate = rate,
                            installationDate = installationDate,
                            contractStartDate = parsedStart,
                            notes = notes,
                        ),
                        createdBy = actorId,
                    )
                    accountsCreated++
                    providerStats[providerName]!!.accountsCreated++
                    activity.record(actorId, "account.bulk_imported", "account", created.id)
                }
            }

            val providerSummaries = providerStats.entries
                .sortedBy { it.key }
                .map { (name, stat) ->
                    ProviderImportSummary(
                        name = name,
                        created = stat.created,
                        accountsCreated = stat.accountsCreated,
                        accountsReused = stat.accountsReused,
                    )
                }

            BulkImportSummary(
                providers = providerSummaries,
                storesCreated = storesCreated,
                storesReused = storesReused,
                accountsCreated = accountsCreated,
                accountsReused = accountsReused,
                rowsSkipped = rowsSkipped,
                skipReasons = skipReasons,
                totalRows = totalRows,
            )
        }
    }

    // -- Helpers --

    /**
     * Reads a cell's value as a trimmed string regardless of its underlying type.
     * Date-formatted cells are returned as ISO date strings; whole numbers lose
     * the trailing ".0" so account numbers and store codes stay clean.
     */
    private fun cellString(cell: Cell?): String? {
        if (cell == null) return null
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim().takeIf { it.isNotBlank() }
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    cell.localDateTimeCellValue?.toLocalDate()?.toString()
                } else {
                    val d = cell.numericCellValue
                    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
                }
            }
            CellType.FORMULA -> cell.stringCellValue?.trim()?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /**
     * Parses mixed-format dates: string dates ("11/20/2024", "Feb 8, 2022") and
     * Excel serial dates returned as numeric strings ("45572"). Returns null when
     * the input is blank or unparseable.
     */
    private fun parseDate(raw: String?): LocalDate? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        for (fmt in DATE_FORMATS) {
            val parsed = runCatching { java.time.LocalDate.parse(s, fmt) }.getOrNull()
            if (parsed != null) {
                return LocalDate(parsed.year, parsed.monthValue, parsed.dayOfMonth)
            }
        }
        s.toLongOrNull()?.let { serial ->
            return runCatching {
                val javaDate = java.time.LocalDate.of(1899, 12, 30).plusDays(serial)
                LocalDate(javaDate.year, javaDate.monthValue, javaDate.dayOfMonth)
            }.getOrNull()
        }
        return null
    }

    /**
     * Strips thousands separators and currency symbols so "2,798.00" becomes "2798.00".
     * Returns null when the cleaned value is not a valid number (e.g. "abc", "N/A",
     * "2798 pesos") so the caller skips that row — otherwise Money.parse would throw
     * NumberFormatException and abort the entire import.
     */
    private fun parseAmount(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleaned = s.replace(",", "").replace("₱", "").trim()
        return cleaned.takeIf { it.toBigDecimalOrNull() != null }
    }
}
