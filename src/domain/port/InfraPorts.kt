package com.puregoldbe.ibms.domain.port

import com.puregoldbe.ibms.domain.model.UserProfile
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

/** The two operations a presigned URL can authorize. */
enum class PresignOp { UPLOAD, DOWNLOAD }

/**
 * Issues and verifies short-lived signed URLs for direct blob upload/download.
 * The local adapter signs URLs back at this backend; the same seam can later front
 * a real S3/GCS presigner without changing callers.
 */
interface PresignPort {
    /** A full signed URL (with token + expiry) the client uses to PUT/GET the blob. */
    fun presignedUrl(attachmentId: String, op: PresignOp): String

    /** Validate a token for (attachmentId, op); false if tampered with or expired. */
    fun isValid(attachmentId: String, op: PresignOp, token: String): Boolean
}

/**
 * One-way password hashing. A port so use cases never name a specific algorithm
 * and specs can substitute a trivial hasher — bcrypt at production cost factors
 * takes ~100ms per call by design, which would dominate a test suite.
 */
interface PasswordHasher {
    fun hash(raw: String): String

    /** Constant-time comparison of [raw] against a stored [hash]; false if [hash] is malformed. */
    fun verify(raw: String, hash: String): Boolean
}

/**
 * Generation and fingerprinting of the secrets auth hands out. Fingerprinting
 * lives here rather than on [PasswordHasher] because refresh tokens are already
 * high-entropy: they want a fast one-way digest for lookup, not a deliberately
 * slow password hash.
 */
interface SecretGenerator {
    /**
     * A temporary password an admin can read aloud or paste into a message —
     * high entropy, but drawn from an alphabet without look-alike characters.
     */
    fun temporaryPassword(): String

    /** An opaque, URL-safe, high-entropy refresh token. */
    fun refreshToken(): String

    /** Stable one-way digest of [token], the only form stored at rest. */
    fun fingerprint(token: String): String
}

/**
 * Mints the bearer tokens the API accepts. A port so the auth use cases stay free
 * of JWT types, and so token lifetimes are declared in one place rather than
 * recomputed by every caller that has to report `expiresInSeconds`.
 */
interface AuthTokenIssuer {
    /** Access token for an established session; [sessionId] rides along as the `sid` claim. */
    fun accessToken(user: UserProfile, sessionId: String): String

    /**
     * Single-purpose token authorizing only `POST /auth/password/change`. Must be
     * rejected by every other authenticated route.
     */
    fun passwordChangeChallenge(userId: String): String

    val accessTtlSeconds: Long
    val challengeTtlSeconds: Long
}
