package com.puregoldbe.ibms.domain.model

import kotlinx.datetime.Instant

/**
 * Authentication state that must never reach the API boundary.
 *
 * Deliberately NOT `@Serializable`: these types carry password and refresh-token
 * hashes, and the compiler refusing to serialize them is a cheap guarantee that
 * no controller can hand one to `call.ok(...)` by accident. Everything the client
 * is allowed to see about a user lives on [UserProfile] instead.
 */
data class UserCredentials(
    val userId: String,
    val username: String,
    /** bcrypt hash; null when no password has been issued yet (login impossible). */
    val passwordHash: String?,
    /** True while [passwordHash] holds a temporary password issued by a sysadmin. */
    val mustChangePassword: Boolean,
    val tempPasswordExpiresAt: Instant?,
    val failedLoginAttempts: Int,
    val lockedUntil: Instant?,
) {
    fun isLockedAt(now: Instant): Boolean = lockedUntil?.let { it > now } == true

    /** A temporary password past its deadline is dead: it can no longer be redeemed. */
    fun isTempPasswordExpiredAt(now: Instant): Boolean =
        mustChangePassword && tempPasswordExpiresAt?.let { it <= now } == true
}

/**
 * One issued refresh token. The token itself is never stored — only its
 * fingerprint — so this row cannot be turned back into a working credential.
 */
data class Session(
    val id: String,
    val userId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
)
