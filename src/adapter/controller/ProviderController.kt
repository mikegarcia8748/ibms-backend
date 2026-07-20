package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.CreateProviderUseCase
import com.puregoldbe.ibms.application.usecase.DeactivateProviderUseCase
import com.puregoldbe.ibms.application.usecase.ListProvidersUseCase
import com.puregoldbe.ibms.application.usecase.UpdateProviderUseCase
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Route.providerRoutes(
    listProviders: ListProvidersUseCase,
    createProvider: CreateProviderUseCase,
    updateProvider: UpdateProviderUseCase,
    deactivateProvider: DeactivateProviderUseCase,
) {
    route("/providers") {
        get {
            call.authorize()
            val p = call.pageParams()
            call.ok(listProviders(parseProviderStatus(call.request.queryParameters["status"]), p.cursor, p.limit))
        }
        post {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<CreateProviderRequest>()
            call.created(createProvider(req.name, req.paymentScheduleDay))
        }
        put("/{id}") {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<UpdateProviderRequest>()
            call.ok(updateProvider(call.pathId(), req.name, req.paymentScheduleDay))
        }
        post("/{id}/deactivate") {
            call.authorize(UserRole.SYSADMIN)
            call.ok(deactivateProvider(call.pathId()))
        }
    }
}
