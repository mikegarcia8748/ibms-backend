package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.Provider
import com.puregoldbe.ibms.domain.model.ProviderStatus
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.BatchSequenceRepository
import com.puregoldbe.ibms.domain.port.InvoiceSequenceRepository
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.service.InvoiceNumberFormatter
import com.puregoldbe.ibms.domain.service.NameNormalizer

class ListProvidersUseCase(
    private val providers: ProviderRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(status: ProviderStatus?, cursor: String?, limit: Int): CursorPage<Provider> =
        tx.inTransaction { providers.page(status, cursor, limit) }
}

/**
 * Creates a provider and seeds its invoice_sequences row (prefix = acronym) in one
 * transaction, so an invoice number can be minted for it immediately.
 */
class CreateProviderUseCase(
    private val providers: ProviderRepository,
    private val sequences: InvoiceSequenceRepository,
    private val batchSequences: BatchSequenceRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(name: String, paymentScheduleDay: Int): Provider = tx.inTransaction {
        if (name.isBlank()) throw DomainError.Validation("provider name is required")
        if (paymentScheduleDay !in 1..31) throw DomainError.Validation("paymentScheduleDay must be 1..31")
        val canonical = NameNormalizer.displayName(name)
        // Names are case-insensitive in the DB (citext, V24). Check first so the caller gets
        // "provider 'X' already exists" naming the conflict, rather than the bare 23505 that
        // StatusPages renders as a generic "resource already exists".
        providers.findByName(canonical)?.let {
            throw DomainError.Conflict("provider '${it.name}' already exists")
        }
        val provider = providers.create(canonical, paymentScheduleDay)
        sequences.seed(provider.id, InvoiceNumberFormatter.prefix(provider.name))
        batchSequences.seed(provider.id)
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

class UpdateProviderUseCase(
    private val providers: ProviderRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String, name: String?, paymentScheduleDay: Int?): Provider = tx.inTransaction {
        if (name != null && name.isBlank()) throw DomainError.Validation("provider name cannot be blank")
        if (paymentScheduleDay != null && paymentScheduleDay !in 1..31) {
            throw DomainError.Validation("paymentScheduleDay must be 1..31")
        }
        val canonical = name?.let { NameNormalizer.displayName(it) }
        // A rename onto another provider's name collides case-insensitively (citext, V24).
        // Renaming a provider to a different casing of its OWN name stays allowed.
        if (canonical != null) {
            providers.findByName(canonical)?.takeIf { it.id != id }?.let {
                throw DomainError.Conflict("provider '${it.name}' already exists")
            }
        }
        providers.updateDetails(id, canonical, paymentScheduleDay)
            ?: throw DomainError.NotFound("provider $id not found")
    }
}
