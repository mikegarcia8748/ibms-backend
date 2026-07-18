package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.GetCurrentUserUseCase
import com.puregoldbe.ibms.application.usecase.ListUsersUseCase
import com.puregoldbe.ibms.application.usecase.UpdateUserRoleUseCase
import com.puregoldbe.ibms.domain.model.UpdateRoleRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Route.userRoutes(
    getCurrentUser: GetCurrentUserUseCase,
    listUsers: ListUsersUseCase,
    updateUserRole: UpdateUserRoleUseCase,
) {
    route("/users") {
        get("/me") {
            val caller = call.authorize()
            call.ok(getCurrentUser(caller.userId))
        }
        get {
            call.authorize(UserRole.SYSADMIN)
            val p = call.pageParams()
            call.ok(listUsers(parseUserRole(call.request.queryParameters["role"]), p.cursor, p.limit))
        }
        patch("/{id}/role") {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<UpdateRoleRequest>()
            call.ok(updateUserRole(call.pathId(), req.role))
        }
    }
}
