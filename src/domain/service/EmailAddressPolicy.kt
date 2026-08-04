package com.puregoldbe.ibms.domain.service

import com.puregoldbe.ibms.domain.error.DomainError

/**
 * Rules for a user's notification email address.
 *
 * Deliberately permissive — the only thing this address is used for is addressing
 * outbound notification mail, so the useful check is "plausibly an address" rather
 * than RFC 5322 conformance, which would reject valid corporate addresses and still
 * not prove deliverability. The real verification is whether mail arrives.
 *
 * Addresses are **not** unique per user: V7 dropped the `users_email_key` constraint,
 * and shared team mailboxes are legitimate. Recipient resolution de-duplicates by
 * address, so a mailbox two users share still receives one copy.
 */
object EmailAddressPolicy {
    const val MAX_LENGTH = 254

    private val PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

    /**
     * Trim + lowercase, then validate. A blank input normalises to `null`, which is how
     * an address is cleared. @throws DomainError.Validation if malformed.
     */
    fun normalizeOrNull(raw: String?): String? {
        val email = raw?.trim()?.lowercase()
        if (email.isNullOrEmpty()) return null
        if (email.length > MAX_LENGTH || !PATTERN.matches(email)) {
            throw DomainError.Validation(
                "'$raw' is not a valid email address",
                code = "invalid_email",
            )
        }
        return email
    }
}
