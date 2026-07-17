package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.model.StoreUpsertRequest
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import kotlinx.serialization.Serializable

class ListStoresUseCase(
    private val stores: StoreRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(status: StoreStatus?, query: String?): List<Store> =
        tx.inTransaction { stores.list(status, query) }
}

class GetStoreUseCase(
    private val stores: StoreRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String): Store =
        tx.inTransaction { stores.findById(id) } ?: throw DomainError.NotFound("store $id not found")
}

/**
 * Creates a store. Business rules: branch_code unique; a valid installation proof
 * is mandatory (the old proofOfInstallationUrl invariant, now an attachment FK).
 */
class CreateStoreUseCase(
    private val stores: StoreRepository,
    private val attachments: AttachmentRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(input: StoreUpsertRequest, actorId: String?): Store = tx.inTransaction {
        if (input.branchCode.isBlank()) throw DomainError.Validation("branchCode is required")
        if (input.name.isBlank()) throw DomainError.Validation("store name is required")
        if (input.proofOfInstallationId.isBlank() || !attachments.exists(input.proofOfInstallationId)) {
            throw DomainError.Validation("a valid proofOfInstallationId is required", "proof_required")
        }
        if (stores.existsByBranchCode(input.branchCode)) {
            throw DomainError.Conflict("branch_code ${input.branchCode} already exists", "duplicate_branch_code")
        }
        stores.create(input, actorId)
    }
}

@Serializable
data class CloseStoreResult(val store: Store, val floatingAccounts: List<Account>)

class CloseStoreUseCase(
    private val stores: StoreRepository,
    private val attachments: AttachmentRepository,
    private val accounts: AccountRepository,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String, reason: String, proofOfClosureId: String): CloseStoreResult =
        tx.inTransaction {
            if (reason.isBlank()) throw DomainError.Validation("closure reason is required")
            if (proofOfClosureId.isBlank() || !attachments.exists(proofOfClosureId)) {
                throw DomainError.Validation("a valid proofOfClosureId is required", "proof_required")
            }
            val closed = stores.close(id, reason, proofOfClosureId, clock.now())
                ?: throw DomainError.NotFound("store $id not found")
            CloseStoreResult(closed, accounts.listActiveByStore(id))
        }
}

class GetFloatingAccountsUseCase(
    private val accounts: AccountRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(): List<Account> = tx.inTransaction { accounts.listFloating() }
}
