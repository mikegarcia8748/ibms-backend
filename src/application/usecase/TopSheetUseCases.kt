package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.CompilableLine
import com.puregoldbe.ibms.domain.model.CompilablePreview
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.NotYetSubscribedLine
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.BatchSequenceRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.domain.port.IdempotencyKeyRepository
import com.puregoldbe.ibms.domain.port.InvoiceSequenceRepository
import com.puregoldbe.ibms.domain.port.NewTopSheetLine
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.service.InvoiceNumberFormatter
import com.puregoldbe.ibms.domain.service.ProrationEngine
import com.puregoldbe.ibms.domain.valueobject.BillingPeriod
import com.puregoldbe.ibms.domain.valueobject.toMoney
import com.puregoldbe.ibms.domain.valueobject.toMoneyString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import java.math.BigInteger

/** Eligible account + its store + computed proration, shared by preview and compile. */
internal data class EligibleLine(
    val account: Account,
    val store: Store?,
    val proratedAmount: String,
    val isProrated: Boolean,
    /** Lumped recovery of un-billed prior periods; "0.00" when none. */
    val arrearsAmount: String = "0.00",
    /** The "YYYY-MM" periods folded into [arrearsAmount], for review/audit. */
    val arrearsPeriods: List<String> = emptyList(),
) {
    val isArrears: Boolean get() = arrearsPeriods.isNotEmpty()
}

/** The three review buckets a Secretary sees before compiling. */
internal data class Classified(
    /** Bill-now lines (may be flagged [EligibleLine.isArrears]). */
    val billable: List<EligibleLine>,
    /** Accounts whose subscription starts after the selected period (validation warning). */
    val notYetSubscribed: List<Account>,
)

/**
 * Legacy current-period-only projection (no arrears). Retained for the deprecated
 * [CompileTopSheetUseCase] so its behavior is unchanged.
 */
internal fun computeEligible(
    providerId: String,
    billingPeriod: String,
    accounts: List<Account>,
    storesById: Map<String, Store>,
    alreadyBilled: Set<String>,
): List<EligibleLine> = accounts
    .filter { ProrationEngine.isEligible(it, providerId, billingPeriod, alreadyBilled) }
    .map { acc ->
        EligibleLine(
            account = acc,
            store = storesById[acc.storeId],
            proratedAmount = ProrationEngine.proratedAmount(acc, billingPeriod),
            isProrated = ProrationEngine.isFirstBillProrated(acc, billingPeriod),
        )
    }

/**
 * Two-phase classification: split a provider's accounts into bill-now (with any
 * arrears folded in and flagged) and not-yet-subscribed (surfaced as a validation
 * warning rather than silently dropped). Accounts excluded for other reasons —
 * terminated past grace, transferred, wrong provider, already billed this period —
 * fall out of both buckets exactly as before.
 *
 * [billedThisPeriod] is the current-period double-billing guard; [settledByAccount]
 * is each account's full history of billed + arrears-recovered periods, used to
 * decide which prior partials are still owed.
 */
internal fun classify(
    providerId: String,
    billingPeriod: String,
    accounts: List<Account>,
    storesById: Map<String, Store>,
    billedThisPeriod: Set<String>,
    settledByAccount: Map<String, Set<String>>,
): Classified {
    val billable = mutableListOf<EligibleLine>()
    val notYetSubscribed = mutableListOf<Account>()
    for (acc in accounts) {
        if (ProrationEngine.isEligible(acc, providerId, billingPeriod, billedThisPeriod)) {
            val settled = settledByAccount[acc.id] ?: emptySet()
            val arrearsPeriods = ProrationEngine.missedPeriods(acc, billingPeriod, settled)
            billable += EligibleLine(
                account = acc,
                store = storesById[acc.storeId],
                proratedAmount = ProrationEngine.proratedAmount(acc, billingPeriod),
                isProrated = ProrationEngine.isFirstBillProrated(acc, billingPeriod),
                arrearsAmount = ProrationEngine.arrearsAmount(acc, billingPeriod, settled),
                arrearsPeriods = arrearsPeriods,
            )
        } else if (ProrationEngine.isNotYetSubscribed(acc, billingPeriod)) {
            notYetSubscribed += acc
        }
    }
    return Classified(billable, notYetSubscribed)
}

private fun requirePeriod(billingPeriod: String) {
    if (!BillingPeriod.isValid(billingPeriod)) {
        throw DomainError.Validation("billingPeriod must be YYYY-MM")
    }
}

private fun requireNotFuturePeriod(billingPeriod: String, clock: Clock) {
    requirePeriod(billingPeriod)
    val now = clock.now()
    // Use Asia/Manila timezone for business day boundary
    val local = now.toLocalDateTime(TimeZone.of("Asia/Manila"))
    val currentPeriod = "${local.year}-${local.monthNumber.toString().padStart(2, '0')}"
    if (billingPeriod > currentPeriod) {
        throw DomainError.Validation("Cannot select a future billing period")
    }
}

private fun List<EligibleLine>.total(): String =
    fold(BigDecimal.ZERO) { acc, line ->
        acc + line.proratedAmount.toMoney() + line.arrearsAmount.toMoney()
    }.toMoneyString()

/** Pure read: the eligible lines + prorated amounts a Secretary reviews before compiling. */
class PreviewCompilationUseCase(
    private val accounts: AccountRepository,
    private val stores: StoreRepository,
    private val topsheets: TopSheetRepository,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(providerId: String, billingPeriod: String): CompilablePreview = tx.inTransaction {
        requireNotFuturePeriod(billingPeriod, clock)
        val billed = topsheets.billedAccountIds(billingPeriod)
        val settled = topsheets.billedPeriodsByAccount(providerId)
        val storesById = stores.list(null, null).associateBy { it.id }
        val classified = classify(
            providerId, billingPeriod, accounts.list(null, providerId, null), storesById, billed, settled,
        )
        CompilablePreview(
            providerId = providerId,
            billingPeriod = billingPeriod,
            lines = classified.billable.map { it.toCompilableLine() },
            arrears = classified.billable.filter { it.isArrears }.map { it.toCompilableLine() },
            notYetSubscribed = classified.notYetSubscribed.map { acc ->
                NotYetSubscribedLine(
                    accountId = acc.id,
                    accountNumber = acc.accountNumber,
                    storeName = storesById[acc.storeId]?.name,
                    subscriptionStart = ProrationEngine.subscriptionStart(acc).toString(),
                    billingPeriod = billingPeriod,
                )
            },
            totalAmount = classified.billable.total(),
        )
    }
}

private fun EligibleLine.toCompilableLine(): CompilableLine = CompilableLine(
    accountId = account.id,
    accountNumber = account.accountNumber,
    branchCode = store?.branchCode,
    storeName = store?.name,
    circuitId = account.circuitId,
    fullAmount = account.rate,
    proratedAmount = proratedAmount,
    isProrated = isProrated,
    isArrears = isArrears,
    arrearsAmount = arrearsAmount,
    arrearsPeriods = arrearsPeriods,
    storeId = account.storeId,
)

/**
 * @deprecated Use [CreateDraftTopSheetUseCase] + [ConfirmTopSheetUseCase] instead. Kept for backward compatibility.
 *
 * Atomically compile a TopSheet: bump the provider's invoice sequence (row-locked),
 * mint `<ACRONYM>-YYYYMM-XXXX`, and insert the header + all lines. Re-compiling a
 * period is blocked because already-billed accounts are excluded (the double-billing
 * guard is also enforced by the uq_account_per_period index).
 */
class CompileTopSheetUseCase(
    private val accounts: AccountRepository,
    private val stores: StoreRepository,
    private val providers: ProviderRepository,
    private val topsheets: TopSheetRepository,
    private val sequences: InvoiceSequenceRepository,
    private val idempotency: IdempotencyKeyRepository,
    private val activity: ActivityRecorder,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        providerId: String,
        billingPeriod: String,
        compilerId: String,
        idem: IdempotencyContext? = null,
    ): TopSheet = tx.inTransaction {
        idempotent(idempotency, "topsheet.compile", idem, 201) {
            requirePeriod(billingPeriod)
            val provider = providers.findById(providerId)
                ?: throw DomainError.NotFound("provider $providerId not found")
            val billed = topsheets.billedAccountIds(billingPeriod)
            val storesById = stores.list(null, null).associateBy { it.id }
            val lines = computeEligible(providerId, billingPeriod, accounts.list(null, providerId, null), storesById, billed)
            if (lines.isEmpty()) {
                throw DomainError.Conflict(
                    "no eligible accounts to compile for provider $providerId / $billingPeriod",
                    "nothing_to_compile",
                )
            }
            val sequence = sequences.nextValue(providerId)
            val prefix = sequences.prefixOf(providerId) ?: InvoiceNumberFormatter.prefix(provider.name)
            val invoiceNumber = InvoiceNumberFormatter.format(prefix, billingPeriod, sequence)

            val topsheet = topsheets.create(
                invoiceNumber = invoiceNumber,
                billingPeriod = billingPeriod,
                providerId = providerId,
                providerName = provider.name,
                accountCount = lines.size,
                totalAmount = lines.total(),
                compilerId = compilerId,
            )
            lines.forEach { e ->
                topsheets.addLine(
                    topsheet.id,
                    NewTopSheetLine(
                        accountId = e.account.id,
                        billingPeriod = billingPeriod,
                        proratedAmount = e.proratedAmount,
                        fullAmount = e.account.rate,
                        branchCode = e.store?.branchCode,
                        storeName = e.store?.name,
                        circuitId = e.account.circuitId,
                        accountNumber = e.account.accountNumber,
                        accountStatus = e.account.status.name.lowercase(),
                    ),
                )
            }
            activity.record(compilerId, "topsheet.compiled", "topsheet", topsheet.id)
            topsheet
        }
    }
}

class ListTopSheetsUseCase(
    private val topsheets: TopSheetRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        providerId: String?,
        billingPeriod: String?,
        status: TopSheetStatus?,
        cursor: String?,
        limit: Int,
    ): CursorPage<TopSheet> =
        tx.inTransaction { topsheets.page(providerId, billingPeriod, status, cursor, limit) }
}

class GetTopSheetUseCase(
    private val topsheets: TopSheetRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String): TopSheet =
        tx.inTransaction { topsheets.findById(id) } ?: throw DomainError.NotFound("topsheet $id not found")
}

class GetTopSheetDetailsUseCase(
    private val topsheets: TopSheetRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String): List<TopSheetDetail> = tx.inTransaction { topsheets.findLines(id) }
}

/** Finance sign-off: compiled -> approved. */
class ApproveTopSheetUseCase(
    private val topsheets: TopSheetRepository,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String, approverId: String): TopSheet = tx.inTransaction {
        val ts = topsheets.findById(id) ?: throw DomainError.NotFound("topsheet $id not found")
        if (ts.status != TopSheetStatus.COMPILED) {
            throw DomainError.Conflict("only compiled topsheets can be approved (was ${ts.status.name.lowercase()})")
        }
        val approved = topsheets.approve(id, approverId, clock.now())
            ?: throw DomainError.NotFound("topsheet $id not found")
        activity.record(approverId, "topsheet.approved", "topsheet", id)
        approved
    }
}

/** Finance payment: approved -> paid, cascading line items to paid. */
class PayTopSheetUseCase(
    private val topsheets: TopSheetRepository,
    private val idempotency: IdempotencyKeyRepository,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String, idem: IdempotencyContext? = null): TopSheet = tx.inTransaction {
        idempotent(idempotency, "topsheet.pay", idem, 200) {
            val ts = topsheets.findById(id) ?: throw DomainError.NotFound("topsheet $id not found")
            if (ts.status != TopSheetStatus.APPROVED) {
                throw DomainError.Conflict("only approved topsheets can be paid (was ${ts.status.name.lowercase()})")
            }
            topsheets.pay(id, clock.now()) ?: throw DomainError.NotFound("topsheet $id not found")
        }
    }
}

// =====================================================================
//  Two-phase compilation: DRAFT -> (edit lines) -> CONFIRM
// =====================================================================

/**
 * Phase 1: create a DRAFT topsheet with eligible accounts for a provider/period.
 * Lines are pre-sorted by store branch code (descending) and assigned an
 * rfpSortOrder. RFP numbers are filled in later via [UpdateDraftLineUseCase].
 */
class CreateDraftTopSheetUseCase(
    private val accounts: AccountRepository,
    private val stores: StoreRepository,
    private val providers: ProviderRepository,
    private val topsheets: TopSheetRepository,
    private val batchSequences: BatchSequenceRepository,
    private val sequences: InvoiceSequenceRepository,
    private val idempotency: IdempotencyKeyRepository,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        providerId: String,
        billingPeriod: String,
        compilerId: String,
        idem: IdempotencyContext? = null,
    ): TopSheet = tx.inTransaction {
        idempotent(idempotency, "topsheet.draft", idem, 201) {
            requireNotFuturePeriod(billingPeriod, clock)
            val provider = providers.findById(providerId)
                ?: throw DomainError.NotFound("provider $providerId not found")
            // One open DRAFT per provider/period (also enforced by the uq_draft_per_provider_period
            // partial index). Surface it as a clean 409 rather than letting the index throw a raw
            // 500. A same-key idempotent retry never reaches here — it replays the stored 201.
            if (topsheets.list(providerId, billingPeriod, TopSheetStatus.DRAFT).isNotEmpty()) {
                throw DomainError.Conflict("a draft already exists for this provider/period", "draft_exists")
            }
            val billed = topsheets.billedAccountIds(billingPeriod)
            val settled = topsheets.billedPeriodsByAccount(providerId)
            val storesById = stores.list(null, null).associateBy { it.id }
            val lines = classify(
                providerId, billingPeriod, accounts.list(null, providerId, null), storesById, billed, settled,
            ).billable
            if (lines.isEmpty()) {
                throw DomainError.Conflict(
                    "no eligible accounts to compile for provider $providerId / $billingPeriod",
                    "nothing_to_compile",
                )
            }
            val sortedLines = lines.sortedByDescending { it.store?.branchCode ?: "" }
            val prefix = sequences.prefixOf(providerId) ?: InvoiceNumberFormatter.prefix(provider.name)
            val batchSeq = batchSequences.nextValue(providerId)
            val batchNumber = "${prefix}${billingPeriod.replace("-", "")}-B${batchSeq.toString().padStart(3, '0')}"
            val topsheet = topsheets.createDraft(
                billingPeriod = billingPeriod,
                providerId = providerId,
                providerName = provider.name,
                accountCount = sortedLines.size,
                totalAmount = sortedLines.total(),
                batchNumber = batchNumber,
                compilerId = compilerId,
            )
            sortedLines.forEachIndexed { index, e ->
                topsheets.addLine(
                    topsheet.id, NewTopSheetLine(
                        accountId = e.account.id,
                        billingPeriod = billingPeriod,
                        proratedAmount = e.proratedAmount,
                        fullAmount = e.account.rate,
                        branchCode = e.store?.branchCode,
                        storeName = e.store?.name,
                        circuitId = e.account.circuitId,
                        accountNumber = e.account.accountNumber,
                        accountStatus = e.account.status.name.lowercase(),
                        rfpSortOrder = index + 1,
                        arrearsAmount = e.arrearsAmount,
                        arrearsPeriods = e.arrearsPeriods,
                    )
                )
            }
            activity.record(compilerId, "topsheet.draft_created", "topsheet", topsheet.id)
            topsheet
        }
    }
}

/**
 * Edit a single line in a DRAFT topsheet: set/update the RFP number and/or
 * override the prorated amount. Header totals are recalculated at confirm time.
 */
class UpdateDraftLineUseCase(
    private val topsheets: TopSheetRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        topsheetId: String,
        lineId: String,
        rfpNumber: String?,
        proratedAmount: String?,
    ): TopSheetDetail = tx.inTransaction {
        val ts = topsheets.findById(topsheetId)
            ?: throw DomainError.NotFound("topsheet $topsheetId not found")
        if (ts.status != TopSheetStatus.DRAFT) {
            throw DomainError.Conflict("only draft topsheets can be edited (was ${ts.status.name.lowercase()})")
        }
        if (rfpNumber == null && proratedAmount == null) {
            throw DomainError.Validation("at least one of rfpNumber or proratedAmount must be provided")
        }
        if (rfpNumber != null && !rfpNumber.matches(Regex("^\\d+$"))) {
            throw DomainError.Validation("rfpNumber must be numeric only")
        }
        if (proratedAmount != null) {
            if (proratedAmount.isBlank()) {
                throw DomainError.Validation("proratedAmount must be a valid decimal amount")
            }
            val parsed = try {
                proratedAmount.toMoney()
            } catch (e: NumberFormatException) {
                throw DomainError.Validation("proratedAmount must be a valid decimal amount")
            }
            if (parsed <= BigDecimal.ZERO) {
                throw DomainError.Validation("proratedAmount must be greater than zero")
            }
        }
        topsheets.findLines(topsheetId).find { it.id == lineId }
            ?: throw DomainError.NotFound("line $lineId not found")
        topsheets.updateLine(lineId, rfpNumber, proratedAmount)
            ?: throw DomainError.NotFound("line $lineId not found")
    }
}

/**
 * Bulk-assign RFP numbers across a DRAFT topsheet's lines. Store codes
 * ([TopSheetDetail.branchCode]) are numbered in display order — descending, so the top
 * line (highest branch code, matching GET /lines / rfpSortOrder) gets [startRfpNumber]
 * and the numbers grow downward. A sequential RFP number is minted per distinct store
 * code, so every account sharing a store code receives the same RFP number. Storeless
 * lines (null branchCode) are skipped — their RFP stays null for a manual PATCH.
 * [endRfpNumber] is a safety check: the range must cover exactly as many numbers as
 * there are distinct (non-null) store codes. The lines are returned in display order for
 * the Secretary to review before confirming.
 */
class AssignRfpNumbersUseCase(
    private val topsheets: TopSheetRepository,
    private val activity: ActivityRecorder,
    private val tx: TransactionRunner,
) {
    private val numeric = Regex("^\\d+$")

    suspend operator fun invoke(
        topsheetId: String,
        startRfpNumber: String,
        endRfpNumber: String,
        callerId: String,
    ): List<TopSheetDetail> = tx.inTransaction {
        val ts = topsheets.findById(topsheetId)
            ?: throw DomainError.NotFound("topsheet $topsheetId not found")
        if (ts.status != TopSheetStatus.DRAFT) {
            throw DomainError.Conflict("only draft topsheets can be edited (was ${ts.status.name.lowercase()})")
        }
        if (!startRfpNumber.matches(numeric)) {
            throw DomainError.Validation("startRfpNumber must be numeric only")
        }
        if (!endRfpNumber.matches(numeric)) {
            throw DomainError.Validation("endRfpNumber must be numeric only")
        }
        val start = startRfpNumber.toBigInteger()
        val end = endRfpNumber.toBigInteger()
        if (end < start) {
            throw DomainError.Validation("endRfpNumber must be greater than or equal to startRfpNumber")
        }
        val lines = topsheets.findLines(topsheetId)
        if (lines.isEmpty()) {
            throw DomainError.Conflict("draft topsheet $topsheetId has no lines to number", "nothing_to_compile")
        }
        // Number in display order: store code DESCENDING so the top line (highest branch
        // code, matching GET /lines / rfpSortOrder order) claims [startRfpNumber] and the
        // numbers grow downward. Storeless lines (null branchCode) can't be numbered by
        // store code, so they are skipped — their RFP stays null for a manual PATCH.
        val distinctCodes = lines.mapNotNull { it.branchCode }.distinct().sortedDescending()
        val provided = end - start + BigInteger.ONE
        if (provided != distinctCodes.size.toBigInteger()) {
            throw DomainError.Validation(
                "RFP range covers $provided number(s) but there are ${distinctCodes.size} store code(s) to number",
            )
        }
        // Preserve the width of the provided range (e.g. "0100021" stays 7 digits).
        val width = maxOf(startRfpNumber.length, endRfpNumber.length)
        val rfpByCode = distinctCodes.withIndex().associate { (i, code) ->
            code to (start + i.toBigInteger()).toString().padStart(width, '0')
        }
        for (line in lines) {
            val rfp = line.branchCode?.let { rfpByCode[it] } ?: continue
            topsheets.updateLine(line.id, rfp, null)
        }
        activity.record(callerId, "topsheet.rfp_assigned", "topsheet", topsheetId)
        // Return in the documented display order (GET /lines / rfpSortOrder).
        topsheets.findLines(topsheetId)
    }
}

/**
 * Remove a line from a DRAFT topsheet. The last remaining line cannot be
 * removed — the entire draft must be deleted instead.
 */
class RemoveDraftLineUseCase(
    private val topsheets: TopSheetRepository,
    private val activity: ActivityRecorder,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        topsheetId: String,
        lineId: String,
        callerId: String,
    ): Unit = tx.inTransaction {
        val ts = topsheets.findById(topsheetId)
            ?: throw DomainError.NotFound("topsheet $topsheetId not found")
        if (ts.status != TopSheetStatus.DRAFT) {
            throw DomainError.Conflict("only draft topsheets can be edited (was ${ts.status.name.lowercase()})")
        }
        val lines = topsheets.findLines(topsheetId)
        lines.find { it.id == lineId }
            ?: throw DomainError.NotFound("line $lineId not found")
        if (lines.size == 1) {
            throw DomainError.Conflict("Cannot remove all lines; delete the draft instead")
        }
        topsheets.removeLine(lineId)
        activity.record(callerId, "topsheet.line_removed", "topsheet", topsheetId)
    }
}

/**
 * Phase 2: confirm a DRAFT topsheet — validates all RFP numbers are present,
 * re-checks eligibility (accounts may have changed since draft creation),
 * recalculates totals from current line values, mints the invoice number,
 * and transitions the topsheet to COMPILED.
 */
class ConfirmTopSheetUseCase(
    private val accounts: AccountRepository,
    private val stores: StoreRepository,
    private val topsheets: TopSheetRepository,
    private val sequences: InvoiceSequenceRepository,
    private val idempotency: IdempotencyKeyRepository,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        topsheetId: String,
        confirmerId: String,
        acknowledgeArrears: Boolean = false,
        idem: IdempotencyContext? = null,
    ): TopSheet = tx.inTransaction {
        idempotent(idempotency, "topsheet.confirm", idem, 200) {
            val ts = topsheets.findById(topsheetId)
                ?: throw DomainError.NotFound("topsheet $topsheetId not found")
            if (ts.status != TopSheetStatus.DRAFT) {
                throw DomainError.Conflict("only draft topsheets can be confirmed (was ${ts.status.name.lowercase()})")
            }
            val lines = topsheets.findLines(topsheetId)
            if (lines.isEmpty()) {
                throw DomainError.Conflict("draft topsheet $topsheetId has no lines to confirm", "nothing_to_compile")
            }
            val missingRfp = lines.filter { it.rfpNumber == null }
            if (missingRfp.isNotEmpty()) {
                throw DomainError.Validation("All lines must have an RFP number before confirming")
            }
            // Arrears (recovered prior-period partials) must be explicitly acknowledged.
            val arrearsLines = lines.filter { it.arrearsAmount.toMoney() > BigDecimal.ZERO }
            if (arrearsLines.isNotEmpty() && !acknowledgeArrears) {
                throw DomainError.Validation(
                    "${arrearsLines.size} account(s) carry arrears; acknowledgeArrears is required to confirm",
                )
            }
            val providerId = ts.providerId
                ?: throw DomainError.Conflict("topsheet $topsheetId has no provider assigned", "missing_provider")
            // Re-validate eligibility (accounts may have changed since draft creation)
            val billedIds = topsheets.billedAccountIds(ts.billingPeriod)
            val accountsById = accounts.list(null, providerId, null).associateBy { it.id }
            val ineligible = mutableListOf<String>()
            val doubleBilled = mutableListOf<String>()
            for (line in lines) {
                val account = accountsById[line.accountId]
                if (account == null || !ProrationEngine.isEligible(account, providerId, ts.billingPeriod, emptySet())) {
                    ineligible.add(line.accountId)
                } else if (billedIds.contains(line.accountId)) {
                    doubleBilled.add(line.accountId)
                }
            }
            if (ineligible.isNotEmpty()) {
                throw DomainError.Conflict("accounts no longer eligible: $ineligible")
            }
            if (doubleBilled.isNotEmpty()) {
                throw DomainError.Conflict("accounts already billed in this period: $doubleBilled")
            }
            // Recalculate totals from current line values (current-period charge + arrears)
            val totalAmount = lines
                .fold(BigDecimal.ZERO) { acc, l -> acc + l.proratedAmount.toMoney() + l.arrearsAmount.toMoney() }
                .toMoneyString()
            val accountCount = lines.size
            // Mint invoice number
            val sequence = sequences.nextValue(providerId)
            val prefix = sequences.prefixOf(providerId) ?: InvoiceNumberFormatter.prefix(ts.providerName ?: "")
            val invoiceNumber = InvoiceNumberFormatter.format(prefix, ts.billingPeriod, sequence)
            val confirmed = topsheets.confirm(topsheetId, invoiceNumber, accountCount, totalAmount)
                ?: throw DomainError.NotFound("topsheet $topsheetId not found")
            activity.record(confirmerId, "topsheet.compiled", "topsheet", topsheetId)
            confirmed
        }
    }
}
