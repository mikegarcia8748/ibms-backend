package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ExportAccountsExcelUseCase
import com.puregoldbe.ibms.application.usecase.GetDashboardSummaryUseCase
import com.puregoldbe.ibms.application.usecase.ListBillingHistoryUseCase
import com.puregoldbe.ibms.application.usecase.ListDashboardAccountsUseCase
import com.puregoldbe.ibms.application.usecase.ListStoresUseCase
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Manager's Dashboard — consolidated read/aggregation endpoints, all gated to
 * MANAGER + FINANCE (SYSADMIN is auto-admitted as global superuser).
 *
 * The summary and the denormalized account listing are dashboard-specific; the
 * billing-history, archive and filtered-export routes are thin aliases that
 * delegate to existing use cases so there is a single manager-facing namespace.
 * The Excel alias streams bytes (respondBytes), bypassing the JSON envelope like
 * the primary `/exports/accounts.xlsx`.
 */
fun Route.dashboardRoutes(
    summary: GetDashboardSummaryUseCase,
    listDashboardAccounts: ListDashboardAccountsUseCase,
    listBillingHistory: ListBillingHistoryUseCase,
    listStores: ListStoresUseCase,
    exportAccounts: ExportAccountsExcelUseCase,
) {
    route("/dashboard") {
        // Features 1 & 2 — total active accounts + MRC, status breakdown, per-ISP breakdown.
        get("/summary") {
            call.authorize(UserRole.MANAGER, UserRole.FINANCE)
            call.ok(summary())
        }

        // Feature 3 — accounts with associated store (denormalized, paginated).
        get("/accounts") {
            call.authorize(UserRole.MANAGER, UserRole.FINANCE)
            val p = call.pageParams()
            call.ok(
                listDashboardAccounts(
                    storeId = call.request.queryParameters["storeId"],
                    providerId = call.request.queryParameters["providerId"],
                    status = parseAccountStatus(call.request.queryParameters["status"]),
                    cursor = p.cursor,
                    limit = p.limit,
                ),
            )
        }

        // Feature 5 — billing history / compiled top sheets (non-draft by default).
        get("/billing-history") {
            call.authorize(UserRole.MANAGER, UserRole.FINANCE)
            val p = call.pageParams()
            call.ok(
                listBillingHistory(
                    providerId = call.request.queryParameters["providerId"],
                    billingPeriod = call.request.queryParameters["billingPeriod"],
                    status = parseTopSheetStatus(call.request.queryParameters["status"]),
                    cursor = p.cursor,
                    limit = p.limit,
                ),
            )
        }

        // Feature 6a — archived accounts (INACTIVE), with store/provider names.
        get("/archived-accounts") {
            call.authorize(UserRole.MANAGER, UserRole.FINANCE)
            val p = call.pageParams()
            call.ok(
                listDashboardAccounts(
                    storeId = null,
                    providerId = call.request.queryParameters["providerId"],
                    status = AccountStatus.INACTIVE,
                    cursor = p.cursor,
                    limit = p.limit,
                ),
            )
        }

        // Feature 6b — archived (closed) stores.
        get("/archived-stores") {
            call.authorize(UserRole.MANAGER, UserRole.FINANCE)
            val p = call.pageParams()
            call.ok(
                listStores(
                    status = StoreStatus.CLOSED,
                    query = call.request.queryParameters["q"],
                    cursor = p.cursor,
                    limit = p.limit,
                ),
            )
        }

        // Feature 4 — Excel export filtered by ISP + status (alias of /exports/accounts.xlsx).
        get("/exports/accounts.xlsx") {
            call.authorize(UserRole.MANAGER, UserRole.FINANCE)
            val file = exportAccounts(
                providerId = call.request.queryParameters["providerId"],
                status = parseAccountStatus(call.request.queryParameters["status"]),
            )
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName).toString(),
            )
            call.respondBytes(
                file.bytes,
                ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            )
        }
    }
}
