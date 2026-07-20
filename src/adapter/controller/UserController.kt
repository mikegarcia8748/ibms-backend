package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.GetCurrentUserUseCase
import com.puregoldbe.ibms.application.usecase.ListUsersUseCase
import com.puregoldbe.ibms.application.usecase.ProvisionUserUseCase
import com.puregoldbe.ibms.application.usecase.ResetUserPasswordUseCase
import com.puregoldbe.ibms.application.usecase.UpdateUserRoleUseCase
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.UpdateRoleRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Route.userRoutes(
    getCurrentUser: GetCurrentUserUseCase,
    listUsers: ListUsersUseCase,
    provisionUser: ProvisionUserUseCase,
    resetUserPassword: ResetUserPasswordUseCase,
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
        // Account creation is an admin act, not self-registration. The response
        // carries the generated temporary password — the one and only time it is
        // readable — for the admin to relay out-of-band.
        post {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<ProvisionUserRequest>()
            call.created(provisionUser(req), "User provisioned — relay the temporary password securely.")
        }
        post("/{id}/reset-password") {
            call.authorize(UserRole.SYSADMIN)
            val result = resetUserPassword(call.pathId())
            call.ok(result, "Temporary password issued — all existing sessions were revoked.")
        }
        patch("/{id}/role") {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<UpdateRoleRequest>()
            call.ok(updateUserRole(call.pathId(), req.role))
        }
    }
}
