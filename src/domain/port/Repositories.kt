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
    fun findByGoogleSub(googleSub: String): UserProfile?
    fun list(role: UserRole?): List<UserProfile>
    fun countByRole(role: UserRole): Int
    fun create(email: String, name: String, googleSub: String?, role: UserRole): UserProfile
    fun updateRole(id: String, role: UserRole): UserProfile?
    fun updateGoogleSub(id: String, googleSub: String): UserProfile?
}

interface ProviderRepository {
    fun findById(id: String): Provider?
    fun list(status: ProviderStatus?): List<Provider>
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
    fun existsByBranchCode(branchCode: String): Boolean
    fun create(input: StoreUpsertRequest, createdBy: String?): Store
    fun update(id: String, input: StoreUpsertRequest): Store?
    fun close(id: String, reason: String, proofOfClosureId: String, at: Instant): Store?
}

interface AccountRepository {
    fun findById(id: String): Account?
    fun list(storeId: String?, providerId: String?, status: AccountStatus?): List<Account>
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
