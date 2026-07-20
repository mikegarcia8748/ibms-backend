package com.puregoldbe.ibms.domain.service

import com.puregoldbe.ibms.domain.error.DomainError

/**
 * Rules for a provisioned username. The pattern intentionally mirrors the
 * `users_username_format` CHECK constraint in V6 — the database is the last line
 * of defence, this is the one that produces a readable error for the admin.
 *
 * Usernames are compared case-insensitively (the column is CITEXT), so they are
 * normalised to lowercase on the way in and two accounts cannot differ by case
 * alone.
 */
object UsernamePolicy {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 32

    private val PATTERN = Regex("^[a-zA-Z0-9._-]{$MIN_LENGTH,$MAX_LENGTH}$")

    /** Trim + lowercase, then validate. @throws DomainError.Validation if malformed. */
    fun normalize(raw: String): String {
        val username = raw.trim().lowercase()
        if (!PATTERN.matches(username)) {
            throw DomainError.Validation(
                "username must be $MIN_LENGTH-$MAX_LENGTH characters using only letters, digits, dot, underscore or hyphen",
                code = "invalid_username",
            )
        }
        return username
    }
}
