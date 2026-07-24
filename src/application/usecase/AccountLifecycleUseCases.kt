package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.TransferRecord
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.domain.port.IdempotencyKeyRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.port.TransferRepository

/**
 * Relocates a circuit to a new store. Transactionally: mark the old account
 * `transferred` (which frees the partial unique index), create a new active account
 * at the new store carrying the same details, and record the transfer. The new
 * account has a distinct id, so it can still be billed in the current period.
 */
class TransferAccountUseCase(
    private val accounts: AccountRepository,
    private val stores: StoreRepository,
    private val transfers: TransferRepository,
    private val attachments: AttachmentRepository,
    private val idempotency: IdempotencyKeyRepository,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        accountId: String,
        newStoreId: String,
        proofId: String,
        actorId: String?,
        idem: IdempotencyContext? = null,
    ): Account = tx.inTransaction {
        idempotent(idempotency, "account.transfer", idem, 201) {
            val actor = actorId ?: throw DomainError.Unauthorized("authentication required")
            val old = accounts.findById(accountId) ?: throw DomainError.NotFound("account $accountId not found")
            if (old.status == AccountStatus.TRANSFERRED) {
                throw DomainError.Conflict("account $accountId has already been transferred")
            }
            stores.findById(newStoreId) ?: throw DomainError.Validation("unknown newStoreId $newStoreId")
            if (!attachments.exists(proofId)) throw DomainError.Validation("a valid transfer proofId is required")

            accounts.updateStatus(old.id, AccountStatus.TRANSFERRED)
            val moved = accounts.create(
                AccountUpsertRequest(
                    accountNumber = old.accountNumber,
                    circuitId = old.circuitId,
                    providerId = old.providerId,
                    storeId = newStoreId,
                    planName = old.planName,
                    serviceType = old.serviceType,
                    speed = old.speed,
                    contractDurationMonths = old.contractDurationMonths,
                    contractStartDate = old.contractStartDate,
                    contractEndDate = old.contractEndDate,
                    notes = old.notes,
                    installationFee = old.installationFee,
                    rate = old.rate,
                    installationDate = old.installationDate,
                    billingPeriodLabel = old.billingPeriodLabel,
                    subscriptionProofIds = old.subscriptionProofIds,
                ),
                createdBy = actor,
            )
            transfers.create(old.storeId, newStoreId, old.id, moved.id, proofId, actor, clock.now())
            activity.record(actor, "account.transferred", "account", moved.id)
            moved
        }
    }
}

/** Read-only transfer history, cursor-paginated, optionally filtered by account. */
class ListTransfersUseCase(
    private val transfers: TransferRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(accountId: String?, cursor: String?, limit: Int): CursorPage<TransferRecord> =
        tx.inTransaction { transfers.page(accountId, cursor, limit) }
}

/** Requests deactivation: status -> termination_requested and start the 30-day grace. */
class DeactivateAccountUseCase(
    private val accounts: AccountRepository,
    private val attachments: AttachmentRepository,
    private val idempotency: IdempotencyKeyRepository,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        accountId: String,
        proofId: String,
        actorId: String?,
        idem: IdempotencyContext? = null,
    ): Account = tx.inTransaction {
        idempotent(idempotency, "account.deactivate", idem, 200) {
            val account = accounts.findById(accountId)
                ?: throw DomainError.NotFound("account $accountId not found")
            if (account.status != AccountStatus.ACTIVE) {
                throw DomainError.Conflict("only active accounts can be deactivated")
            }
            if (!attachments.exists(proofId)) throw DomainError.Validation("a valid deactivation proofId is required")
            val result = accounts.markTerminationRequested(accountId, clock.now())
                ?: throw DomainError.NotFound("account $accountId not found")
            accounts.linkProof(accountId, proofId)
            activity.record(actorId, "account.deactivation_requested", "account", accountId)
            result
        }
    }
}

/** Cancels a pending deactivation: reverts status back to ACTIVE. */
class CancelDeactivationUseCase(
    private val accounts: AccountRepository,
    private val activity: ActivityRecorder,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(accountId: String, reason: String, actorId: String?): Account = tx.inTransaction {
        val account = accounts.findById(accountId)
            ?: throw DomainError.NotFound("account $accountId not found")
        if (account.status != AccountStatus.TERMINATION_REQUESTED) {
            throw DomainError.Conflict("only accounts in termination_requested status can have deactivation cancelled")
        }
        val result = accounts.cancelTerminationRequested(accountId)
            ?: throw DomainError.NotFound("account $accountId not found")
        activity.record(actorId, "account.deactivation_cancelled", "account", accountId)
        result
    }
}
