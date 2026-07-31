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
import java.math.BigDecimal
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
 * Name, ISP/Provider, Account No, Monthly Recurring Amount (required), plus optional
 * Circuit ID, Service Type and Start Date. Column order does not matter — headers
 * are matched by name (case-insensitive).
 *
 * The import is idempotent: re-running with the same file creates no duplicates.
 * Providers are read from the ISP/Provider column and matched by name — a single
 * file may contain rows for multiple providers (e.g. PLDT, Globe, Radius, Converge).
 * Stores are matched by branchCode, accounts by (storeId, providerId, accountNumber,
 * circuitId) — one account number may recur across stores and carry many circuits, and
 * each distinct identity becomes its own account. Circuit ID is OPTIONAL (empty circuit
 * accounts import fine; store scopes their identity). Rows missing Store Code, ISP/Provider,
 * Account No, or with a malformed/zero MRC are skipped and reported in `skipReasons`.
 *
 * The import is PARTIAL: valid rows are committed row-by-row in their own transactions,
 * so a single row that fails at the DB layer is reported in `failureReasons` without
 * rolling back the rows that already succeeded.
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

    /** A spreadsheet row that passed validation and is ready to persist. */
    private class ParsedRow(
        val rowNumber: Int, // 1-based, for user-facing messages
        val storeCode: String,
        val storeName: String?,
        val providerName: String,
        val accountNumber: String,
        val circuitId: String?, // null when blank — circuit is optional; store scopes identity
        val serviceType: String?,
        val startDate: String?, // null when blank; may still be unparseable
        val rate: String, // validated, positive, normalized (no separators/₱)
    )

    private class ParseResult(
        val validRows: List<ParsedRow>,
        val skipReasons: List<String>,
        val totalRows: Int,
    )

    /** Result of persisting one row, used to update caches/counters only on commit. */
    private class RowOutcome(
        val storeId: String,
        val storeWasCached: Boolean,
        val storeCreated: Boolean,
        val accountCreated: Boolean,
    )

    suspend operator fun invoke(fileBytes: ByteArray, actorId: String): BulkImportSummary {
        // ---- Phase 1: parse + validate (no DB, so a bad row can never roll back a commit) ----
        val parsed = parseWorkbook(fileBytes)

        var storesCreated = 0
        var storesReused = 0
        var accountsCreated = 0
        var accountsReused = 0
        var rowsFailed = 0
        val failureReasons = mutableListOf<String>()

        val storeCache = mutableMapOf<String, String>() // branchCode -> storeId (only committed stores)
        val providerCache = mutableMapOf<String, Provider>() // name -> committed Provider
        val providerStats = mutableMapOf<String, ProviderStat>() // name -> stats

        if (parsed.validRows.isEmpty()) {
            return summary(providerStats, storesCreated, storesReused, accountsCreated, accountsReused, parsed, rowsFailed, failureReasons)
        }

        // ---- Phase 2: shared placeholder attachment, committed up front (FK target for stores) ----
        val proofId = tx.inTransaction {
            attachments.create(
                purpose = AttachmentPurpose.INSTALLATION_PROOF,
                entityType = "store",
                entityId = null,
                storageKey = "bulk-import/placeholder-installation-proof",
                contentType = null,
                sizeBytes = null,
                uploadedBy = actorId,
            ).id
        }

        // Find or create a provider in its OWN committed transaction, cached by name. Committing
        // providers before the row that needs them keeps the account insert's FK valid even if a
        // later row rolls back, and keeps the cache from ever pointing at a rolled-back provider.
        suspend fun ensureProvider(name: String): Provider {
            providerCache[name]?.let { return it }
            val (provider, created) = tx.inTransaction {
                val existing = providers.findByName(name)
                if (existing != null) {
                    existing to false
                } else {
                    val fresh = providers.create(name, DEFAULT_PAYMENT_SCHEDULE_DAY)
                    sequences.seed(fresh.id, InvoiceNumberFormatter.prefix(fresh.name))
                    batchSequences.seed(fresh.id)
                    fresh to true
                }
            }
            providerCache[name] = provider
            providerStats[name] = ProviderStat(created = created)
            return provider
        }

        // ---- Phase 3: per-row DB work, one committed transaction per row (partial commit) ----
        for (row in parsed.validRows) {
            val provider = try {
                ensureProvider(row.providerName)
            } catch (e: Exception) {
                rowsFailed++
                failureReasons.add("Row ${row.rowNumber}: provider setup failed: ${e.message ?: e::class.simpleName}")
                continue
            }

            try {
                val outcome = tx.inTransaction {
                    // Resolve store: cache -> existing -> create. A newly created store lives in THIS
                    // row's transaction, so if the account insert below throws, the store rolls back too.
                    val cachedStoreId = storeCache[row.storeCode]
                    val storeId: String
                    val storeWasCached: Boolean
                    val storeCreated: Boolean
                    if (cachedStoreId != null) {
                        storeId = cachedStoreId
                        storeWasCached = true
                        storeCreated = false
                    } else {
                        val existing = stores.findByBranchCode(row.storeCode)
                        if (existing != null) {
                            storeId = existing.id
                            storeWasCached = false
                            storeCreated = false
                        } else {
                            storeId = stores.create(
                                StoreUpsertRequest(
                                    storeType = StoreType.PUREGOLD,
                                    branchCode = row.storeCode,
                                    name = row.storeName ?: row.storeCode,
                                    proofOfInstallationId = proofId,
                                ),
                                createdBy = actorId,
                            ).id
                            storeWasCached = false
                            storeCreated = true
                        }
                    }

                    val accountCreated: Boolean
                    if (accounts.existsByIdentity(storeId, provider.id, row.accountNumber, row.circuitId)) {
                        accountCreated = false
                    } else {
                        val installationDate = parseDate(row.startDate) ?: LocalDate(1970, 1, 1)
                        val notes = if (row.startDate == null) {
                            "Installation date unavailable from source (bulk import)"
                        } else {
                            null
                        }
                        val created = accounts.create(
                            AccountUpsertRequest(
                                accountNumber = row.accountNumber,
                                circuitId = row.circuitId,
                                providerId = provider.id,
                                storeId = storeId,
                                serviceType = row.serviceType,
                                rate = row.rate,
                                installationDate = installationDate,
                                contractStartDate = parseDate(row.startDate),
                                notes = notes,
                            ),
                            createdBy = actorId,
                        )
                        activity.record(actorId, "account.bulk_imported", "account", created.id)
                        accountCreated = true
                    }

                    RowOutcome(storeId, storeWasCached, storeCreated, accountCreated)
                }

                // Commit succeeded: now (and only now) mutate caches + counters from the outcome.
                if (!outcome.storeWasCached) {
                    storeCache[row.storeCode] = outcome.storeId
                    if (outcome.storeCreated) storesCreated++ else storesReused++
                }
                if (outcome.accountCreated) {
                    accountsCreated++
                    providerStats[row.providerName]!!.accountsCreated++
                } else {
                    accountsReused++
                    providerStats[row.providerName]!!.accountsReused++
                }
            } catch (e: Exception) {
                // The row's transaction rolled back atomically (store insert included), so caches are
                // untouched and a later row for the same branch code re-creates the store cleanly.
                rowsFailed++
                failureReasons.add("Row ${row.rowNumber}: ${e.message ?: e::class.simpleName}")
            }
        }

        return summary(providerStats, storesCreated, storesReused, accountsCreated, accountsReused, parsed, rowsFailed, failureReasons)
    }

    // -- Parsing --

    /**
     * Opens the workbook and validates every data row WITHOUT touching the DB. File-level problems
     * (not an XLSX, no header row, a missing required column) are fatal 400s; per-row problems become
     * skip reasons so one bad row never aborts the import.
     */
    private fun parseWorkbook(fileBytes: ByteArray): ParseResult {
        val workbook = try {
            XSSFWorkbook(ByteArrayInputStream(fileBytes))
        } catch (e: Exception) {
            throw DomainError.Validation("file is not a valid XLSX: ${e.message}", "invalid_file")
        }

        workbook.use { wb ->
            val sheet = wb.getSheetAt(0)
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
            val colCircuitId = headerMap["circuit id"]
            val colServiceType = headerMap["service type"]
            val colStartDate = headerMap["start date"]

            val validRows = mutableListOf<ParsedRow>()
            val skipReasons = mutableListOf<String>()
            var totalRows = 0

            for (rowIdx in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIdx) ?: continue
                totalRows++
                val rowNumber = rowIdx + 1

                val storeCode = cellString(row.getCell(colStoreCode))
                val storeName = cellString(row.getCell(colStoreName))
                val providerName = cellString(row.getCell(colProvider))
                val accountNo = cellString(row.getCell(colAccountNo))
                val circuitId = colCircuitId?.let { cellString(row.getCell(it)) }
                val serviceType = colServiceType?.let { cellString(row.getCell(it)) }
                val startDate = colStartDate?.let { cellString(row.getCell(it)) }
                val mraRaw = cellString(row.getCell(colMra))

                if (storeCode.isNullOrBlank()) {
                    skipReasons.add("Row $rowNumber: missing Store Code"); continue
                }
                if (providerName.isNullOrBlank()) {
                    skipReasons.add("Row $rowNumber: missing ISP/Provider"); continue
                }
                if (accountNo.isNullOrBlank()) {
                    skipReasons.add("Row $rowNumber: missing Account No"); continue
                }
                val rate = parsePositiveAmount(mraRaw)
                if (rate == null) {
                    skipReasons.add("Row $rowNumber: invalid or zero Monthly Recurring Amount"); continue
                }

                validRows.add(
                    ParsedRow(
                        rowNumber = rowNumber,
                        storeCode = storeCode,
                        storeName = storeName,
                        providerName = providerName,
                        accountNumber = accountNo,
                        circuitId = circuitId?.takeIf { it.isNotBlank() },
                        serviceType = serviceType?.takeIf { it.isNotBlank() },
                        startDate = startDate?.takeIf { it.isNotBlank() },
                        rate = rate,
                    ),
                )
            }

            return ParseResult(validRows, skipReasons, totalRows)
        }
    }

    private fun summary(
        providerStats: Map<String, ProviderStat>,
        storesCreated: Int,
        storesReused: Int,
        accountsCreated: Int,
        accountsReused: Int,
        parsed: ParseResult,
        rowsFailed: Int,
        failureReasons: List<String>,
    ): BulkImportSummary {
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
        return BulkImportSummary(
            providers = providerSummaries,
            storesCreated = storesCreated,
            storesReused = storesReused,
            accountsCreated = accountsCreated,
            accountsReused = accountsReused,
            rowsSkipped = parsed.skipReasons.size,
            skipReasons = parsed.skipReasons,
            totalRows = parsed.totalRows,
            rowsFailed = rowsFailed,
            failureReasons = failureReasons,
        )
    }

    // -- Cell helpers --

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
     * Strips thousands separators and currency symbols ("2,798.00" -> "2798.00") and returns the
     * normalized string only when it parses to a positive amount. Returns null for blank, non-numeric
     * (e.g. "N/A"), or non-positive input — so a malformed amount cell is a skipped row, never a crash.
     */
    private fun parsePositiveAmount(raw: String?): String? {
        val s = raw?.trim()?.replace(",", "")?.replace("₱", "")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        // parseOrNull, not isPositive: an unparseable cell here is a row to skip, whereas
        // Money.parse raises a DomainError.Validation that would fail the whole import.
        val parsed = Money.parseOrNull(s) ?: return null
        return if (parsed > BigDecimal.ZERO) s else null
    }
}
