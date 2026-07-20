package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.AUTH_PASSWORD_CHANGE
import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.adapter.security.passwordChangeSubject
import com.puregoldbe.ibms.application.usecase.ChangeOwnPasswordUseCase
import com.puregoldbe.ibms.application.usecase.ClientContext
import com.puregoldbe.ibms.application.usecase.CompleteFirstLoginUseCase
import com.puregoldbe.ibms.application.usecase.GetCurrentUserUseCase
import com.puregoldbe.ibms.application.usecase.LogoutEverywhereUseCase
import com.puregoldbe.ibms.application.usecase.LogoutUseCase
import com.puregoldbe.ibms.application.usecase.LoginUseCase
import com.puregoldbe.ibms.application.usecase.RefreshSessionUseCase
import com.puregoldbe.ibms.domain.model.ChangeOwnPasswordRequest
import com.puregoldbe.ibms.domain.model.ChangePasswordRequest
import com.puregoldbe.ibms.domain.model.LoginOutcome
import com.puregoldbe.ibms.domain.model.LoginRequest
import com.puregoldbe.ibms.domain.model.RefreshRequest
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

/**
 * Client details recorded on the session row. `X-Forwarded-For` is only
 * meaningful behind a proxy that overwrites it; it is stored for audit and never
 * trusted for an authorization decision.
 */
private fun ApplicationCall.clientContext() = ClientContext(
    userAgent = request.headers["User-Agent"],
    ipAddress = request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
        ?: request.origin.remoteHost,
)

/**
 * Public auth endpoints (no session required).
 *
 * `/auth/password/change` sits here rather than under the session guard on
 * purpose: the caller has redeemed a temporary password and holds only a
 * change-password challenge, which the AUTH_PASSWORD_CHANGE provider admits and
 * the ordinary session provider rejects.
 */
fun Route.publicAuthRoutes(
    login: LoginUseCase,
    completeFirstLogin: CompleteFirstLoginUseCase,
    refreshSession: RefreshSessionUseCase,
) {
    route("/auth") {
        post("/login") {
            val req = call.receive<LoginRequest>()
            val result = login(req.username, req.password, call.clientContext())
            val message = when (result.outcome) {
                LoginOutcome.AUTHENTICATED -> "Authentication successful!"
                LoginOutcome.PASSWORD_CHANGE_REQUIRED ->
                    "Temporary password accepted — set a new password to continue."
            }
            call.ok(result, message)
        }

        post("/refresh") {
            val req = call.receive<RefreshRequest>()
            call.ok(refreshSession(req.refreshToken, call.clientContext()), "Session refreshed")
        }

        authenticate(AUTH_PASSWORD_CHANGE) {
            post("/password/change") {
                val req = call.receive<ChangePasswordRequest>()
                val result = completeFirstLogin(call.passwordChangeSubject(), req.newPassword, call.clientContext())
                call.ok(result, "Password set — you are now signed in.")
            }
        }
    }
}

/** Auth endpoints that require an established session. */
fun Route.securedAuthRoutes(
    getCurrentUser: GetCurrentUserUseCase,
    changeOwnPassword: ChangeOwnPasswordUseCase,
    logout: LogoutUseCase,
    logoutEverywhere: LogoutEverywhereUseCase,
) {
    route("/auth") {
        get("/me") {
            val caller = call.authorize()
            call.ok(getCurrentUser(caller.userId))
        }

        post("/password") {
            val caller = call.authorize()
            val req = call.receive<ChangeOwnPasswordRequest>()
            val result = changeOwnPassword(caller.userId, req.currentPassword, req.newPassword, call.clientContext())
            call.ok(result, "Password changed")
        }

        post("/logout") {
            val caller = call.authorize()
            logout(caller.sessionId)
            call.okEmpty("Signed out")
        }

        post("/logout-all") {
            val caller = call.authorize()
            val revoked = logoutEverywhere(caller.userId)
            call.ok(revoked, "Signed out of $revoked session(s)")
        }
    }
}
