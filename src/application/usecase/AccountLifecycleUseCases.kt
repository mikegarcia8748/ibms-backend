package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.DeepLinks
import com.puregoldbe.ibms.domain.model.TransferRecord
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.domain.port.IdempotencyKeyRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.port.TransferRepository
import com.puregoldbe.ibms.domain.service.PdfProofPolicy

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
    private val notifications: NotificationEnqueuer,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        accountId: String,
        newStoreId: String,
        proofIds: List<String>,
        actorId: String?,
        idem: IdempotencyContext? = null,
    ): Account = tx.inTransaction {
        idempotent(idempotency, "account.transfer", idem, 201) {
            val actor = actorId ?: throw DomainError.Unauthorized("authentication required")
            // Locked read: a concurrent deactivation must queue behind this transfer
            // rather than observe the same ACTIVE row and write over the outcome.
            val old = accounts.findByIdForUpdate(accountId)
                ?: throw DomainError.NotFound("account $accountId not found")
            if (old.status != AccountStatus.ACTIVE) {
                throw DomainError.Conflict("only active accounts can be transferred")
            }
            if (newStoreId == old.storeId) {
                throw DomainError.Validation("cannot transfer an account to its current store")
            }
            val newStore = stores.findById(newStoreId)
                ?: throw DomainError.Validation("unknown newStoreId $newStoreId")
            // A closed or inactive store cannot take on a circuit. Without this the
            // transfer succeeds and the account lands straight in the floating-accounts
            // view — manufacturing the very state the store-closure flow exists to flag.
            if (newStore.status != StoreStatus.ACTIVE) {
                throw DomainError.Conflict(
                    "cannot transfer to ${newStore.name} (Branch ${newStore.branchCode}): " +
                        "the store is ${newStore.status.name.lowercase()}",
                )
            }
            PdfProofPolicy.requireProofSet(
                proofIds,
                AttachmentPurpose.TRANSFER_PROOF,
                field = "transfer proof",
            ) { attachments.findById(it) }
            // Guard the destination against an existing live account with the same identity
            // (store, provider, account#, circuit). Since we mark the source TRANSFERRED below
            // — which frees the partial unique index — a bad transfer would otherwise slip past
            // the DB constraint (same store) or 500 on it (different store) without this check.
            accounts.findLiveByIdentity(newStoreId, old.providerId, old.accountNumber, old.circuitId)
                ?.let { blocker ->
                    // Names the blocker's real status: "already exists" used to say
                    // "active" even when the obstacle was an account counting down its
                    // termination grace, which sends the operator looking for a bug.
                    throw DomainError.Conflict(
                        identityConflictMessage(old.accountNumber, old.circuitId, newStore, blocker),
                    )
                }

            accounts.updateStatus(old.id, AccountStatus.TRANSFERRED, expected = AccountStatus.ACTIVE)
                ?: throw DomainError.Conflict("account $accountId is no longer active; transfer was not applied")
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
                    isProrated = old.isProrated,
                    subscriptionProofIds = old.subscriptionProofIds,
                ),
                createdBy = actor,
            )
            // transfers.proof_id holds the first proof so pre-proofIds readers still work;
            // the full set lives on account_attachments, linked to BOTH accounts — the
            // source keeps its history, and the destination account's proof list is
            // complete from the moment it exists.
            val transfer = transfers.create(
                old.storeId, newStoreId, old.id, moved.id, proofIds.first(), actor, clock.now(),
            )
            accounts.linkProofs(old.id, proofIds, AttachmentPurpose.TRANSFER_PROOF, actor, transfer.id)
            accounts.linkProofs(moved.id, proofIds, AttachmentPurpose.TRANSFER_PROOF, actor, transfer.id)
            proofIds.forEach { attachments.linkEntity(it, "account", moved.id) }
            activity.record(actor, "account.transferred", "account", moved.id)
            val oldStore = stores.findById(old.storeId)
            notifications.enqueue(
                NotificationEvent.ACCOUNT_TRANSFERRED,
                NotificationContext(
                    headline = "Account ${old.accountNumber} transferred to ${newStore.name} (Branch ${newStore.branchCode})",
                    details = listOfNotNull(
                        "Account number" to old.accountNumber,
                        // Which circuit moved: an account number can cover several.
                        old.circuitId?.let { "Circuit" to it },
                        oldStore?.let { "From store" to "${it.name} (Branch ${it.branchCode})" },
                        "To store" to "${newStore.name} (Branch ${newStore.branchCode})",
                    ),
                    entityId = moved.id,
                    actorId = actor,
                    // The activity tab, not the account fields: what a reader wants here
                    // is the move itself, which activity.record above just wrote.
                    linkPath = DeepLinks.accountActivity(moved.id),
                ),
            )
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
    private val stores: StoreRepository,
    private val attachments: AttachmentRepository,
    private val idempotency: IdempotencyKeyRepository,
    private val activity: ActivityRecorder,
    private val notifications: NotificationEnqueuer,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        accountId: String,
        proofIds: List<String>,
        actorId: String?,
        idem: IdempotencyContext? = null,
    ): Account = tx.inTransaction {
        idempotent(idempotency, "account.deactivate", idem, 200) {
            // Locked read: the guard below is a read-then-write, so without the lock a
            // concurrent transfer can complete in between and this call would resurrect
            // the transferred row — leaving one circuit billable at two stores.
            val account = accounts.findByIdForUpdate(accountId)
                ?: throw DomainError.NotFound("account $accountId not found")
            if (account.status != AccountStatus.ACTIVE) {
                throw DomainError.Conflict("only active accounts can be deactivated")
            }
            PdfProofPolicy.requireProofSet(
                proofIds,
                AttachmentPurpose.DEACTIVATION_PROOF,
                field = "deactivation proof",
            ) { attachments.findById(it) }
            // Null here means the row exists but is no longer active — a conflict, not a
            // 404. The write itself carries the same guard as the check above.
            val result = accounts.markTerminationRequested(accountId, clock.now())
                ?: throw DomainError.Conflict("account $accountId is no longer active; deactivation was not applied")
            accounts.linkProofs(accountId, proofIds, AttachmentPurpose.DEACTIVATION_PROOF, actorId)
            proofIds.forEach { attachments.linkEntity(it, "account", accountId) }
            activity.record(actorId, "account.deactivation_requested", "account", accountId)
            val store = stores.findById(account.storeId)
            notifications.enqueue(
                NotificationEvent.ACCOUNT_DEACTIVATION_REQUESTED,
                NotificationContext(
                    headline = "Termination requested for account ${account.accountNumber}",
                    // Account number alone cannot identify the subject: one number
                    // legitimately recurs across stores and across circuits within a
                    // store, so two unrelated terminations rendered identical emails.
                    // The grace end is the actionable fact — it is the deadline the
                    // whole notification exists to announce.
                    details = listOfNotNull(
                        "Account number" to account.accountNumber,
                        account.circuitId?.let { "Circuit" to it },
                        store?.let { "Store" to "${it.name} (Branch ${it.branchCode})" },
                        result.graceEndDate?.let { "Grace period ends" to it.toString() },
                    ),
                    entityId = accountId,
                    actorId = actorId,
                    linkPath = DeepLinks.account(accountId),
                ),
            )
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
        // Locked read: the grace-expiry job may be archiving this very account. Without
        // the lock both commit and the account ends up inactive with no grace record.
        val account = accounts.findByIdForUpdate(accountId)
            ?: throw DomainError.NotFound("account $accountId not found")
        if (account.status != AccountStatus.TERMINATION_REQUESTED) {
            throw DomainError.Conflict("only accounts in termination_requested status can have deactivation cancelled")
        }
        if (reason.isBlank()) throw DomainError.Validation("a cancellation reason is required")
        val result = accounts.cancelTerminationRequested(accountId)
            ?: throw DomainError.Conflict(
                "account $accountId is no longer in termination_requested status; cancellation was not applied",
            )
        activity.record(actorId, "account.deactivation_cancelled", "account", accountId, details = reason)
        result
    }
}
