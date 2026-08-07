package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.CompilableLine
import com.puregoldbe.ibms.domain.model.CompilablePreview
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.DeepLinks
import com.puregoldbe.ibms.domain.model.NotYetSubscribedLine
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.BatchSequenceRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.domain.port.IdempotencyKeyRepository
import com.puregoldbe.ibms.domain.port.InvoiceSequenceRepository
import com.puregoldbe.ibms.domain.port.NewTopSheetLine
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.RfpGateway
import com.puregoldbe.ibms.domain.port.RfpGenerationInput
import com.puregoldbe.ibms.domain.port.RfpLineInput
import com.puregoldbe.ibms.domain.port.RfpReleaseInput
import com.puregoldbe.ibms.domain.port.RfpReleaseLine
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TopSheetLineRfp
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.service.BILLING_ZONE
import com.puregoldbe.ibms.domain.service.InvoiceNumberFormatter
import com.puregoldbe.ibms.domain.service.ProrationEngine
import com.puregoldbe.ibms.domain.valueobject.BillingPeriod
import com.puregoldbe.ibms.domain.valueobject.Money
import com.puregoldbe.ibms.domain.valueobject.toMoney
import com.puregoldbe.ibms.domain.valueobject.toMoneyOrNull
import com.puregoldbe.ibms.domain.valueobject.toMoneyString
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal

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
            val prorated = ProrationEngine.proratedAmount(acc, billingPeriod)
            val arrears = ProrationEngine.arrearsAmount(acc, billingPeriod, settled)
            // Skip a zero-charge line: an account that prorates to 0.00 (e.g. dirty
            // grace/termination dates) with no arrears has nothing to bill.
            if (prorated.toMoney().signum() == 0 && arrears.toMoney().signum() == 0) continue
            billable += EligibleLine(
                account = acc,
                store = storesById[acc.storeId],
                proratedAmount = prorated,
                isProrated = ProrationEngine.isFirstBillProrated(acc, billingPeriod),
                arrearsAmount = arrears,
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
    // Use the canonical billing timezone (Asia/Manila) for the business-day boundary.
    val local = now.toLocalDateTime(BILLING_ZONE)
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

/** Finance payment: approved -> paid, cascading line items to paid. */
class PayTopSheetUseCase(
    private val topsheets: TopSheetRepository,
    private val idempotency: IdempotencyKeyRepository,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        id: String,
        chequeNumber: String,
        idem: IdempotencyContext? = null,
    ): TopSheet {
        val cheque = chequeNumber.trim()
        if (cheque.isBlank()) throw DomainError.Validation("chequeNumber is required to pay a topsheet")
        return tx.inTransaction {
            idempotent(idempotency, "topsheet.pay", idem, 200) {
                val ts = topsheets.findById(id) ?: throw DomainError.NotFound("topsheet $id not found")
                if (ts.status != TopSheetStatus.APPROVED) {
                    throw DomainError.Conflict("only approved topsheets can be paid (was ${ts.status.name.lowercase()})")
                }
                topsheets.pay(id, cheque, clock.now())
                    ?: throw DomainError.Conflict("topsheet $id is no longer in approved status")
            }
        }
    }
}

// =====================================================================
//  Two-phase compilation: DRAFT -> (edit lines) -> CONFIRM
// =====================================================================

/**
 * Phase 1: create a DRAFT topsheet with eligible accounts for a provider/period.
 * Lines are pre-sorted by store branch code (descending) and assigned an rfpSortOrder.
 * The invoice and batch numbers are minted later, at confirm; RFP numbers are assigned
 * after that by the external system (generate-rfp).
 */
class CreateDraftTopSheetUseCase(
    private val accounts: AccountRepository,
    private val stores: StoreRepository,
    private val providers: ProviderRepository,
    private val topsheets: TopSheetRepository,
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
            val topsheet = topsheets.createDraft(
                billingPeriod = billingPeriod,
                providerId = providerId,
                providerName = provider.name,
                accountCount = sortedLines.size,
                totalAmount = sortedLines.total(),
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
 * Edit a single line in a DRAFT topsheet: override the prorated amount. RFP numbers
 * are assigned by the external system after confirm, not here. Header totals are
 * recalculated at confirm time.
 */
class UpdateDraftLineUseCase(
    private val topsheets: TopSheetRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        topsheetId: String,
        lineId: String,
        proratedAmount: String?,
    ): TopSheetDetail = tx.inTransaction {
        // FOR UPDATE: serialize against a concurrent confirm/cancel so we never edit a
        // line whose parent has just left DRAFT.
        val ts = topsheets.findByIdForUpdate(topsheetId)
            ?: throw DomainError.NotFound("topsheet $topsheetId not found")
        if (ts.status != TopSheetStatus.DRAFT) {
            throw DomainError.Conflict("only draft topsheets can be edited (was ${ts.status.name.lowercase()})")
        }
        if (proratedAmount == null || proratedAmount.isBlank()) {
            throw DomainError.Validation("proratedAmount must be a valid decimal amount")
        }
        val parsed = proratedAmount.toMoneyOrNull()
            ?: throw DomainError.Validation("proratedAmount must be a valid decimal amount")
        if (parsed <= BigDecimal.ZERO) {
            throw DomainError.Validation("proratedAmount must be greater than zero")
        }
        val line = topsheets.findLines(topsheetId).find { it.id == lineId }
            ?: throw DomainError.NotFound("line $lineId not found")
        // A prorated amount is a within-period charge; it must never exceed the full
        // monthly rate (guards against a fat-fingered override inflating the invoice).
        if (parsed > line.fullAmount.toMoney()) {
            throw DomainError.Validation(
                "proratedAmount cannot exceed the line's full monthly charge (${line.fullAmount})",
            )
        }
        topsheets.updateLineAmount(lineId, proratedAmount)
            ?: throw DomainError.NotFound("line $lineId not found")
    }
}

/**
 * Generate RFP numbers for a COMPILED topsheet by calling the external RFP system.
 * The external system returns an RFP number + a unique key per line; both are
 * persisted and the topsheet moves COMPILED -> RFP_ASSIGNED. Idempotent: a retry
 * with the same Idempotency-Key replays the stored result rather than re-calling the
 * external system (guarding against double-minting on a network retry).
 */
class GenerateRfpUseCase(
    private val topsheets: TopSheetRepository,
    private val rfp: RfpGateway,
    private val idempotency: IdempotencyKeyRepository,
    private val activity: ActivityRecorder,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        topsheetId: String,
        callerId: String,
        idem: IdempotencyContext? = null,
    ): List<TopSheetDetail> = tx.inTransaction {
        idempotent(idempotency, "topsheet.generate_rfp", idem, 200) {
            val ts = topsheets.findById(topsheetId)
                ?: throw DomainError.NotFound("topsheet $topsheetId not found")
            if (ts.status != TopSheetStatus.COMPILED) {
                throw DomainError.Conflict(
                    "only compiled topsheets can generate RFP numbers (was ${ts.status.name.lowercase()})",
                )
            }
            val lines = topsheets.findLines(topsheetId)
            if (lines.isEmpty()) {
                throw DomainError.Conflict("topsheet $topsheetId has no lines to number", "nothing_to_compile")
            }
            val result = rfp.generateRfp(
                RfpGenerationInput(
                    topsheetId = topsheetId,
                    billingPeriod = ts.billingPeriod,
                    providerName = ts.providerName,
                    batchNumber = ts.batchNumber,
                    lines = lines.map { line ->
                        RfpLineInput(
                            lineId = line.id,
                            accountId = line.accountId,
                            accountNumber = line.accountNumber,
                            branchCode = line.branchCode,
                            storeName = line.storeName,
                            amount = line.proratedAmount,
                        )
                    },
                ),
            )
            // The external system must return exactly one assignment per line.
            val knownLineIds = lines.map { it.id }.toSet()
            val assignments = result.lines
                .filter { it.lineId in knownLineIds }
                .map { TopSheetLineRfp(it.lineId, it.rfpNumber, it.uniqueKey) }
            if (assignments.map { it.lineId }.toSet() != knownLineIds) {
                throw DomainError.Conflict(
                    "external RFP system returned ${assignments.size} assignment(s) for ${lines.size} line(s)",
                    "rfp_incomplete",
                )
            }
            topsheets.assignExternalRfp(topsheetId, assignments)
                ?: throw DomainError.Conflict("topsheet $topsheetId is no longer compiled")
            activity.record(callerId, "topsheet.rfp_assigned", "topsheet", topsheetId)
            // Return in the documented display order (GET /lines / rfpSortOrder).
            topsheets.findLines(topsheetId)
        }
    }
}

/**
 * Secretary handoff to finance. Requires an RFP_ASSIGNED topsheet, tells the external
 * system to move the (open) payment transaction to finance, then transitions
 * RFP_ASSIGNED -> APPROVED ('approved' now means "released to finance"; there is no
 * separate Finance-approval step).
 */
class ReleaseToFinanceUseCase(
    private val topsheets: TopSheetRepository,
    private val rfp: RfpGateway,
    private val activity: ActivityRecorder,
    private val notifications: NotificationEnqueuer,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(topsheetId: String, callerId: String): TopSheet = tx.inTransaction {
        val ts = topsheets.findById(topsheetId)
            ?: throw DomainError.NotFound("topsheet $topsheetId not found")
        if (ts.status != TopSheetStatus.RFP_ASSIGNED) {
            throw DomainError.Conflict(
                "only rfp_assigned topsheets can be released to finance (was ${ts.status.name.lowercase()})",
            )
        }
        val lines = topsheets.findLines(topsheetId)
        val result = rfp.notifyReleaseToFinance(
            RfpReleaseInput(
                topsheetId = topsheetId,
                invoiceNumber = ts.invoiceNumber,
                lines = lines.map { RfpReleaseLine(it.id, it.rfpNumber, it.rfpUniqueKey) },
            ),
        )
        if (!result.success) {
            throw DomainError.Conflict("external RFP system rejected the release", "rfp_release_failed")
        }
        val released = topsheets.releaseToFinance(topsheetId, callerId, clock.now())
            ?: throw DomainError.Conflict("topsheet $topsheetId is not in rfp_assigned status")
        activity.record(callerId, "topsheet.released_to_finance", "topsheet", topsheetId)
        notifications.enqueue(
            NotificationEvent.TOPSHEET_RELEASED,
            NotificationContext(
                headline = "Topsheet released to finance: invoice ${released.invoiceNumber ?: released.id}",
                details = listOfNotNull(
                    released.invoiceNumber?.let { "Invoice" to it },
                    released.providerName?.let { "Provider" to it },
                    "Billing Period" to released.billingPeriod,
                    "Total Amount" to Money.display(released.totalAmount),
                ),
                entityId = topsheetId,
                actorId = callerId,
                linkPath = DeepLinks.topsheet(topsheetId),
            ),
        )
        released
    }
}

/**
 * Remove a line from a DRAFT topsheet. The last remaining line cannot be removed — the
 * whole topsheet must be cancelled instead (see [CancelTopSheetUseCase]).
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
        // FOR UPDATE: serialize the last-line check + delete against a concurrent
        // confirm/cancel or another remove (so two removes can't empty the draft).
        val ts = topsheets.findByIdForUpdate(topsheetId)
            ?: throw DomainError.NotFound("topsheet $topsheetId not found")
        if (ts.status != TopSheetStatus.DRAFT) {
            throw DomainError.Conflict("only draft topsheets can be edited (was ${ts.status.name.lowercase()})")
        }
        val lines = topsheets.findLines(topsheetId)
        lines.find { it.id == lineId }
            ?: throw DomainError.NotFound("line $lineId not found")
        if (lines.size == 1) {
            throw DomainError.Conflict("Cannot remove the last line; cancel the topsheet instead")
        }
        topsheets.removeLine(lineId)
        activity.record(callerId, "topsheet.line_removed", "topsheet", topsheetId)
    }
}

/**
 * Phase 2: confirm a DRAFT topsheet — re-checks eligibility (accounts may have changed
 * since draft creation), requires any arrears to be acknowledged, recalculates totals
 * from current line values, mints the invoice and batch numbers, and transitions the
 * topsheet to COMPILED. RFP numbers are assigned afterwards by the external system.
 */
class ConfirmTopSheetUseCase(
    private val accounts: AccountRepository,
    private val stores: StoreRepository,
    private val topsheets: TopSheetRepository,
    private val sequences: InvoiceSequenceRepository,
    private val batchSequences: BatchSequenceRepository,
    private val idempotency: IdempotencyKeyRepository,
    private val activity: ActivityRecorder,
    private val notifications: NotificationEnqueuer,
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
            // RFP numbers are assigned by the external system AFTER confirm
            // (COMPILED -> generate-rfp -> RFP_ASSIGNED), so they are not required here.
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
            val settledByAccount = topsheets.billedPeriodsByAccount(providerId)
            val accountsById = accounts.list(null, providerId, null).associateBy { it.id }
            val ineligible = mutableListOf<String>()
            val doubleBilled = mutableListOf<String>()
            val staleArrears = mutableListOf<String>()
            for (line in lines) {
                val account = accountsById[line.accountId]
                if (account == null || !ProrationEngine.isEligible(account, providerId, ts.billingPeriod, emptySet())) {
                    ineligible.add(line.accountId)
                } else if (billedIds.contains(line.accountId)) {
                    doubleBilled.add(line.accountId)
                }
                // A prior period folded into this line's arrears at draft time may have been
                // billed on another (committed, non-draft) topsheet since — recovering it again
                // would double-charge. Our own DRAFT is excluded from settledByAccount.
                val settled = settledByAccount[line.accountId] ?: emptySet()
                if (line.arrearsPeriods.any { it in settled }) {
                    staleArrears.add(line.accountId)
                }
            }
            if (ineligible.isNotEmpty()) {
                throw DomainError.Conflict("accounts no longer eligible: $ineligible")
            }
            if (doubleBilled.isNotEmpty()) {
                throw DomainError.Conflict("accounts already billed in this period: $doubleBilled")
            }
            if (staleArrears.isNotEmpty()) {
                throw DomainError.Conflict(
                    "arrears periods already recovered on another topsheet since draft; re-preview required: $staleArrears",
                    "arrears_stale",
                )
            }
            // Recalculate totals from current line values (current-period charge + arrears)
            val totalAmount = lines
                .fold(BigDecimal.ZERO) { acc, l -> acc + l.proratedAmount.toMoney() + l.arrearsAmount.toMoney() }
                .toMoneyString()
            val accountCount = lines.size
            // Mint the invoice + batch numbers here (both at confirm, not at draft, so
            // an abandoned draft never consumes a sequence value).
            val sequence = sequences.nextValue(providerId)
            val prefix = sequences.prefixOf(providerId) ?: InvoiceNumberFormatter.prefix(ts.providerName ?: "")
            val invoiceNumber = InvoiceNumberFormatter.format(prefix, ts.billingPeriod, sequence)
            val batchSeq = batchSequences.nextValue(providerId)
            val batchNumber = "${prefix}${ts.billingPeriod.replace("-", "")}-B${batchSeq.toString().padStart(3, '0')}"
            val confirmed = topsheets.confirm(topsheetId, invoiceNumber, batchNumber, accountCount, totalAmount, clock.now())
                ?: throw DomainError.Conflict("topsheet $topsheetId is no longer in draft status")
            activity.record(confirmerId, "topsheet.compiled", "topsheet", topsheetId)
            notifications.enqueue(
                NotificationEvent.TOPSHEET_COMPILED,
                NotificationContext(
                    headline = "Topsheet compiled: invoice ${confirmed.invoiceNumber ?: confirmed.id}",
                    details = listOfNotNull(
                        confirmed.invoiceNumber?.let { "Invoice" to it },
                        confirmed.providerName?.let { "Provider" to it },
                        "Billing Period" to confirmed.billingPeriod,
                        "Total Accounts" to confirmed.accountCount.toString(),
                        "Total Amount" to Money.display(confirmed.totalAmount),
                    ),
                    entityId = topsheetId,
                    actorId = confirmerId,
                    linkPath = DeepLinks.topsheet(topsheetId),
                ),
            )
            confirmed
        }
    }
}

/**
 * Cancel/void a topsheet before RFP numbers are assigned. A DRAFT or COMPILED topsheet
 * is moved to CANCELLED (the header is kept for audit) and its lines are deleted so the
 * accounts become immediately re-billable. Blocked once the topsheet has reached
 * RFP_ASSIGNED or beyond — at that point the external RFP transaction already exists.
 *
 * [reason] is mandatory and lands in the audit trail. Cancelling a COMPILED topsheet
 * destroys a billing-history record that already carries a minted invoice number, and
 * with the RFP chain switched off COMPILED is the terminal status — so there is no
 * later point-of-no-return to stop it happening months after the fact. Requiring a
 * stated reason does not prevent that; it makes it attributable.
 */
class CancelTopSheetUseCase(
    private val topsheets: TopSheetRepository,
    private val activity: ActivityRecorder,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(topsheetId: String, callerId: String, reason: String): TopSheet {
        // Validated before the transaction opens: a missing body must not cost a round trip.
        val trimmed = reason.trim()
        if (trimmed.isEmpty()) throw DomainError.Validation("reason is required to cancel a topsheet")
        if (trimmed.length > MAX_REASON_LENGTH) {
            throw DomainError.Validation("reason must be at most $MAX_REASON_LENGTH characters")
        }
        return tx.inTransaction {
            val ts = topsheets.findById(topsheetId)
                ?: throw DomainError.NotFound("topsheet $topsheetId not found")
            if (ts.status != TopSheetStatus.DRAFT && ts.status != TopSheetStatus.COMPILED) {
                throw DomainError.Conflict(
                    "only draft or compiled topsheets can be cancelled (was ${ts.status.name.lowercase()})",
                )
            }
            val cancelled = topsheets.cancel(topsheetId)
                ?: throw DomainError.Conflict("topsheet $topsheetId is no longer in a cancellable status")
            activity.record(callerId, "topsheet.cancelled", "topsheet", topsheetId, details = trimmed)
            cancelled
        }
    }

    private companion object {
        const val MAX_REASON_LENGTH = 500
    }
}
