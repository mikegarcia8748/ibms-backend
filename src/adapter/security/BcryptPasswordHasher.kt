package com.puregoldbe.ibms.adapter.security

import at.favre.lib.crypto.bcrypt.BCrypt
import com.puregoldbe.ibms.domain.port.PasswordHasher

/**
 * bcrypt password hashing.
 *
 * The cost factor is configurable because it is a moving target: it should be
 * raised as hardware gets faster, and lowered in tests where ~100ms per hash
 * would otherwise dominate the suite. Each hash embeds its own cost, so raising
 * it later does not invalidate existing hashes — they keep verifying at the cost
 * they were written with, and get upgraded the next time the user changes password.
 */
class BcryptPasswordHasher(private val cost: Int = DEFAULT_COST) : PasswordHasher {

    init {
        require(cost in MIN_COST..MAX_COST) { "bcrypt cost must be between $MIN_COST and $MAX_COST, was $cost" }
    }

    override fun hash(raw: String): String =
        BCrypt.withDefaults().hashToString(cost, raw.toCharArray())

    /**
     * Returns false rather than throwing on a malformed hash: a corrupt or
     * legacy value in the column is an authentication failure, not a 500.
     */
    override fun verify(raw: String, hash: String): Boolean =
        runCatching { BCrypt.verifyer().verify(raw.toCharArray(), hash).verified }.getOrDefault(false)

    companion object {
        const val DEFAULT_COST = 12
        const val MIN_COST = 4
        const val MAX_COST = 31
    }
}
