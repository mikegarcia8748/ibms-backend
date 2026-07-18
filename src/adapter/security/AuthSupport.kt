package com.puregoldbe.ibms.adapter.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.ErrorEnvelope
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.infrastructure.config.JwtConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import java.util.Date

/** The authenticated caller, resolved from the backend JWT's claims. */
data class AuthenticatedUser(val userId: String, val email: String, val role: UserRole)

/** Issues and verifies the backend JWT (HMAC256) carrying the role claim. */
class JwtService(private val cfg: JwtConfig) {
    private val algorithm = Algorithm.HMAC256(cfg.secret)

    fun issue(user: UserProfile): String =
        JWT.create()
            .withIssuer(cfg.issuer)
            .withAudience(cfg.audience)
            .withSubject(user.id)
            .withClaim("email", user.email)
            .withClaim("role", user.role.name.lowercase())
            .withExpiresAt(Date(System.currentTimeMillis() + cfg.expiresMinutes * 60_000))
            .sign(algorithm)

    fun verifier(): JWTVerifier =
        JWT.require(algorithm).withIssuer(cfg.issuer).withAudience(cfg.audience).build()
}

/** Installs the "auth-jwt" scheme; wrap protected routes in authenticate("auth-jwt"). */
fun Application.configureAuthentication(jwtService: JwtService) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "ibms"
            verifier(jwtService.verifier())
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
            // A missing/invalid token is rejected here, before any handler or StatusPages
            // runs — emit the unified error envelope so 401s match the contract too.
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope("error", "401", "authentication required", null),
                )
            }
        }
    }
}

fun ApplicationCall.authenticatedUserOrNull(): AuthenticatedUser? {
    val principal = principal<JWTPrincipal>() ?: return null
    val userId = principal.subject ?: return null
    val email = principal.payload.getClaim("email").asString() ?: ""
    val roleStr = principal.payload.getClaim("role").asString() ?: return null
    val role = runCatching { UserRole.valueOf(roleStr.uppercase()) }.getOrNull() ?: return null
    return AuthenticatedUser(userId, email, role)
}

/**
 * Server-side authorization mirroring the API_CONTRACT role matrix. Throws
 * Unauthorized if unauthenticated, Forbidden if the role is not permitted.
 */
fun ApplicationCall.authorize(vararg allowed: UserRole): AuthenticatedUser {
    val user = authenticatedUserOrNull() ?: throw DomainError.Unauthorized("authentication required")
    // SYSADMIN is a global superuser: admitted to every endpoint regardless of the
    // allow-list, so per-route lists below carry only the contract's non-sysadmin roles.
    if (allowed.isNotEmpty() && user.role != UserRole.SYSADMIN && user.role !in allowed) {
        throw DomainError.Forbidden("role ${user.role.name.lowercase()} is not permitted for this action")
    }
    return user
}
