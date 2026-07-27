package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.model.AccountListItem
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.DashboardSummary
import com.puregoldbe.ibms.domain.model.StatusCount
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner

/**
 * Read-side use cases backing the Manager's Dashboard. Aggregation and denormalized
 * listing live here; the archive views and filtered export are served by the
 * dashboard controller delegating to existing use cases (ListStores, ExportAccounts).
 */

/**
 * Features 1 & 2 — headline totals plus per-ISP breakdown. Totals cover ACTIVE
 * (billable) accounts; [DashboardSummary.statusBreakdown] reports counts for every
 * status so archived/pending accounts remain visible.
 */
class GetDashboardSummaryUseCase(
    private val accounts: AccountRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(): DashboardSummary = tx.inTransaction {
        val active = accounts.aggregate(AccountStatus.ACTIVE)
        val counts = accounts.countByStatus()
        DashboardSummary(
            totalActiveAccounts = active.accountCount,
            totalActiveMrc = active.totalMrc,
            statusBreakdown = AccountStatus.entries.map { StatusCount(it, counts[it] ?: 0) },
            byProvider = accounts.aggregateByProvider(AccountStatus.ACTIVE),
        )
    }
}

/**
 * Feature 3 — accounts with their associated store (denormalized, keyset-paginated).
 * Optional filters mirror `GET /accounts`: by store, provider (ISP), and status.
 */
class ListDashboardAccountsUseCase(
    private val accounts: AccountRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        storeId: String?,
        providerId: String?,
        status: AccountStatus?,
        cursor: String?,
        limit: Int,
    ): CursorPage<AccountListItem> = tx.inTransaction {
        accounts.pageWithDetails(storeId, providerId, status, cursor, limit)
    }
}

/**
 * Feature 5 — billing history / compiled top sheets. Excludes DRAFT top sheets by
 * default (pass an explicit [status] to narrow to compiled/approved/paid).
 */
class ListBillingHistoryUseCase(
    private val topsheets: TopSheetRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        providerId: String?,
        billingPeriod: String?,
        status: TopSheetStatus?,
        cursor: String?,
        limit: Int,
    ): CursorPage<TopSheet> = tx.inTransaction {
        topsheets.pageHistory(providerId, billingPeriod, status, cursor, limit)
    }
}
