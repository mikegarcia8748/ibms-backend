package com.puregoldbe.ibms.adapter.security

import com.puregoldbe.ibms.domain.port.SecretGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Secret generation backed by [SecureRandom]. One instance is shared: SecureRandom
 * is thread-safe and seeding a fresh one per call is both slower and no stronger.
 */
class SecureRandomSecrets : SecretGenerator {

    private val random = SecureRandom()

    /**
     * A temporary password an admin has to relay by hand, so the alphabet omits
     * characters that are misread when dictated or retyped — no O/0, no I/l/1.
     * Grouping into blocks of four makes it readable aloud without lowering
     * entropy (the separators are fixed, not random).
     *
     * 16 characters over a 56-symbol alphabet is ~93 bits, far past anything a
     * 72-hour redemption window puts at risk.
     */
    override fun temporaryPassword(): String {
        // Guarantee one of each class so the value is accepted by systems (and
        // humans) expecting mixed case and a digit, then shuffle so the classes
        // are not pinned to fixed positions.
        val chars = buildList {
            add(UPPERCASE.random())
            add(LOWERCASE.random())
            add(DIGITS.random())
            repeat(TEMP_PASSWORD_LENGTH - 3) { add(ALPHABET.random()) }
        }.shuffled(random)

        return chars.chunked(4) { it.joinToString("") }.joinToString("-")
    }

    /** 256 bits of entropy, URL-safe and unpadded so it survives any transport. */
    override fun refreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * SHA-256, not bcrypt. Refresh tokens are already full-entropy random values,
     * so there is nothing for a slow hash to protect against — and lookup by
     * fingerprint has to be a plain indexed equality match.
     */
    override fun fingerprint(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun String.random(): Char = this[random.nextInt(length)]

    private companion object {
        const val TEMP_PASSWORD_LENGTH = 16
        const val REFRESH_TOKEN_BYTES = 32

        const val UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ"  // no I, O
        const val LOWERCASE = "abcdefghijkmnopqrstuvwxyz" // no l
        const val DIGITS = "23456789"                     // no 0, 1
        const val ALPHABET = UPPERCASE + LOWERCASE + DIGITS
    }
}
