package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.JwtService
import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.AuthenticateDevUseCase
import com.puregoldbe.ibms.application.usecase.AuthenticateWithGoogleUseCase
import com.puregoldbe.ibms.application.usecase.GetCurrentUserUseCase
import com.puregoldbe.ibms.domain.model.AuthResponse
import com.puregoldbe.ibms.domain.model.GoogleAuthRequest
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Public auth endpoints (no bearer required). */
fun Route.publicAuthRoutes(
    authenticateWithGoogle: AuthenticateWithGoogleUseCase,
    authenticateDev: AuthenticateDevUseCase,
    jwtService: JwtService,
    devAuthEnabled: Boolean,
) {
    route("/auth") {
        post("/google") {
            val req = call.receive<GoogleAuthRequest>()
            val user = authenticateWithGoogle(req.idToken)
            call.ok(AuthResponse(jwtService.issue(user), user), "Authentication successful!")
        }
        if (devAuthEnabled) {
            post("/dev-login") {
                val req = call.receive<DevLoginRequest>()
                val user = authenticateDev(req.email)
                call.ok(AuthResponse(jwtService.issue(user), user))
            }
        }
    }
}

/** Authenticated auth endpoints. */
fun Route.securedAuthRoutes(
    getCurrentUser: GetCurrentUserUseCase,
    jwtService: JwtService,
) {
    route("/auth") {
        get("/me") {
            val caller = call.authorize()
            call.ok(getCurrentUser(caller.userId))
        }
        post("/refresh") {
            val caller = call.authorize()
            val user = getCurrentUser(caller.userId)
            call.ok(AuthResponse(jwtService.issue(user), user))
        }
    }
}
