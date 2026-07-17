package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.CreateAccountUseCase
import com.puregoldbe.ibms.application.usecase.DeactivateAccountUseCase
import com.puregoldbe.ibms.application.usecase.GetAccountUseCase
import com.puregoldbe.ibms.application.usecase.ListAccountsUseCase
import com.puregoldbe.ibms.application.usecase.TransferAccountUseCase
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.DeactivateAccountRequest
import com.puregoldbe.ibms.domain.model.TransferAccountRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.accountRoutes(
    listAccounts: ListAccountsUseCase,
    getAccount: GetAccountUseCase,
    createAccount: CreateAccountUseCase,
    transferAccount: TransferAccountUseCase,
    deactivateAccount: DeactivateAccountUseCase,
) {
    route("/accounts") {
        get {
            call.authorize()
            call.respond(
                listAccounts(
                    storeId = call.request.queryParameters["storeId"],
                    providerId = call.request.queryParameters["providerId"],
                    status = parseAccountStatus(call.request.queryParameters["status"]),
                ),
            )
        }
        get("/{id}") {
            call.authorize()
            call.respond(getAccount(call.pathId()))
        }
        post {
            val caller = call.authorize(UserRole.SYSADMIN, UserRole.SECRETARY, UserRole.PAYABLES)
            val req = call.receive<AccountUpsertRequest>()
            call.respond(HttpStatusCode.Created, createAccount(req, caller.userId))
        }
        post("/{id}/transfer") {
            val caller = call.authorize(UserRole.SYSADMIN, UserRole.SECRETARY, UserRole.PAYABLES)
            val req = call.receive<TransferAccountRequest>()
            call.respond(HttpStatusCode.Created, transferAccount(call.pathId(), req.newStoreId, req.proofId, caller.userId))
        }
        post("/{id}/deactivate") {
            val caller = call.authorize(UserRole.SYSADMIN, UserRole.SECRETARY, UserRole.PAYABLES)
            val req = call.receive<DeactivateAccountRequest>()
            call.respond(deactivateAccount(call.pathId(), req.proofId, caller.userId))
        }
    }
}
