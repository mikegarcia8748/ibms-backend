package com.puregoldbe.ibms.domain.service

import com.puregoldbe.ibms.domain.error.DomainError

/**
 * The rules a user-chosen password must satisfy. Pure logic with no I/O so it can
 * be exercised directly, and the single place the rules are stated — both the
 * forced first-login change and self-service rotation route through [validate].
 *
 * Length does more for strength than character-class rules do, hence a 12-char
 * floor rather than a shorter password with more mandatory symbol classes. The
 * upper bound exists because bcrypt silently truncates input past 72 bytes: a
 * longer password would give a false sense of added strength.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 72

    /** @throws DomainError.Validation with a message the UI can show verbatim. */
    fun validate(password: String, username: String) {
        val failures = buildList {
            if (password.length < MIN_LENGTH) add("be at least $MIN_LENGTH characters")
            if (password.length > MAX_LENGTH) add("be at most $MAX_LENGTH characters")
            if (password.none { it.isUpperCase() }) add("contain an uppercase letter")
            if (password.none { it.isLowerCase() }) add("contain a lowercase letter")
            if (password.none { it.isDigit() }) add("contain a digit")
            if (password.any { it.isWhitespace() }) add("not contain spaces")
            if (username.isNotBlank() && password.contains(username, ignoreCase = true)) {
                add("not contain your username")
            }
        }
        if (failures.isNotEmpty()) {
            throw DomainError.Validation(
                "password must ${failures.joinToString(", ")}",
                code = "weak_password",
            )
        }
    }
}
