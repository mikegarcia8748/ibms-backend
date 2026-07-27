package com.puregoldbe.ibms.domain.port

import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.model.TransferRecord
import kotlinx.datetime.Instant

/** A line to persist during compilation (snapshots captured at compile time). */
data class NewTopSheetLine(
    val accountId: String,
    val billingPeriod: String,
    val proratedAmount: String,
    val fullAmount: String,
    val branchCode: String?,
    val storeName: String?,
    val circuitId: String?,
    val accountNumber: String?,
    val accountStatus: String?,
    val rfpNumber: String? = null,
    val rfpSortOrder: Int? = null,
    /** Lumped recovery of un-billed prior periods; "0.00" when none. */
    val arrearsAmount: String = "0.00",
    /** The "YYYY-MM" periods folded into [arrearsAmount]. */
    val arrearsPeriods: List<String> = emptyList(),
)

/** An RFP number + external unique key to write onto a single existing line. */
data class TopSheetLineRfp(
    val lineId: String,
    val rfpNumber: String,
    val uniqueKey: String,
)

interface TopSheetRepository {
    fun addLine(topsheetId: String, line: NewTopSheetLine)

    fun findById(id: String): TopSheet?
    fun list(providerId: String?, billingPeriod: String?, status: TopSheetStatus?): List<TopSheet>
    fun page(providerId: String?, billingPeriod: String?, status: TopSheetStatus?, cursor: String?, limit: Int): CursorPage<TopSheet>

    /**
     * Billing-history page: like [page] but excludes DRAFT topsheets when [status]
     * is null (a draft is an in-progress compilation, not billing history). When
     * [status] is given it filters to exactly that status.
     */
    fun pageHistory(providerId: String?, billingPeriod: String?, status: TopSheetStatus?, cursor: String?, limit: Int): CursorPage<TopSheet>

    fun findLines(topsheetId: String): List<TopSheetDetail>

    /** Account ids already billed in [billingPeriod] (the double-billing guard set). */
    fun billedAccountIds(billingPeriod: String): Set<String>

    /**
     * Per-account set of periods already settled for [providerId] — the union of each
     * non-draft line's own billing period and any periods it recovered as arrears.
     * Used as the arrears double-recovery guard.
     */
    fun billedPeriodsByAccount(providerId: String): Map<String, Set<String>>

    /**
     * Write the external system's RFP number + unique key onto each line and move
     * the header COMPILED -> RFP_ASSIGNED (status-guarded). Returns null if the
     * topsheet was not in COMPILED.
     */
    fun assignExternalRfp(topsheetId: String, lines: List<TopSheetLineRfp>): TopSheet?

    /**
     * Secretary handoff to finance: move RFP_ASSIGNED -> APPROVED (status-guarded),
     * recording who released it and when. Returns null if not in RFP_ASSIGNED.
     */
    fun releaseToFinance(id: String, releasedById: String, at: Instant): TopSheet?

    /** Move to paid and cascade all line items to paid. */
    fun pay(id: String, at: Instant): TopSheet?

    fun createDraft(
        billingPeriod: String,
        providerId: String?,
        providerName: String?,
        accountCount: Int,
        totalAmount: String,
        batchNumber: String,
        compilerId: String,
    ): TopSheet

    /** Edit a draft line's prorated amount (RFP is now assigned by the external system). */
    fun updateLineAmount(detailId: String, proratedAmount: String): TopSheetDetail?
    fun removeLine(detailId: String): Boolean
    fun confirm(id: String, invoiceNumber: String, accountCount: Int, totalAmount: String): TopSheet?
}

interface TransferRepository {
    fun create(
        oldStoreId: String,
        newStoreId: String,
        oldAccountId: String,
        newAccountId: String,
        proofId: String?,
        requestedById: String,
        at: Instant,
    ): TransferRecord

    /** Transfers involving [accountId] (as source or destination), or all when null. */
    fun page(accountId: String?, cursor: String?, limit: Int): CursorPage<TransferRecord>
}

interface BatchSequenceRepository {
    fun seed(providerId: String)
    fun nextValue(providerId: String): Int
}
