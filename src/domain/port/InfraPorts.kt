package com.puregoldbe.ibms.domain.port

import kotlinx.datetime.Instant

/**
 * Infrastructure ports the use cases depend on. Implemented in the adapter layer.
 * Keeping them here (domain) is what lets every use case be unit-tested with fakes,
 * no DB / Ktor / network required.
 */

/** Wall clock — a port so tests can inject a fixed time (grace-period logic). */
interface Clock {
    fun now(): Instant
}

/**
 * Atomicity boundary. A use case wraps its body in [inTransaction]; repo calls
 * inside run in one ambient transaction. Adapter uses a blocking Exposed
 * transaction on Dispatchers.IO; the test fake just runs the block.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: () -> T): T
}

/** Blob storage for proof files. Local-disk adapter now; swappable to GCS/S3. */
interface StoragePort {
    /** Persist [bytes] under [key]. */
    fun put(key: String, bytes: ByteArray)

    /** Read the bytes at [key], or null if missing. */
    fun read(key: String): ByteArray?
}

/** Verifies a Google OIDC ID token server-side and returns the identity. */
interface TokenVerifierPort {
    /** @return the verified identity, or null if the token is invalid/expired. */
    fun verify(idToken: String): VerifiedGoogleIdentity?
}

data class VerifiedGoogleIdentity(
    val sub: String,
    val email: String,
    val name: String?,
)
