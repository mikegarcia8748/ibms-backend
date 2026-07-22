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

interface TopSheetRepository {
    fun create(
        invoiceNumber: String,
        billingPeriod: String,
        providerId: String?,
        providerName: String?,
        accountCount: Int,
        totalAmount: String,
        compilerId: String,
    ): TopSheet

    fun addLine(topsheetId: String, line: NewTopSheetLine)

    fun findById(id: String): TopSheet?
    fun list(providerId: String?, billingPeriod: String?, status: TopSheetStatus?): List<TopSheet>
    fun page(providerId: String?, billingPeriod: String?, status: TopSheetStatus?, cursor: String?, limit: Int): CursorPage<TopSheet>
    fun findLines(topsheetId: String): List<TopSheetDetail>

    /** Account ids already billed in [billingPeriod] (the double-billing guard set). */
    fun billedAccountIds(billingPeriod: String): Set<String>

    /**
     * Per-account set of periods already settled for [providerId] — the union of each
     * non-draft line's own billing period and any periods it recovered as arrears.
     * Used as the arrears double-recovery guard.
     */
    fun billedPeriodsByAccount(providerId: String): Map<String, Set<String>>

    fun approve(id: String, approverId: String, at: Instant): TopSheet?

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

    fun updateLine(detailId: String, rfpNumber: String?, proratedAmount: String?): TopSheetDetail?
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
