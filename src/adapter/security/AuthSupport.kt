package com.puregoldbe.ibms.adapter.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.ErrorEnvelope
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.port.AuthTokenIssuer
import com.puregoldbe.ibms.infrastructure.config.JwtConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import java.util.Date
import kotlin.time.Duration

/** The authenticated caller, resolved from the backend JWT's claims. */
data class AuthenticatedUser(
    val userId: String,
    val username: String,
    val role: UserRole,
    /** The session this token belongs to — what logout revokes. */
    val sessionId: String,
)

/**
 * Issues and verifies the backend JWTs (HMAC256).
 *
 * Two kinds of token are signed with the same key, told apart by the `typ`
 * claim. That claim is not decoration: a password-change challenge is handed to
 * someone who has proven only that they hold a temporary password, so it must
 * never be accepted as a session token. Each Ktor auth provider below pins the
 * exact `typ` it will admit, and a token of the wrong kind fails authentication
 * rather than falling through to an authorization check.
 */
class JwtService(
    private val cfg: JwtConfig,
    private val challengeTtl: Duration,
) : AuthTokenIssuer {

    private val algorithm = Algorithm.HMAC256(cfg.secret)

    override val accessTtlSeconds: Long = cfg.expiresMinutes * 60
    override val challengeTtlSeconds: Long = challengeTtl.inWholeSeconds

    override fun accessToken(user: UserProfile, sessionId: String): String =
        base(user.id, TYPE_ACCESS, accessTtlSeconds)
            .withClaim(CLAIM_SESSION_ID, sessionId)
            .withClaim("username", user.username)
            .withClaim("role", user.role.name.lowercase())
            .sign(algorithm)

    /**
     * Carries no role and no session id — there is deliberately nothing on this
     * token that an authorization check could act on.
     */
    override fun passwordChangeChallenge(userId: String): String =
        base(userId, TYPE_PASSWORD_CHANGE, challengeTtlSeconds).sign(algorithm)

    fun verifier(): JWTVerifier =
        JWT.require(algorithm).withIssuer(cfg.issuer).withAudience(cfg.audience).build()

    private fun base(subject: String, type: String, ttlSeconds: Long) =
        JWT.create()
            .withIssuer(cfg.issuer)
            .withAudience(cfg.audience)
            .withSubject(subject)
            .withClaim(CLAIM_TYPE, type)
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + ttlSeconds * 1_000))

    companion object {
        const val CLAIM_TYPE = "typ"
        const val CLAIM_SESSION_ID = "sid"
        const val TYPE_ACCESS = "access"
        const val TYPE_PASSWORD_CHANGE = "pwd_change"
    }
}

/** Name of the provider guarding ordinary API routes. */
const val AUTH_SESSION = "auth-jwt"

/** Name of the provider guarding only `POST /auth/password/change`. */
const val AUTH_PASSWORD_CHANGE = "auth-pwd-change"

/**
 * Installs both bearer schemes. Wrap ordinary routes in `authenticate(AUTH_SESSION)`;
 * the forced password change uses `authenticate(AUTH_PASSWORD_CHANGE)`.
 */
fun Application.configureAuthentication(jwtService: JwtService) {
    install(Authentication) {
        jwt(AUTH_SESSION) {
            realm = "ibms"
            verifier(jwtService.verifier())
            validate { credential ->
                val payload = credential.payload
                val isAccessToken = payload.getClaim(JwtService.CLAIM_TYPE).asString() == JwtService.TYPE_ACCESS
                val hasSession = !payload.getClaim(JwtService.CLAIM_SESSION_ID).asString().isNullOrBlank()
                if (payload.subject != null && isAccessToken && hasSession) JWTPrincipal(payload) else null
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

        jwt(AUTH_PASSWORD_CHANGE) {
            realm = "ibms-password-change"
            verifier(jwtService.verifier())
            validate { credential ->
                val payload = credential.payload
                val isChallenge =
                    payload.getClaim(JwtService.CLAIM_TYPE).asString() == JwtService.TYPE_PASSWORD_CHANGE
                if (payload.subject != null && isChallenge) JWTPrincipal(payload) else null
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope("error", "401", "a valid password-change challenge is required", null),
                )
            }
        }
    }
}

fun ApplicationCall.authenticatedUserOrNull(): AuthenticatedUser? {
    val principal = principal<JWTPrincipal>() ?: return null
    val userId = principal.subject ?: return null
    val sessionId = principal.payload.getClaim(JwtService.CLAIM_SESSION_ID).asString() ?: return null
    val roleStr = principal.payload.getClaim("role").asString() ?: return null
    val role = runCatching { UserRole.valueOf(roleStr.uppercase()) }.getOrNull() ?: return null
    return AuthenticatedUser(
        userId = userId,
        username = principal.payload.getClaim("username").asString() ?: "",
        role = role,
        sessionId = sessionId,
    )
}

/**
 * The user id carried by a password-change challenge. The provider has already
 * pinned `typ`, so reaching here means the caller holds a genuine challenge.
 */
fun ApplicationCall.passwordChangeSubject(): String =
    principal<JWTPrincipal>()?.subject
        ?: throw DomainError.Unauthorized("a valid password-change challenge is required")

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
