package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.CreateProviderUseCase
import com.puregoldbe.ibms.application.usecase.DeactivateProviderUseCase
import com.puregoldbe.ibms.application.usecase.ListProvidersUseCase
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.providerRoutes(
    listProviders: ListProvidersUseCase,
    createProvider: CreateProviderUseCase,
    deactivateProvider: DeactivateProviderUseCase,
) {
    route("/providers") {
        get {
            call.authorize()
            call.respond(listProviders(parseProviderStatus(call.request.queryParameters["status"])))
        }
        post {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<CreateProviderRequest>()
            call.respond(HttpStatusCode.Created, createProvider(req.name, req.paymentScheduleDay))
        }
        post("/{id}/deactivate") {
            call.authorize(UserRole.SYSADMIN)
            call.respond(deactivateProvider(call.pathId()))
        }
    }
}
