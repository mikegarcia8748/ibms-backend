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
    fun findByUsername(username: String): UserProfile?
    fun list(role: UserRole?, status: UserStatus?): List<UserProfile>
    fun page(role: UserRole?, status: UserStatus?, cursor: String?, limit: Int): CursorPage<UserProfile>
    fun countByRole(role: UserRole): Int
    fun existsByUsername(username: String): Boolean

    /** Insert a provisioned account carrying its temporary password hash. */
    fun create(input: ProvisionUserRequest, passwordHash: String, tempPasswordExpiresAt: Instant, at: Instant): UserProfile

    fun updateRole(id: String, role: UserRole): UserProfile?
    fun updateStatus(id: String, status: UserStatus): UserProfile?

    /** Set or clear the notification delivery address; [email] must already be normalised. */
    fun updateEmail(id: String, email: String?): UserProfile?

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
    fun findByName(name: String): Provider?
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

    /** Bulk lookup for the proof-list endpoints; order is unspecified. */
    fun findAllById(ids: List<String>): List<Attachment>

    fun exists(id: String): Boolean
    fun create(
        purpose: AttachmentPurpose,
        entityType: String?,
        entityId: String?,
        storageKey: String,
        contentType: String?,
        sizeBytes: Long?,
        uploadedBy: String?,
        fileName: String? = null,
    ): Attachment

    /** Stamp the row once bytes are actually stored: records the true size + content type. */
    fun markUploaded(id: String, sizeBytes: Long, contentType: String)

    /**
     * Stamp the owning entity once the activity claiming this file commits. A
     * denormalized hint for `idx_attachments_entity` and download scoping — the
     * authoritative link is `account_attachments` (see [AccountRepository.linkProofs]).
     */
    fun linkEntity(id: String, entityType: String, entityId: String)
}

interface StoreRepository {
    fun findById(id: String): Store?
    fun findByBranchCode(branchCode: String): Store?
    fun list(status: StoreStatus?, query: String?): List<Store>
    fun page(status: StoreStatus?, query: String?, cursor: String?, limit: Int): CursorPage<Store>
    fun existsByBranchCode(branchCode: String): Boolean
    fun create(input: StoreUpsertRequest, createdBy: String?): Store
    fun update(id: String, input: StoreUpsertRequest): Store?
    fun close(id: String, reason: String, proofOfClosureId: String, at: Instant): Store?
}

/** Scalar account aggregation (row count + summed MRC) for the manager dashboard. */
data class AccountAggregate(val accountCount: Int, val totalMrc: Money)

interface AccountRepository {
    fun findById(id: String): Account?
    fun list(storeId: String?, providerId: String?, status: AccountStatus?): List<Account>
    fun page(storeId: String?, providerId: String?, status: AccountStatus?, cursor: String?, limit: Int): CursorPage<Account>
    /**
     * True if a LIVE account (not transferred/inactive) already exists with this
     * (store, provider, account number, circuit) identity. A null/blank [circuitId]
     * matches existing accounts whose circuit is null or blank — mirroring the DB's
     * COALESCE(circuit_id,'') unique index, so no-circuit accounts still dedupe. Scoping
     * by store lets the same account number recur across stores as distinct accounts.
     */
    fun existsByIdentity(storeId: String, providerId: String, accountNumber: String, circuitId: String?): Boolean
    fun create(input: AccountUpsertRequest, createdBy: String?): Account
    fun update(id: String, input: AccountUpsertRequest): Account?

    /** Active accounts on a given store (used when closing a store -> floating). */
    fun listActiveByStore(storeId: String): List<Account>

    /** Active accounts whose store is closed/inactive (the global floating view). */
    fun listFloating(): List<Account>

    /** Denormalized accounts joined with store/provider names for the Excel export. */
    fun listForExport(providerId: String?, status: AccountStatus?): List<AccountExportRow>

    /** Account counts grouped by status (dashboard status breakdown). */
    fun countByStatus(): Map<AccountStatus, Int>

    /** Row count + summed MRC (rate) for [status], or all accounts when null. */
    fun aggregate(status: AccountStatus?): AccountAggregate

    /** Per-provider (ISP) count + summed MRC for [status], or all when null. */
    fun aggregateByProvider(status: AccountStatus?): List<ProviderAccountSummary>

    /** Denormalized, keyset-paginated accounts joined with store/provider names. */
    fun pageWithDetails(
        storeId: String?,
        providerId: String?,
        status: AccountStatus?,
        cursor: String?,
        limit: Int,
    ): CursorPage<AccountListItem>

    fun updateStatus(id: String, status: AccountStatus): Account?

    /** Start the 30-day grace window: status -> termination_requested, timestamp set. */
    fun markTerminationRequested(id: String, at: Instant): Account?

    /**
     * Attach [attachmentIds] as the proofs of ONE activity: they share a `linked_at`
     * (the transaction timestamp), carry their request order in `sort_order`, and are
     * tagged [purpose] so a deactivation proof can never be read back as a
     * subscription proof. [transferId] is set only for TRANSFER_PROOF. Already-linked
     * pairs are ignored rather than failing.
     */
    fun linkProofs(
        accountId: String,
        attachmentIds: List<String>,
        purpose: AttachmentPurpose,
        linkedBy: String?,
        transferId: String? = null,
    )

    /** An account's proof links, newest activity first ([purpose] filters when non-null). */
    fun listProofs(accountId: String, purpose: AttachmentPurpose? = null): List<AccountProofLink>

    /** The links of one transfer, across both the source and destination account. */
    fun listProofsByTransfer(transferId: String): List<AccountProofLink>

    /** Revert deactivation: status -> ACTIVE, clear terminationRequestedAt. */
    fun cancelTerminationRequested(id: String): Account?

    /** Find accounts whose grace period has expired (DB-side filtering). */
    fun findExpiredGrace(before: Instant): List<Account>
}

interface AccountChangeRequestRepository {
    fun findById(id: String): AccountChangeRequest?
    fun findPendingByAccountId(accountId: String): AccountChangeRequest?
    fun page(
        accountId: String? = null,
        submittedById: String? = null,
        status: AccountChangeRequestStatus? = null,
        cursor: String? = null,
        limit: Int,
    ): CursorPage<AccountChangeRequest>
    fun create(accountId: String, submittedById: String, input: SubmitAccountChangeRequestInput): AccountChangeRequest
    fun approve(id: String, approverId: String, at: Instant): AccountChangeRequest?
    fun reject(id: String, reason: String, at: Instant): AccountChangeRequest?
    fun cancel(id: String, at: Instant): AccountChangeRequest?
}
