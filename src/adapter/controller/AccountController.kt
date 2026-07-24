package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.BulkImportAccountsUseCase
import com.puregoldbe.ibms.application.usecase.CancelDeactivationUseCase
import com.puregoldbe.ibms.application.usecase.CreateAccountUseCase
import com.puregoldbe.ibms.application.usecase.DeactivateAccountUseCase
import com.puregoldbe.ibms.application.usecase.GetAccountUseCase
import com.puregoldbe.ibms.application.usecase.ListAccountsUseCase
import com.puregoldbe.ibms.application.usecase.TransferAccountUseCase
import com.puregoldbe.ibms.application.usecase.UpdateAccountUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.CancelDeactivationRequest
import com.puregoldbe.ibms.domain.model.DeactivateAccountRequest
import com.puregoldbe.ibms.domain.model.TransferAccountRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.accountRoutes(
    listAccounts: ListAccountsUseCase,
    getAccount: GetAccountUseCase,
    createAccount: CreateAccountUseCase,
    updateAccount: UpdateAccountUseCase,
    transferAccount: TransferAccountUseCase,
    deactivateAccount: DeactivateAccountUseCase,
    cancelDeactivation: CancelDeactivationUseCase,
    bulkImport: BulkImportAccountsUseCase,
) {
    route("/accounts") {
        get {
            call.authorize()
            val p = call.pageParams()
            call.ok(
                listAccounts(
                    storeId = call.request.queryParameters["storeId"],
                    providerId = call.request.queryParameters["providerId"],
                    status = parseAccountStatus(call.request.queryParameters["status"]),
                    cursor = p.cursor,
                    limit = p.limit,
                ),
            )
        }
        get("/{id}") {
            call.authorize()
            call.ok(getAccount(call.pathId()))
        }
        post {
            val caller = call.authorize(UserRole.SECRETARY, UserRole.FINANCE)
            val req = call.receive<AccountUpsertRequest>()
            call.created(createAccount(req, caller.userId))
        }
        post("/bulk-import") {
            val caller = call.authorize(UserRole.SYSADMIN)
            val multipart = call.receiveMultipart()
            var fileBytes: ByteArray? = null
            multipart.forEachPart { part ->
                if (part is PartData.FileItem) fileBytes = part.provider().readRemaining().readByteArray()
                part.dispose()
            }
            val bytes = fileBytes ?: throw DomainError.Validation("file is required")
            call.ok(bulkImport(bytes, caller.userId), "bulk import completed")
        }
        put("/{id}") {
            call.authorize(UserRole.SECRETARY, UserRole.FINANCE)
            val req = call.receive<AccountUpsertRequest>()
            call.ok(updateAccount(call.pathId(), req))
        }
        post("/{id}/transfer") {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = call.receive<TransferAccountRequest>()
            call.created(transferAccount(call.pathId(), req.newStoreId, req.proofId, caller.userId))
        }
        post("/{id}/deactivate") {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = call.receive<DeactivateAccountRequest>()
            val idem = call.idempotencyContext(caller.userId, Json.encodeToString(req))
            call.ok(deactivateAccount(call.pathId(), req.proofId, caller.userId, idem))
        }
        post("/{id}/cancel-deactivation") {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = call.receive<CancelDeactivationRequest>()
            call.ok(cancelDeactivation(call.pathId(), req.reason, caller.userId))
        }
    }
}
