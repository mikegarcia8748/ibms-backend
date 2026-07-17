package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ListUsersUseCase
import com.puregoldbe.ibms.application.usecase.UpdateUserRoleUseCase
import com.puregoldbe.ibms.domain.model.UpdateRoleRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.userRoutes(
    listUsers: ListUsersUseCase,
    updateUserRole: UpdateUserRoleUseCase,
) {
    route("/users") {
        get {
            call.authorize(UserRole.SYSADMIN)
            call.respond(listUsers(parseUserRole(call.request.queryParameters["role"])))
        }
        patch("/{id}/role") {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<UpdateRoleRequest>()
            call.respond(updateUserRole(call.pathId(), req.role))
        }
    }
}
