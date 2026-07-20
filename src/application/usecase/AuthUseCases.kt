package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.LoginOutcome
import com.puregoldbe.ibms.domain.model.LoginResponse
import com.puregoldbe.ibms.domain.model.PasswordChangeChallenge
import com.puregoldbe.ibms.domain.model.SessionTokens
import com.puregoldbe.ibms.domain.model.UserCredentials
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.port.AuthTokenIssuer
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.PasswordHasher
import com.puregoldbe.ibms.domain.port.SecretGenerator
import com.puregoldbe.ibms.domain.port.SessionRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.port.UserRepository
import com.puregoldbe.ibms.domain.service.PasswordPolicy
import com.puregoldbe.ibms.domain.service.SessionPolicy
import kotlinx.datetime.Instant

/**
 * Authentication for sysadmin-provisioned accounts.
 *
 * The flow has three steps, and the middle one is the reason this is not a plain
 * login endpoint:
 *
 *  1. A sysadmin provisions the account (see `ProvisionUserUseCase`) and hands
 *     over a system-generated temporary password out-of-band.
 *  2. [LoginUseCase] accepts that temporary password but **refuses to start a
 *     session**. It returns a change-password challenge instead — a token good
 *     for exactly one call to [CompleteFirstLoginUseCase].
 *  3. Only when the holder sets their own password does a session row appear and
 *     an access/refresh pair get minted.
 *
 * So possession of a temporary password never yields API access on its own.
 */

/** Per-request client details recorded on the session row for audit. */
data class ClientContext(val userAgent: String?, val ipAddress: String?)

/**
 * Creates the session row and the token pair. Shared by every path that ends in
 * "the user is now logged in", so the tokens issued by login, first-login
 * password change, self-service rotation and refresh are identical in shape.
 */
class SessionIssuer(
    private val sessions: SessionRepository,
    private val secrets: SecretGenerator,
    private val tokens: AuthTokenIssuer,
    private val policy: SessionPolicy,
) {
    /** Must run inside the caller's transaction — the row and the tokens are one unit. */
    fun start(user: UserProfile, ctx: ClientContext, now: Instant): SessionTokens {
        val refreshToken = secrets.refreshToken()
        val session = sessions.create(
            userId = user.id,
            refreshTokenHash = secrets.fingerprint(refreshToken),
            issuedAt = now,
            expiresAt = now + policy.refreshTtl,
            userAgent = ctx.userAgent,
            ipAddress = ctx.ipAddress,
        )
        return SessionTokens(
            accessToken = tokens.accessToken(user, session.id),
            refreshToken = refreshToken,
            tokenType = "Bearer",
            expiresInSeconds = tokens.accessTtlSeconds,
        )
    }
}

/**
 * Step 2 of the flow: verify username + password.
 *
 * Every rejection surfaces the same message and status. Distinguishing "no such
 * user" from "wrong password" would turn this endpoint into a username oracle,
 * and the response must not reveal whether an account exists to someone who
 * cannot already log in.
 */
class LoginUseCase(
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val tokens: AuthTokenIssuer,
    private val sessionIssuer: SessionIssuer,
    private val policy: SessionPolicy,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    /**
     * A real bcrypt hash to verify against when the account does not exist, so a
     * miss costs the same wall-clock time as a wrong password. Without it, a fast
     * 401 reliably means "no such username". Computed once, lazily, because
     * hashing at production cost is deliberately slow.
     */
    private val decoyHash: String by lazy { hasher.hash("decoy-password-never-matches") }

    suspend operator fun invoke(username: String, password: String, ctx: ClientContext): LoginResponse =
        tx.inTransaction {
            val now = clock.now()
            val credentials = users.credentialsByUsername(username.trim().lowercase())

            if (credentials?.passwordHash == null) {
                hasher.verify(password, decoyHash)
                throw invalidCredentials()
            }
            if (credentials.isLockedAt(now)) throw accountLocked()

            if (!hasher.verify(password, credentials.passwordHash)) {
                registerFailedAttempt(credentials, now)
                throw invalidCredentials()
            }

            // A temporary password past its deadline is treated as no longer valid
            // even though it hashed correctly — it has to be reissued by a sysadmin.
            if (credentials.isTempPasswordExpiredAt(now)) {
                throw DomainError.Unauthorized(
                    "your temporary password has expired — ask a sysadmin to issue a new one",
                    code = "temp_password_expired",
                )
            }

            users.clearLoginFailures(credentials.userId)
            val user = users.findById(credentials.userId)
                ?: throw DomainError.Unauthorized("account is unavailable", code = "account_unavailable")

            if (credentials.mustChangePassword) {
                LoginResponse(
                    outcome = LoginOutcome.PASSWORD_CHANGE_REQUIRED,
                    user = user,
                    session = null,
                    passwordChange = PasswordChangeChallenge(
                        challengeToken = tokens.passwordChangeChallenge(user.id),
                        expiresInSeconds = tokens.challengeTtlSeconds,
                        reason = "temporary_password",
                    ),
                )
            } else {
                LoginResponse(
                    outcome = LoginOutcome.AUTHENTICATED,
                    user = user,
                    session = sessionIssuer.start(user, ctx, now),
                    passwordChange = null,
                )
            }
        }

    private fun registerFailedAttempt(credentials: UserCredentials, now: Instant) {
        val attempts = credentials.failedLoginAttempts + 1
        val lockedUntil = if (attempts >= policy.maxFailedLogins) now + policy.lockoutDuration else null
        users.recordFailedLogin(credentials.userId, attempts, lockedUntil)
    }

    private fun invalidCredentials() =
        DomainError.Unauthorized("invalid username or password", code = "invalid_credentials")

    private fun accountLocked() = DomainError.Forbidden(
        "too many failed attempts — this account is locked for ${policy.lockoutDuration.inWholeMinutes} minutes",
        code = "account_locked",
    )
}

/**
 * Step 3: redeem a change-password challenge. This is the moment the user becomes
 * authenticated, so it is also the first moment a session exists — the returned
 * response carries the tokens, and the caller never has to log in a second time.
 */
class CompleteFirstLoginUseCase(
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val hasher: PasswordHasher,
    private val sessionIssuer: SessionIssuer,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(userId: String, newPassword: String, ctx: ClientContext): LoginResponse =
        tx.inTransaction {
            val now = clock.now()
            val credentials = users.credentialsById(userId)
                ?: throw DomainError.Unauthorized("account is unavailable", code = "account_unavailable")

            // The challenge is only meaningful while a temporary password is live.
            // Replaying one after the fact must not let anyone reset the password.
            if (!credentials.mustChangePassword) {
                throw DomainError.Conflict("password has already been set", code = "password_already_set")
            }
            if (credentials.isTempPasswordExpiredAt(now)) {
                throw DomainError.Unauthorized(
                    "your temporary password has expired — ask a sysadmin to issue a new one",
                    code = "temp_password_expired",
                )
            }

            val user = applyNewPassword(users, sessions, hasher, credentials, newPassword, now)
            LoginResponse(
                outcome = LoginOutcome.AUTHENTICATED,
                user = user,
                session = sessionIssuer.start(user, ctx, now),
                passwordChange = null,
            )
        }
}

/**
 * Self-service rotation by an already-authenticated user. Requires the current
 * password: an unattended session should not be enough to lock the real owner out.
 */
class ChangeOwnPasswordUseCase(
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val hasher: PasswordHasher,
    private val sessionIssuer: SessionIssuer,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        userId: String,
        currentPassword: String,
        newPassword: String,
        ctx: ClientContext,
    ): LoginResponse = tx.inTransaction {
        val now = clock.now()
        val credentials = users.credentialsById(userId)
            ?: throw DomainError.Unauthorized("account is unavailable", code = "account_unavailable")
        if (credentials.passwordHash == null || !hasher.verify(currentPassword, credentials.passwordHash)) {
            throw DomainError.Unauthorized("current password is incorrect", code = "invalid_credentials")
        }

        val user = applyNewPassword(users, sessions, hasher, credentials, newPassword, now)
        LoginResponse(
            outcome = LoginOutcome.AUTHENTICATED,
            user = user,
            session = sessionIssuer.start(user, ctx, now),
            passwordChange = null,
        )
    }
}

/**
 * Validate, hash and store a new password, then drop every existing session.
 *
 * Revoking on change is the point: whoever knew the old password — including the
 * sysadmin who issued the temporary one — loses any session they had. The caller
 * immediately gets a fresh one, so the user is not signed out of the device they
 * are holding.
 */
private fun applyNewPassword(
    users: UserRepository,
    sessions: SessionRepository,
    hasher: PasswordHasher,
    credentials: UserCredentials,
    newPassword: String,
    now: Instant,
): UserProfile {
    PasswordPolicy.validate(newPassword, credentials.username)
    if (credentials.passwordHash != null && hasher.verify(newPassword, credentials.passwordHash)) {
        throw DomainError.Validation(
            "new password must differ from your current one",
            code = "password_reused",
        )
    }
    val user = users.setPassword(
        id = credentials.userId,
        passwordHash = hasher.hash(newPassword),
        mustChangePassword = false,
        tempPasswordExpiresAt = null,
        at = now,
    ) ?: throw DomainError.NotFound("user ${credentials.userId} not found")
    sessions.revokeAllForUser(credentials.userId, now)
    return user
}

/**
 * Exchange a refresh token for a new pair. Rotation is unconditional: the
 * presented token is revoked here, so a token that leaks is usable at most once
 * and the legitimate client's next refresh fails loudly instead of silently
 * sharing a session.
 */
class RefreshSessionUseCase(
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val secrets: SecretGenerator,
    private val sessionIssuer: SessionIssuer,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(refreshToken: String, ctx: ClientContext): LoginResponse = tx.inTransaction {
        val now = clock.now()
        val session = sessions.findLiveByHash(secrets.fingerprint(refreshToken), now)
            ?: throw DomainError.Unauthorized("refresh token is invalid or expired", code = "invalid_refresh_token")

        val credentials = users.credentialsById(session.userId)
        // A password reset since this token was issued forces a fresh login.
        if (credentials == null || credentials.mustChangePassword) {
            sessions.revoke(session.id, now)
            throw DomainError.Unauthorized("please sign in again", code = "reauthentication_required")
        }
        val user = users.findById(session.userId)
            ?: throw DomainError.Unauthorized("account is unavailable", code = "account_unavailable")

        sessions.revoke(session.id, now)
        LoginResponse(
            outcome = LoginOutcome.AUTHENTICATED,
            user = user,
            session = sessionIssuer.start(user, ctx, now),
            passwordChange = null,
        )
    }
}

/** End the caller's own session. Idempotent — revoking twice is not an error. */
class LogoutUseCase(
    private val sessions: SessionRepository,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(sessionId: String) = tx.inTransaction {
        sessions.revoke(sessionId, clock.now())
        Unit
    }
}

/** Revoke every session for the caller — the "sign out everywhere" control. */
class LogoutEverywhereUseCase(
    private val sessions: SessionRepository,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(userId: String): Int = tx.inTransaction {
        sessions.revokeAllForUser(userId, clock.now())
    }
}

/** Resolve the current user's profile from the JWT subject. */
class GetCurrentUserUseCase(
    private val users: UserRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(userId: String): UserProfile = tx.inTransaction {
        users.findById(userId) ?: throw DomainError.NotFound("user $userId not found")
    }
}
