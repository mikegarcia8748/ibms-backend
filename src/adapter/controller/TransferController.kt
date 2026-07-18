package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ListTransfersUseCase
import com.puregoldbe.ibms.application.usecase.TransferAccountUseCase
import com.puregoldbe.ibms.domain.model.TransferCreateRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Transfers as a top-level group. `POST /transfers` reuses the same
 * [TransferAccountUseCase] as `POST /accounts/{id}/transfer` (idempotent via the
 * Idempotency-Key header); `GET /transfers` is the cursor-paginated history.
 */
fun Route.transferRoutes(
    listTransfers: ListTransfersUseCase,
    transferAccount: TransferAccountUseCase,
) {
    route("/transfers") {
        get {
            call.authorize()
            val p = call.pageParams()
            call.ok(listTransfers(call.request.queryParameters["accountId"], p.cursor, p.limit))
        }
        post {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = call.receive<TransferCreateRequest>()
            val idem = call.idempotencyContext(caller.userId, Json.encodeToString(req))
            call.created(transferAccount(req.accountId, req.newStoreId, req.proofId, caller.userId, idem))
        }
    }
}
