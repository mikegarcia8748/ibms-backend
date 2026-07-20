package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.CompilableLine
import com.puregoldbe.ibms.domain.model.CompilablePreview
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
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
import java.math.BigDecimal

/** Eligible account + its store + computed proration, shared by preview and compile. */
internal data class EligibleLine(
    val account: Account,
    val store: Store?,
    val proratedAmount: String,
    val isProrated: Boolean,
)

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

private fun requirePeriod(billingPeriod: String) {
    if (!BillingPeriod.isValid(billingPeriod)) {
        throw DomainError.Validation("billingPeriod must be YYYY-MM")
    }
}

private fun List<EligibleLine>.total(): String =
    fold(BigDecimal.ZERO) { acc, line -> acc + line.proratedAmount.toMoney() }.toMoneyString()

/** Pure read: the eligible lines + prorated amounts a Secretary reviews before compiling. */
class PreviewCompilationUseCase(
    private val accounts: AccountRepository,
    private val stores: StoreRepository,
    private val topsheets: TopSheetRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(providerId: String, billingPeriod: String): CompilablePreview = tx.inTransaction {
        requirePeriod(billingPeriod)
        val billed = topsheets.billedAccountIds(billingPeriod)
        val storesById = stores.list(null, null).associateBy { it.id }
        val lines = computeEligible(providerId, billingPeriod, accounts.list(null, providerId, null), storesById, billed)
        CompilablePreview(
            providerId = providerId,
            billingPeriod = billingPeriod,
            lines = lines.map { e ->
                CompilableLine(
                    accountId = e.account.id,
                    accountNumber = e.account.accountNumber,
                    branchCode = e.store?.branchCode,
                    storeName = e.store?.name,
                    circuitId = e.account.circuitId,
                    fullAmount = e.account.rate,
                    proratedAmount = e.proratedAmount,
                    isProrated = e.isProrated,
                )
            },
            totalAmount = lines.total(),
        )
    }
}

/**
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
