package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.service.PdfProofPolicy
import com.puregoldbe.ibms.domain.valueobject.Money

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
 * Creates an ISP account. Business rules: rate (MRC) must be > 0; a subscription proof
 * PDF must be uploaded; provider & store must exist; (provider, account_number) is
 * unique (blocks duplicates the schema also guards).
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
    suspend operator fun invoke(input: AccountUpsertRequest, actorId: String?): Account = tx.inTransaction {
        if (input.accountNumber.isBlank()) throw DomainError.Validation("accountNumber is required")
        if (!Money.isPositive(input.rate)) throw DomainError.Validation("rate (MRC) must be greater than 0")
        val proofId = input.subscriptionProofIds.firstOrNull()
            ?: throw DomainError.Validation("a subscription proof (PDF) is required")
        PdfProofPolicy.requireUploadedPdf(attachments.findById(proofId), "subscriptionProofId")
        providers.findById(input.providerId) ?: throw DomainError.Validation("unknown providerId ${input.providerId}")
        stores.findById(input.storeId) ?: throw DomainError.Validation("unknown storeId ${input.storeId}")
        if (accounts.existsByIdentity(input.storeId, input.providerId, input.accountNumber, input.circuitId)) {
            throw DomainError.Conflict(
                "account ${input.accountNumber} already exists for this provider",
                "duplicate_account_number",
            )
        }
        val account = accounts.create(input, actorId)
        activity.record(actorId, "account.created", "account", account.id)
        notifications.enqueue(
            NotificationEvent.ACCOUNT_CREATED,
            NotificationContext(
                headline = "New account added: ${account.accountNumber}",
                details = listOfNotNull(
                    "Account number" to account.accountNumber,
                    account.circuitId?.let { "Circuit" to it },
                    "MRC" to account.rate,
                ),
                entityId = account.id,
                linkPath = "/accounts/${account.id}",
            ),
        )
        account
    }
}

class UpdateAccountUseCase(
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val stores: StoreRepository,
    private val notifications: NotificationEnqueuer,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String, input: AccountUpsertRequest): Account = tx.inTransaction {
        if (input.accountNumber.isBlank()) throw DomainError.Validation("accountNumber is required")
        if (!Money.isPositive(input.rate)) throw DomainError.Validation("rate (MRC) must be greater than 0")
        providers.findById(input.providerId) ?: throw DomainError.Validation("unknown providerId ${input.providerId}")
        stores.findById(input.storeId) ?: throw DomainError.Validation("unknown storeId ${input.storeId}")
        val updated = accounts.update(id, input) ?: throw DomainError.NotFound("account $id not found")
        notifications.enqueue(
            NotificationEvent.ACCOUNT_UPDATED,
            NotificationContext(
                headline = "Account details updated: ${updated.accountNumber}",
                details = listOf("Account number" to updated.accountNumber),
                entityId = updated.id,
                linkPath = "/accounts/${updated.id}",
            ),
        )
        updated
    }
}
