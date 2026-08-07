package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.DeepLinks
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.service.AccountIdentityPolicy
import com.puregoldbe.ibms.domain.service.PdfProofPolicy
import com.puregoldbe.ibms.domain.valueobject.Money

/**
 * The conflict raised when an account identity is already taken.
 *
 * Deliberately specific. One account number legitimately recurs across stores and across
 * circuits within a store, so a bare "already exists" leaves the operator unable to tell
 * which of several same-numbered accounts is in the way — and the likeliest workaround,
 * nudging the number or circuit, manufactures the duplicates the index exists to prevent.
 * Naming the blocker's status matters just as much: an account serving out its 30-day
 * termination grace still holds the slot without being active.
 */
internal fun identityConflictMessage(accountNumber: String, circuitId: String?, store: Store, blocker: Account): String {
    val circuit = circuitId?.let { "circuit $it" } ?: "no circuit"
    val state = when (blocker.status) {
        AccountStatus.TERMINATION_REQUESTED ->
            "an account pending termination (still within its 30-day grace period)"
        // TRANSFERRED and INACTIVE are excluded from the live set, so they can never
        // be the blocker; ACTIVE is the only other value that reaches here.
        else -> "an active account"
    }
    return "$state with number $accountNumber and $circuit already exists at " +
        "${store.name} (Branch ${store.branchCode})"
}

class ListAccountsUseCase(
    private val accounts: AccountRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        storeId: String?,
        providerId: String?,
        status: AccountStatus?,
        cursor: String?,
        limit: Int,
    ): CursorPage<Account> =
        tx.inTransaction { accounts.page(storeId, providerId, status, cursor, limit) }
}

class GetAccountUseCase(
    private val accounts: AccountRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String): Account =
        tx.inTransaction { accounts.findById(id) } ?: throw DomainError.NotFound("account $id not found")
}

/**
 * Creates an ISP account. Business rules: rate (MRC) must be > 0; 1..3 subscription
 * proof PDFs must be uploaded; provider & store must exist; and the account identity
 * (store, provider, account_number, circuit) must be free among LIVE accounts — the
 * same rule the partial unique index enforces. One account number may recur across
 * stores and carry several circuits within a store; only the full four-part identity
 * has to be unique.
 */
class CreateAccountUseCase(
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val stores: StoreRepository,
    private val activity: ActivityRecorder,
    private val attachments: AttachmentRepository,
    private val notifications: NotificationEnqueuer,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(rawInput: AccountUpsertRequest, actorId: String?): Account = tx.inTransaction {
        if (rawInput.accountNumber.isBlank()) throw DomainError.Validation("accountNumber is required")
        // Normalize BEFORE the identity check so the value that is compared is the value
        // that gets stored — otherwise a padded or whitespace-only field slips past the
        // guard and lands in a slot the guard can never find again.
        val input = AccountIdentityPolicy.normalize(rawInput)
        if (!Money.isPositive(input.rate)) throw DomainError.Validation("rate (MRC) must be greater than 0")
        // Validate EVERY proof, not just the first: an unknown id used to reach the
        // insert and come back as an FK violation -> 500.
        val proofIds = PdfProofPolicy.mergeProofIds(null, input.subscriptionProofIds)
        PdfProofPolicy.requireProofSet(
            proofIds,
            AttachmentPurpose.SUBSCRIPTION_PROOF,
            field = "subscriptionProofIds",
            missingMessage = "a subscription proof (PDF) is required",
        ) { attachments.findById(it) }
        providers.findById(input.providerId) ?: throw DomainError.Validation("unknown providerId ${input.providerId}")
        val store = stores.findById(input.storeId)
            ?: throw DomainError.Validation("unknown storeId ${input.storeId}")
        accounts.findLiveByIdentity(input.storeId, input.providerId, input.accountNumber, input.circuitId)
            ?.let { blocker ->
                throw DomainError.Conflict(
                    identityConflictMessage(input.accountNumber, input.circuitId, store, blocker),
                    "duplicate_account_number",
                )
            }
        val account = accounts.create(input.copy(subscriptionProofIds = proofIds), actorId)
        proofIds.forEach { attachments.linkEntity(it, "account", account.id) }
        activity.record(actorId, "account.created", "account", account.id)
        notifications.enqueue(
            NotificationEvent.ACCOUNT_CREATED,
            NotificationContext(
                headline = "New account added: ${account.accountNumber}",
                details = listOfNotNull(
                    "Account number" to account.accountNumber,
                    account.circuitId?.let { "Circuit" to it },
                    "MRC" to Money.display(account.rate),
                ),
                entityId = account.id,
                actorId = actorId,
                linkPath = DeepLinks.account(account.id),
            ),
        )
        account
    }
}

class UpdateAccountUseCase(
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val stores: StoreRepository,
    private val activity: ActivityRecorder,
    private val notifications: NotificationEnqueuer,
    private val tx: TransactionRunner,
) {
    /**
     * [actorId] has no default on purpose: a direct edit that records no actor is the
     * bug this parameter was added to fix, and a default would let the next caller
     * reintroduce it silently.
     */
    suspend operator fun invoke(id: String, rawInput: AccountUpsertRequest, actorId: String?): Account = tx.inTransaction {
        if (rawInput.accountNumber.isBlank()) throw DomainError.Validation("accountNumber is required")
        val input = AccountIdentityPolicy.normalize(rawInput)
        if (!Money.isPositive(input.rate)) throw DomainError.Validation("rate (MRC) must be greater than 0")
        providers.findById(input.providerId) ?: throw DomainError.Validation("unknown providerId ${input.providerId}")
        val store = stores.findById(input.storeId)
            ?: throw DomainError.Validation("unknown storeId ${input.storeId}")
        val current = accounts.findById(id) ?: throw DomainError.NotFound("account $id not found")

        // A plain edit must not be able to relocate a circuit. `update` rewrites every
        // identity column unconditionally, so without this an operator could move an
        // account to another store with no transfer proof, no `transfers` row, no
        // activity entry and no destination check — bypassing TransferAccountUseCase
        // entirely. Relocation is a transfer; it has its own endpoint and its own audit.
        if (input.storeId != current.storeId) {
            throw DomainError.Conflict(
                "an account cannot change store through an update; use POST /accounts/$id/transfer",
            )
        }
        // The remaining identity fields may change, but only into a free slot. Without
        // this the only backstop is the partial unique index, which surfaces as an
        // unattributed "resource already exists" 409.
        val identityChanged = input.accountNumber != current.accountNumber ||
            input.circuitId != current.circuitId ||
            input.providerId != current.providerId
        if (identityChanged) {
            accounts.findLiveByIdentity(input.storeId, input.providerId, input.accountNumber, input.circuitId)
                ?.takeIf { it.id != id }
                ?.let { blocker ->
                    throw DomainError.Conflict(
                        identityConflictMessage(input.accountNumber, input.circuitId, store, blocker),
                        "duplicate_account_number",
                    )
                }
        }

        val updated = accounts.update(id, input) ?: throw DomainError.NotFound("account $id not found")
        activity.record(actorId, "account.updated", "account", updated.id)
        notifications.enqueue(
            NotificationEvent.ACCOUNT_UPDATED,
            NotificationContext(
                headline = "Account details updated: ${updated.accountNumber}",
                details = listOf("Account number" to updated.accountNumber),
                entityId = updated.id,
                actorId = actorId,
                // The activity tab, which the record above is what makes non-empty.
                linkPath = DeepLinks.accountActivity(updated.id),
            ),
        )
        updated
    }
}
