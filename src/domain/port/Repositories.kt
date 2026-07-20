package com.puregoldbe.ibms.domain.port

import com.puregoldbe.ibms.domain.model.*
import kotlinx.datetime.Instant

/**
 * Repository ports (interfaces). Adapter implementations use Exposed and assume an
 * ambient transaction opened by [TransactionRunner]. Methods are non-suspend and
 * do blocking DSL work; the runner puts them on Dispatchers.IO.
 */

interface UserRepository {
    fun findById(id: String): UserProfile?
    fun findByEmail(email: String): UserProfile?
    fun findByUsername(username: String): UserProfile?
    fun list(role: UserRole?): List<UserProfile>
    fun page(role: UserRole?, cursor: String?, limit: Int): CursorPage<UserProfile>
    fun countByRole(role: UserRole): Int
    fun existsByUsername(username: String): Boolean
    fun existsByEmail(email: String): Boolean

    /** Insert a provisioned account carrying its temporary password hash. */
    fun create(input: ProvisionUserRequest, passwordHash: String, tempPasswordExpiresAt: Instant, at: Instant): UserProfile

    fun updateRole(id: String, role: UserRole): UserProfile?

    // --- credential access (hashes; never expose these through a controller) ---
    fun credentialsByUsername(username: String): UserCredentials?
    fun credentialsById(id: String): UserCredentials?

    /**
     * Replace the stored hash. [tempPasswordExpiresAt] is non-null only when
     * setting a temporary password, which is also the only case where
     * [mustChangePassword] is true. Clears any accumulated lockout.
     */
    fun setPassword(
        id: String,
        passwordHash: String,
        mustChangePassword: Boolean,
        tempPasswordExpiresAt: Instant?,
        at: Instant,
    ): UserProfile?

    /** Record a rejected login; [lockedUntil] non-null once the threshold is crossed. */
    fun recordFailedLogin(id: String, attempts: Int, lockedUntil: Instant?)

    /** Reset the failure counter and lockout after a successful login. */
    fun clearLoginFailures(id: String)
}

/**
 * Server-side sessions backing refresh-token rotation. Tokens are looked up by
 * fingerprint, so this port never sees a usable credential.
 */
interface SessionRepository {
    fun create(
        userId: String,
        refreshTokenHash: String,
        issuedAt: Instant,
        expiresAt: Instant,
        userAgent: String?,
        ipAddress: String?,
    ): Session

    /** Find a session by token fingerprint, excluding revoked and expired rows. */
    fun findLiveByHash(refreshTokenHash: String, now: Instant): Session?

    fun findById(id: String): Session?

    fun revoke(id: String, at: Instant): Boolean

    /** Revoke every live session for a user (logout-everywhere, password reset). */
    fun revokeAllForUser(userId: String, at: Instant): Int

    fun touch(id: String, at: Instant)
}

interface ProviderRepository {
    fun findById(id: String): Provider?
    fun list(status: ProviderStatus?): List<Provider>
    fun page(status: ProviderStatus?, cursor: String?, limit: Int): CursorPage<Provider>
    fun create(name: String, paymentScheduleDay: Int): Provider
    fun updateDetails(id: String, name: String?, paymentScheduleDay: Int?): Provider?
    fun deactivate(id: String, at: Instant): Provider?
}

interface InvoiceSequenceRepository {
    /** Insert the per-provider counter row (idempotent). */
    fun seed(providerId: String, prefix: String)

    /** Atomic UPDATE ... SET current_value = current_value + 1 RETURNING. */
    fun nextValue(providerId: String): Int

    fun prefixOf(providerId: String): String?
}

interface AttachmentRepository {
    fun findById(id: String): Attachment?
    fun exists(id: String): Boolean
    fun create(
        purpose: AttachmentPurpose,
        entityType: String?,
        entityId: String?,
        storageKey: String,
        contentType: String?,
        sizeBytes: Long?,
        uploadedBy: String?,
    ): Attachment
}

interface StoreRepository {
    fun findById(id: String): Store?
    fun list(status: StoreStatus?, query: String?): List<Store>
    fun page(status: StoreStatus?, query: String?, cursor: String?, limit: Int): CursorPage<Store>
    fun existsByBranchCode(branchCode: String): Boolean
    fun create(input: StoreUpsertRequest, createdBy: String?): Store
    fun update(id: String, input: StoreUpsertRequest): Store?
    fun close(id: String, reason: String, proofOfClosureId: String, at: Instant): Store?
}

interface AccountRepository {
    fun findById(id: String): Account?
    fun list(storeId: String?, providerId: String?, status: AccountStatus?): List<Account>
    fun page(storeId: String?, providerId: String?, status: AccountStatus?, cursor: String?, limit: Int): CursorPage<Account>
    fun existsByProviderAndNumber(providerId: String, accountNumber: String): Boolean
    fun create(input: AccountUpsertRequest, createdBy: String?): Account
    fun update(id: String, input: AccountUpsertRequest): Account?

    /** Active accounts on a given store (used when closing a store -> floating). */
    fun listActiveByStore(storeId: String): List<Account>

    /** Active accounts whose store is closed/inactive (the global floating view). */
    fun listFloating(): List<Account>

    fun updateStatus(id: String, status: AccountStatus): Account?

    /** Start the 30-day grace window: status -> termination_requested, timestamp set. */
    fun markTerminationRequested(id: String, at: Instant): Account?
}
