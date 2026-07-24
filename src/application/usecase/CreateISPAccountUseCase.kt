package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.CreateISPAccountInput
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.valueobject.Money
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Creates an ISP account with mandatory subscription proof, circuit ID validation,
 * and auto-computed proration flag. Delegates core account creation to [CreateAccountUseCase].
 *
 * Proration rule: isProrated = installationDate.dayOfMonth > provider.paymentScheduleDay
 */
class CreateISPAccountUseCase(
    private val createAccount: CreateAccountUseCase,
    private val providers: ProviderRepository,
    private val attachments: AttachmentRepository,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(input: CreateISPAccountInput, actorId: String?): Account {
        // --- ISP-specific validation (runs in its own transaction) ---
        val (isProrated, req) = tx.inTransaction {
            if (input.accountNumber.isBlank()) throw DomainError.Validation("accountNumber is required")
            if (input.circuitId.isNullOrBlank()) throw DomainError.Validation("circuitId is required for ISP accounts")
            if (!Money.isPositive(input.rate)) throw DomainError.Validation("rate (MRC) must be greater than 0")

            val today = clock.now().toLocalDateTime(TimeZone.of("Asia/Manila")).date
            if (input.installationDate > today) {
                throw DomainError.Validation("installationDate cannot be in the future")
            }

            if (input.subscriptionProofId.isBlank()) throw DomainError.Validation("subscriptionProofId is required")
            if (!attachments.exists(input.subscriptionProofId)) {
                throw DomainError.Validation("subscription proof attachment not found")
            }

            // --- Compute proration flag ---
            val provider = providers.findById(input.providerId)
                ?: throw DomainError.Validation("unknown providerId ${input.providerId}")
            val prorated = input.installationDate.dayOfMonth > provider.paymentScheduleDay

            prorated to AccountUpsertRequest(
                accountNumber = input.accountNumber.trim(),
                circuitId = input.circuitId.trim(),
                providerId = input.providerId,
                storeId = input.storeId,
                rate = input.rate,
                installationDate = input.installationDate,
                subscriptionProofIds = listOf(input.subscriptionProofId),
                isProrated = prorated,
            )
        }

        // --- Delegate to existing CreateAccountUseCase (manages its own transaction) ---
        return createAccount(req, actorId)
    }
}
