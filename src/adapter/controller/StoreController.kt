package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.CloseStoreUseCase
import com.puregoldbe.ibms.application.usecase.CreateStoreUseCase
import com.puregoldbe.ibms.application.usecase.GetFloatingAccountsUseCase
import com.puregoldbe.ibms.application.usecase.GetStoreUseCase
import com.puregoldbe.ibms.application.usecase.ListStoresUseCase
import com.puregoldbe.ibms.domain.model.CloseStoreRequest
import com.puregoldbe.ibms.domain.model.StoreUpsertRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.storeRoutes(
    listStores: ListStoresUseCase,
    getStore: GetStoreUseCase,
    createStore: CreateStoreUseCase,
    closeStore: CloseStoreUseCase,
    getFloatingAccounts: GetFloatingAccountsUseCase,
) {
    route("/stores") {
        get {
            call.authorize()
            val status = parseStoreStatus(call.request.queryParameters["status"])
            val query = call.request.queryParameters["q"]
            call.respond(listStores(status, query))
        }
        get("/floating-accounts") {
            call.authorize()
            call.respond(getFloatingAccounts())
        }
        get("/{id}") {
            call.authorize()
            call.respond(getStore(call.pathId()))
        }
        post {
            val caller = call.authorize(UserRole.SYSADMIN, UserRole.SECRETARY)
            val req = call.receive<StoreUpsertRequest>()
            call.respond(HttpStatusCode.Created, createStore(req, caller.userId))
        }
        post("/{id}/close") {
            call.authorize(UserRole.SYSADMIN, UserRole.SECRETARY)
            val req = call.receive<CloseStoreRequest>()
            call.respond(closeStore(call.pathId(), req.reason, req.proofOfClosureId))
        }
    }
}
