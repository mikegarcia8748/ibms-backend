package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.valueobject.Money

class ListAccountsUseCase(
    private val accounts: AccountRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(storeId: String?, providerId: String?, status: AccountStatus?): List<Account> =
        tx.inTransaction { accounts.list(storeId, providerId, status) }
}

class GetAccountUseCase(
    private val accounts: AccountRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String): Account =
        tx.inTransaction { accounts.findById(id) } ?: throw DomainError.NotFound("account $id not found")
}

/**
 * Creates an ISP account. Business rules: rate (MRC) must be > 0; provider & store
 * must exist; (provider, account_number) is unique (blocks duplicates the schema
 * also guards).
 */
class CreateAccountUseCase(
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val stores: StoreRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(input: AccountUpsertRequest, actorId: String?): Account = tx.inTransaction {
        if (input.accountNumber.isBlank()) throw DomainError.Validation("accountNumber is required")
        if (!Money.isPositive(input.rate)) throw DomainError.Validation("rate (MRC) must be greater than 0")
        providers.findById(input.providerId) ?: throw DomainError.Validation("unknown providerId ${input.providerId}")
        stores.findById(input.storeId) ?: throw DomainError.Validation("unknown storeId ${input.storeId}")
        if (accounts.existsByProviderAndNumber(input.providerId, input.accountNumber)) {
            throw DomainError.Conflict(
                "account ${input.accountNumber} already exists for this provider",
                "duplicate_account_number",
            )
        }
        accounts.create(input, actorId)
    }
}
