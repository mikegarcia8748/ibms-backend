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

    fun approve(id: String, approverId: String, at: Instant): TopSheet?

    /** Move to paid and cascade all line items to paid. */
    fun pay(id: String, at: Instant): TopSheet?
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
