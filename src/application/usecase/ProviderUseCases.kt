package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Provider
import com.puregoldbe.ibms.domain.model.ProviderStatus
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.InvoiceSequenceRepository
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.service.InvoiceNumberFormatter

class ListProvidersUseCase(
    private val providers: ProviderRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(status: ProviderStatus?): List<Provider> =
        tx.inTransaction { providers.list(status) }
}

/**
 * Creates a provider and seeds its invoice_sequences row (prefix = acronym) in one
 * transaction, so an invoice number can be minted for it immediately.
 */
class CreateProviderUseCase(
    private val providers: ProviderRepository,
    private val sequences: InvoiceSequenceRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(name: String, paymentScheduleDay: Int): Provider = tx.inTransaction {
        if (name.isBlank()) throw DomainError.Validation("provider name is required")
        if (paymentScheduleDay !in 1..31) throw DomainError.Validation("paymentScheduleDay must be 1..31")
        val provider = providers.create(name.trim(), paymentScheduleDay)
        sequences.seed(provider.id, InvoiceNumberFormatter.prefix(provider.name))
        provider
    }
}

class DeactivateProviderUseCase(
    private val providers: ProviderRepository,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String): Provider = tx.inTransaction {
        providers.deactivate(id, clock.now()) ?: throw DomainError.NotFound("provider $id not found")
    }
}
