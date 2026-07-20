package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.CloseStoreUseCase
import com.puregoldbe.ibms.application.usecase.CreateStoreUseCase
import com.puregoldbe.ibms.application.usecase.GetFloatingAccountsUseCase
import com.puregoldbe.ibms.application.usecase.GetStoreUseCase
import com.puregoldbe.ibms.application.usecase.ListStoresUseCase
import com.puregoldbe.ibms.application.usecase.UpdateStoreUseCase
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
    updateStore: UpdateStoreUseCase,
    closeStore: CloseStoreUseCase,
    getFloatingAccounts: GetFloatingAccountsUseCase,
) {
    route("/stores") {
        get {
            call.authorize()
            val status = parseStoreStatus(call.request.queryParameters["status"])
            val query = call.request.queryParameters["q"]
            val p = call.pageParams()
            call.ok(listStores(status, query, p.cursor, p.limit))
        }
        get("/floating-accounts") {
            call.authorize()
            call.ok(getFloatingAccounts())
        }
        get("/{id}") {
            call.authorize()
            call.ok(getStore(call.pathId()))
        }
        post {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = call.receive<StoreUpsertRequest>()
            call.created(createStore(req, caller.userId))
        }
        put("/{id}") {
            call.authorize(UserRole.SECRETARY)
            val req = call.receive<StoreUpsertRequest>()
            call.ok(updateStore(call.pathId(), req))
        }
        // Contract calls this "deactivate"; it performs the same close-store operation.
        post("/{id}/deactivate") {
            call.authorize(UserRole.SECRETARY)
            val req = call.receive<CloseStoreRequest>()
            call.ok(closeStore(call.pathId(), req.reason, req.proofOfClosureId))
        }
    }
}
