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
import com.puregoldbe.ibms.domain.service.NameNormalizer
import com.puregoldbe.ibms.domain.valueobject.Money
import java.math.BigDecimal
import kotlinx.datetime.LocalDate
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which component of a `dd/mm/yyyy`-shaped date comes first. See [BulkImportAccountsUseCase]. */
enum class DateOrder { MDY, DMY }

/**
 * Bulk-imports stores, providers, and accounts from an XLSX spreadsheet.
 *
 * The spreadsheet must have a header row with column names: Store Code, Store
 * Name, ISP/Provider, Account No, Monthly Recurring Amount (required), plus optional
 * Circuit ID, Service Type and Start Date. Column order does not matter — headers
 * are matched by name (case-insensitive).
 *
 * The import is idempotent: re-running with the same file creates no duplicates.
 * Providers are read from the ISP/Provider column and matched by name, CASE-INSENSITIVELY
 * — a single file may contain rows for multiple providers (e.g. PLDT, Globe, Radius,
 * Converge), and "Converge"/"CONVERGE" are one provider, not two (see [NameNormalizer]).
 * Stores are matched by branchCode, accounts by (storeId, providerId, accountNumber,
 * circuitId) — one account number may recur across stores and carry many circuits, and
 * each distinct identity becomes its own account. Circuit ID is OPTIONAL (empty circuit
 * accounts import fine; store scopes their identity). Rows missing Store Code, ISP/Provider,
 * Account No, or with a malformed/zero MRC are skipped and reported in `skipReasons`.
 *
 * Re-importing a row whose Monthly Recurring Amount has changed REFRESHES the stored rate
 * (counted in `accountsUpdated`); a row that matches with an unchanged rate is counted in
 * `accountsReused`. Nothing else about a matched account is touched.
 *
 * The import is PARTIAL: valid rows are committed row-by-row in their own transactions,
 * so a single row that fails at the DB layer is reported in `failureReasons` without
 * rolling back the rows that already succeeded.
 *
 * Rows that import successfully but whose source data cannot be trusted — an ambiguous
 * slash-date, or an identity column Excel already rounded — are reported in `warnings`.
 * [dateOrder] selects how `05/06/2025` is read; the opposite order is still tried as a
 * fallback, with a warning, rather than silently falling back to the epoch sentinel.
 *
 * A single shared placeholder attachment (purpose = installation_proof) is
 * resolved-or-created to satisfy the NOT NULL FK on stores.proof_of_installation_id,
 * since bulk-imported stores do not carry individual proof documents. It is looked up by
 * its fixed storage key, so repeated imports reuse one row rather than leaking one per run.
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

        /** Fixed key for the shared placeholder proof; the lookup that makes imports not leak. */
        const val PLACEHOLDER_STORAGE_KEY = "bulk-import/placeholder-installation-proof"

        /** Advisory warnings are capped so a pathological sheet cannot balloon the response. */
        private const val MAX_WARNINGS = 200

        /** Excel stores at most 15 significant digits in a numeric cell. */
        private const val EXCEL_SIGNIFICANT_DIGITS = 15

        private fun fmt(pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.US)

        // Unambiguous formats, tried first. ISO is what cellString emits for a genuine
        // date-formatted cell, so real dates never reach the slash heuristics below.
        private val UNAMBIGUOUS_FORMATS = listOf(fmt("yyyy-MM-dd"), fmt("MMM d, yyyy"))
        private val MDY_FORMATS = listOf(fmt("M/d/yyyy"), fmt("MM/dd/yyyy"))
        private val DMY_FORMATS = listOf(fmt("d/M/yyyy"), fmt("dd/MM/yyyy"))

        private val SLASH_DATE = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""")
    }

    private class ProviderStat(val displayName: String, val created: Boolean) {
        var accountsCreated: Int = 0
        var accountsReused: Int = 0
        var accountsUpdated: Int = 0
    }

    /** A spreadsheet row that passed validation and is ready to persist. */
    private class ParsedRow(
        val rowNumber: Int, // 1-based, for user-facing messages
        val storeCode: String, // normalized (trimmed, collapsed, uppercased)
        val storeName: String?,
        val providerName: String, // normalized display form
        val providerKey: String, // case-insensitive match key
        val accountNumber: String,
        val circuitId: String?, // null when blank — circuit is optional; store scopes identity
        val serviceType: String?,
        val startDate: String?, // null when blank; may still be unparseable
        val rate: String, // validated, positive, normalized (no separators/₱)
    )

    private class ParseResult(
        val validRows: List<ParsedRow>,
        val skipReasons: List<String>,
        val warnings: List<String>,
        val totalRows: Int,
    )

    /** What happened to the account on a row that committed. */
    private enum class AccountOutcome { CREATED, REUSED, UPDATED }

    /** Result of persisting one row, used to update caches/counters only on commit. */
    private class RowOutcome(
        val storeId: String,
        val storeWasCached: Boolean,
        val storeCreated: Boolean,
        val account: AccountOutcome,
    )

    suspend operator fun invoke(
        fileBytes: ByteArray,
        actorId: String,
        dateOrder: DateOrder = DateOrder.MDY,
    ): BulkImportSummary {
        // ---- Phase 1: parse + validate (no DB, so a bad row can never roll back a commit) ----
        val parsed = parseWorkbook(fileBytes, dateOrder)

        var storesCreated = 0
        var storesReused = 0
        var accountsCreated = 0
        var accountsReused = 0
        var accountsUpdated = 0
        var rowsFailed = 0
        val failureReasons = mutableListOf<String>()

        val storeCache = mutableMapOf<String, String>() // branchCode -> storeId (only committed stores)
        // Keyed case-insensitively: a sheet mixing "Converge" and "CONVERGE" must resolve to
        // ONE provider and report ONE summary entry.
        val providerCache = mutableMapOf<String, Provider>() // matchKey -> committed Provider
        val providerStats = mutableMapOf<String, ProviderStat>() // matchKey -> stats

        if (parsed.validRows.isEmpty()) {
            return summary(providerStats, storesCreated, storesReused, accountsCreated, accountsReused, accountsUpdated, parsed, rowsFailed, failureReasons)
        }

        // ---- Phase 2: shared placeholder attachment, committed up front (FK target for stores) ----
        // Resolve before creating: the storage key is a constant, so every run would otherwise
        // insert another row naming the same (never-written) blob, orphaned whenever the run
        // creates no store.
        val proofId = tx.inTransaction {
            attachments.findByStorageKey(PLACEHOLDER_STORAGE_KEY)?.id
                ?: attachments.create(
                    purpose = AttachmentPurpose.INSTALLATION_PROOF,
                    entityType = "store",
                    entityId = null,
                    storageKey = PLACEHOLDER_STORAGE_KEY,
                    contentType = null,
                    sizeBytes = null,
                    uploadedBy = actorId,
                ).id
        }

        // Find or create a provider in its OWN committed transaction, cached by match key.
        // Committing providers before the row that needs them keeps the account insert's FK
        // valid even if a later row rolls back, and keeps the cache from ever pointing at a
        // rolled-back provider.
        suspend fun ensureProvider(row: ParsedRow): Provider {
            providerCache[row.providerKey]?.let { return it }
            val (provider, created) = tx.inTransaction {
                val existing = providers.findByName(row.providerName)
                if (existing != null) {
                    // Self-heal: a provider created outside CreateProviderUseCase can be missing
                    // its sequence rows, and ExposedInvoiceSequenceRepository.nextValue does NOT
                    // recover from that the way the batch one does — it throws at compile time.
                    // Both seeds are insertIgnore, so this is a free no-op when they exist.
                    sequences.seed(existing.id, InvoiceNumberFormatter.prefix(existing.name))
                    batchSequences.seed(existing.id)
                    existing to false
                } else {
                    val fresh = providers.create(row.providerName, DEFAULT_PAYMENT_SCHEDULE_DAY)
                    sequences.seed(fresh.id, InvoiceNumberFormatter.prefix(fresh.name))
                    batchSequences.seed(fresh.id)
                    fresh to true
                }
            }
            providerCache[row.providerKey] = provider
            // Report the PERSISTED name, not the cell text, so mixed spellings collapse to the
            // provider's real stored name in the summary.
            providerStats[row.providerKey] = ProviderStat(provider.name, created)
            return provider
        }

        // ---- Phase 3: per-row DB work, one committed transaction per row (partial commit) ----
        for (row in parsed.validRows) {
            val provider = try {
                ensureProvider(row)
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

                    val existingAccount = accounts.findByIdentity(storeId, provider.id, row.accountNumber, row.circuitId)
                    val accountOutcome = if (existingAccount != null) {
                        // Matched. Refresh the rate if the sheet disagrees — the import is the
                        // correction path for MRC changes. Compare as BigDecimal so "2798" and
                        // "2798.00" are the same rate and no pointless write happens.
                        val stored = Money.parse(existingAccount.rate)
                        val fromSheet = Money.parse(row.rate)
                        if (stored.compareTo(fromSheet) != 0) {
                            accounts.updateRate(existingAccount.id, row.rate)
                            activity.record(
                                actorId,
                                "account.bulk_import_rate_updated",
                                "account",
                                existingAccount.id,
                                "rate ${Money.format(stored)} -> ${Money.format(fromSheet)}",
                            )
                            AccountOutcome.UPDATED
                        } else {
                            AccountOutcome.REUSED
                        }
                    } else {
                        // Note whenever we fall back to the epoch sentinel — the source date was
                        // either blank or present-but-unparseable. Gating on blankness alone left
                        // an unparseable date silently stamped 1970 with nothing to explain it.
                        val parsedStart = parseDate(row.startDate, dateOrder).date
                        val installationDate = parsedStart ?: LocalDate(1970, 1, 1)
                        val notes = if (parsedStart == null) {
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
                                contractStartDate = parsedStart,
                                notes = notes,
                            ),
                            createdBy = actorId,
                        )
                        activity.record(actorId, "account.bulk_imported", "account", created.id)
                        AccountOutcome.CREATED
                    }

                    RowOutcome(storeId, storeWasCached, storeCreated, accountOutcome)
                }

                // Commit succeeded: now (and only now) mutate caches + counters from the outcome.
                if (!outcome.storeWasCached) {
                    storeCache[row.storeCode] = outcome.storeId
                    if (outcome.storeCreated) storesCreated++ else storesReused++
                }
                val stat = providerStats[row.providerKey]!!
                when (outcome.account) {
                    AccountOutcome.CREATED -> { accountsCreated++; stat.accountsCreated++ }
                    AccountOutcome.REUSED -> { accountsReused++; stat.accountsReused++ }
                    AccountOutcome.UPDATED -> { accountsUpdated++; stat.accountsUpdated++ }
                }
            } catch (e: Exception) {
                // The row's transaction rolled back atomically (store insert included), so caches are
                // untouched and a later row for the same branch code re-creates the store cleanly.
                rowsFailed++
                failureReasons.add("Row ${row.rowNumber}: ${e.message ?: e::class.simpleName}")
            }
        }

        return summary(providerStats, storesCreated, storesReused, accountsCreated, accountsReused, accountsUpdated, parsed, rowsFailed, failureReasons)
    }

    // -- Parsing --

    /**
     * Opens the workbook and validates every data row WITHOUT touching the DB. File-level problems
     * (not an XLSX, no header row, a missing required column) are fatal 400s; per-row problems become
     * skip reasons so one bad row never aborts the import.
     */
    private fun parseWorkbook(fileBytes: ByteArray, dateOrder: DateOrder): ParseResult {
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
            val warnings = mutableListOf<String>()
            var totalRows = 0

            for (rowIdx in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIdx) ?: continue
                totalRows++
                val rowNumber = rowIdx + 1

                // A cell POI cannot read must cost one row, not the whole import. Before this
                // guard a formula cell threw IllegalStateException straight out of the use case
                // and StatusPages answered 500 with no indication of which row was at fault.
                try {
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

                    // Excel itself keeps only 15 significant digits in a numeric cell, so a long
                    // account number typed as a number is already rounded in the file. We cannot
                    // recover it — but importing a wrong identity silently is worse than saying so.
                    if (isLossyNumeric(row.getCell(colAccountNo))) {
                        warnings.add(
                            "Row $rowNumber: Account No '$accountNo' came from a numeric cell with more than " +
                                "$EXCEL_SIGNIFICANT_DIGITS digits — Excel may already have rounded it. " +
                                "Format that column as Text and re-import to be certain.",
                        )
                    }
                    if (colCircuitId != null && isLossyNumeric(row.getCell(colCircuitId))) {
                        warnings.add(
                            "Row $rowNumber: Circuit ID '$circuitId' came from a numeric cell with more than " +
                                "$EXCEL_SIGNIFICANT_DIGITS digits — Excel may already have rounded it. " +
                                "Format that column as Text and re-import to be certain.",
                        )
                    }
                    parseDate(startDate, dateOrder).warning?.let { warnings.add("Row $rowNumber: $it") }

                    validRows.add(
                        ParsedRow(
                            rowNumber = rowNumber,
                            storeCode = NameNormalizer.branchCode(storeCode),
                            storeName = storeName,
                            providerName = NameNormalizer.displayName(providerName),
                            providerKey = NameNormalizer.matchKey(providerName),
                            accountNumber = accountNo,
                            circuitId = circuitId?.takeIf { it.isNotBlank() },
                            serviceType = serviceType?.takeIf { it.isNotBlank() },
                            startDate = startDate?.takeIf { it.isNotBlank() },
                            rate = rate,
                        ),
                    )
                } catch (e: Exception) {
                    skipReasons.add("Row $rowNumber: could not read row (${e::class.simpleName})")
                }
            }

            return ParseResult(validRows, skipReasons, capWarnings(warnings), totalRows)
        }
    }

    private fun capWarnings(warnings: List<String>): List<String> =
        if (warnings.size <= MAX_WARNINGS) {
            warnings
        } else {
            warnings.take(MAX_WARNINGS) + "…and ${warnings.size - MAX_WARNINGS} more warnings not shown."
        }

    private fun summary(
        providerStats: Map<String, ProviderStat>,
        storesCreated: Int,
        storesReused: Int,
        accountsCreated: Int,
        accountsReused: Int,
        accountsUpdated: Int,
        parsed: ParseResult,
        rowsFailed: Int,
        failureReasons: List<String>,
    ): BulkImportSummary {
        val providerSummaries = providerStats.values
            .sortedBy { it.displayName }
            .map { stat ->
                ProviderImportSummary(
                    name = stat.displayName,
                    created = stat.created,
                    accountsCreated = stat.accountsCreated,
                    accountsReused = stat.accountsReused,
                    accountsUpdated = stat.accountsUpdated,
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
            accountsUpdated = accountsUpdated,
            warnings = parsed.warnings,
        )
    }

    // -- Cell helpers --

    /** The type a cell's value actually has — for a formula, its cached result type. */
    private fun effectiveType(cell: Cell): CellType =
        if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType

    /**
     * Reads a cell's value as a trimmed string regardless of its underlying type.
     * Date-formatted cells are returned as ISO date strings; whole numbers lose
     * the trailing ".0" so account numbers and store codes stay clean.
     *
     * Formulas are resolved through their CACHED RESULT type: calling
     * `stringCellValue` on a formula whose result is numeric (an ordinary `=B2*12`
     * in the amount column) throws IllegalStateException.
     */
    private fun cellString(cell: Cell?): String? {
        if (cell == null) return null
        return when (effectiveType(cell)) {
            CellType.STRING -> cell.stringCellValue.trim().takeIf { it.isNotBlank() }
            CellType.NUMERIC -> numericCellString(cell)
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            else -> null
        }
    }

    /**
     * Numeric cell -> string, preferring the RAW stored value over `numericCellValue`.
     *
     * `numericCellValue` is a Double: an account number past 15 digits comes back rounded
     * (`…678` -> `…680`), and a value large enough to break the `toLong()` round-trip used
     * to render as the literal "1.0E20". The raw `<v>` text has no such round-trip.
     */
    private fun numericCellString(cell: Cell): String? {
        if (DateUtil.isCellDateFormatted(cell)) {
            return cell.localDateTimeCellValue?.toLocalDate()?.toString()
        }
        rawNumeric(cell)?.let { return it.stripTrailingZeros().toPlainString() }
        val d = cell.numericCellValue
        return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }

    /** The cell's stored numeric text as an exact BigDecimal, or null if unavailable. */
    private fun rawNumeric(cell: Cell): BigDecimal? {
        val raw = (cell as? XSSFCell)?.rawValue?.trim()
        if (raw.isNullOrBlank()) return null
        return runCatching { BigDecimal(raw) }.getOrNull()
    }

    /** True when the cell holds a number with more significant digits than Excel can store exactly. */
    private fun isLossyNumeric(cell: Cell?): Boolean {
        if (cell == null || effectiveType(cell) != CellType.NUMERIC) return false
        if (runCatching { DateUtil.isCellDateFormatted(cell) }.getOrDefault(false)) return false
        val raw = rawNumeric(cell) ?: return false
        return raw.stripTrailingZeros().precision() > EXCEL_SIGNIFICANT_DIGITS
    }

    /** A parsed date plus, when the reading is not certain, the reason to tell the operator. */
    private class DateParse(val date: LocalDate?, val warning: String?)

    /**
     * Parses mixed-format dates: string dates ("11/20/2024", "Feb 8, 2022"), ISO dates (what
     * a genuine date-formatted cell yields), and Excel serial dates returned as numeric
     * strings ("45572"). Returns a null date when the input is blank or unparseable.
     *
     * Slash dates are genuinely ambiguous: `05/06/2025` is May 6 under [DateOrder.MDY] and
     * June 5 under [DateOrder.DMY], and both parse cleanly, so neither can be detected as
     * wrong. Since `contractStartDate` anchors TopSheet proration, a misread date becomes a
     * wrong charge — so an ambiguous one is reported rather than trusted silently. A date
     * that only parses under the OTHER order is still accepted (better than the epoch
     * sentinel) and always warns.
     */
    private fun parseDate(raw: String?, order: DateOrder): DateParse {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return DateParse(null, null)

        for (fmt in UNAMBIGUOUS_FORMATS) {
            tryFormat(s, fmt)?.let { return DateParse(it, null) }
        }

        val (preferred, fallback) = when (order) {
            DateOrder.MDY -> MDY_FORMATS to DMY_FORMATS
            DateOrder.DMY -> DMY_FORMATS to MDY_FORMATS
        }

        for (fmt in preferred) {
            tryFormat(s, fmt)?.let { date ->
                return DateParse(date, ambiguityWarning(s, date, order))
            }
        }
        for (fmt in fallback) {
            tryFormat(s, fmt)?.let { date ->
                val other = if (order == DateOrder.MDY) DateOrder.DMY else DateOrder.MDY
                return DateParse(
                    date,
                    "start date '$s' is not a valid ${order.name} date; read as $date using " +
                        "${other.name} order instead. Re-import with dateOrder=${other.name.lowercase()} if the sheet is ${other.name}.",
                )
            }
        }

        s.toLongOrNull()?.let { serial ->
            val date = runCatching {
                val javaDate = java.time.LocalDate.of(1899, 12, 30).plusDays(serial)
                LocalDate(javaDate.year, javaDate.monthValue, javaDate.dayOfMonth)
            }.getOrNull()
            if (date != null) return DateParse(date, null)
        }
        return DateParse(null, null)
    }

    private fun tryFormat(s: String, fmt: DateTimeFormatter): LocalDate? =
        runCatching { java.time.LocalDate.parse(s, fmt) }
            .getOrNull()
            ?.let { LocalDate(it.year, it.monthValue, it.dayOfMonth) }

    /**
     * A warning when the same text would yield a DIFFERENT date under the opposite order —
     * i.e. both leading components are 1..12 and differ. `5/5/2025` is the same day either
     * way and never warns.
     */
    private fun ambiguityWarning(s: String, chosen: LocalDate, order: DateOrder): String? {
        val m = SLASH_DATE.matchEntire(s) ?: return null
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        if (a == b || a !in 1..12 || b !in 1..12) return null
        val other = if (order == DateOrder.MDY) DateOrder.DMY else DateOrder.MDY
        val year = m.groupValues[3].toInt()
        // Both components are 1..12, so swapping them is always a valid date.
        val alternative = if (order == DateOrder.MDY) LocalDate(year, b, a) else LocalDate(year, a, b)
        return "start date '$s' is ambiguous — read as $chosen under ${order.name} order, " +
            "but it would be $alternative under ${other.name}. Verify, or re-import with " +
            "dateOrder=${other.name.lowercase()}."
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
