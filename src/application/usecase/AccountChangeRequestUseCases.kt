package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AccountChangeRequest
import com.puregoldbe.ibms.domain.model.AccountChangeRequestStatus
import com.puregoldbe.ibms.domain.model.AccountChangeRequestWithDiff
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.DeepLinks
import com.puregoldbe.ibms.domain.model.FieldDiff
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.SubmitAccountChangeRequestInput
import com.puregoldbe.ibms.domain.port.AccountChangeRequestRepository
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.model.ProviderStatus
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.valueobject.Money

class SubmitAccountChangeRequestUseCase(
    private val requests: AccountChangeRequestRepository,
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val attachments: AttachmentRepository,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        accountId: String,
        input: SubmitAccountChangeRequestInput,
        submitterId: String,
    ): AccountChangeRequest = tx.inTransaction {
        val account = accounts.findById(accountId)
            ?: throw DomainError.NotFound("account $accountId not found")
        if (account.status != AccountStatus.ACTIVE) {
            throw DomainError.Conflict("can only submit changes for active accounts")
        }

        if (input.accountNumber == null && input.installationDate == null && input.rate == null &&
            input.providerId == null && input.circuitId == null && input.planName == null &&
            input.proofAttachmentId == null
        ) {
            throw DomainError.Validation("at least one field must be changed")
        }

        // Validate field lengths and non-blank
        input.accountNumber?.let {
            if (it.isBlank()) throw DomainError.Validation("accountNumber cannot be blank")
            if (it.length > 100) throw DomainError.Validation("accountNumber exceeds maximum length of 100")
        }
        input.circuitId?.let {
            if (it.isBlank()) throw DomainError.Validation("circuitId cannot be blank")
            if (it.length > 100) throw DomainError.Validation("circuitId exceeds maximum length of 100")
        }
        input.planName?.let {
            if (it.isBlank()) throw DomainError.Validation("planName cannot be blank")
            if (it.length > 255) throw DomainError.Validation("planName exceeds maximum length of 255")
        }

        if (input.providerId != null) {
            providers.findById(input.providerId)
                ?: throw DomainError.Validation("provider ${input.providerId} not found")
        }

        if (input.proofAttachmentId != null) {
            if (!attachments.exists(input.proofAttachmentId)) {
                throw DomainError.Validation("attachment ${input.proofAttachmentId} not found")
            }
        }

        if (input.rate != null) {
            // Check parseability first for the field-specific message; Money.isPositive would
            // otherwise raise its own generic DomainError.Validation.
            if (Money.parseOrNull(input.rate) == null) {
                throw DomainError.Validation("rate must be a valid decimal number")
            }
            if (!Money.isPositive(input.rate)) {
                throw DomainError.Validation("rate must be greater than zero")
            }
        }

        val existing = requests.findPendingByAccountId(accountId)
        if (existing != null) {
            requests.cancel(existing.id, clock.now())
            activity.record(submitterId, "account_change_request.auto_cancelled", "account_change_request", existing.id)
        }

        val created = requests.create(accountId, submitterId, input)
        activity.record(submitterId, "account_change_request.submitted", "account_change_request", created.id)
        created
    }
}

class ApproveAccountChangeRequestUseCase(
    private val requests: AccountChangeRequestRepository,
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val activity: ActivityRecorder,
    private val notifications: NotificationEnqueuer,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(requestId: String, approverId: String): AccountChangeRequest = tx.inTransaction {
        val request = requests.findById(requestId)
            ?: throw DomainError.NotFound("change request $requestId not found")
        if (request.status != AccountChangeRequestStatus.PENDING) {
            throw DomainError.Conflict("only pending requests can be approved")
        }

        // Validate provider is still ACTIVE if changed
        if (request.providerIdNew != null) {
            val newProvider = providers.findById(request.providerIdNew)
                ?: throw DomainError.NotFound("provider ${request.providerIdNew} not found")
            if (newProvider.status != ProviderStatus.ACTIVE) {
                throw DomainError.Conflict("cannot assign account to inactive provider")
            }
        }

        val account = accounts.findById(request.accountId)
            ?: throw DomainError.NotFound("account ${request.accountId} not found")

        if (account.status != AccountStatus.ACTIVE) {
            throw DomainError.Conflict("can only approve changes for active accounts; account is ${account.status.name.lowercase()}")
        }

        val updateReq = AccountUpsertRequest(
            accountNumber = request.accountNumberNew ?: account.accountNumber,
            circuitId = request.circuitIdNew ?: account.circuitId,
            providerId = request.providerIdNew ?: account.providerId,
            storeId = account.storeId,
            planName = request.planNameNew ?: account.planName,
            serviceType = account.serviceType,
            speed = account.speed,
            contractDurationMonths = account.contractDurationMonths,
            contractStartDate = account.contractStartDate,
            contractEndDate = account.contractEndDate,
            notes = account.notes,
            installationFee = account.installationFee,
            rate = request.rateNew ?: account.rate,
            installationDate = request.installationDateNew ?: account.installationDate,
            billingPeriodLabel = account.billingPeriodLabel,
            subscriptionProofIds = account.subscriptionProofIds,
        )

        if (request.accountNumberNew != null || request.providerIdNew != null) {
            val newProvider = request.providerIdNew ?: account.providerId
            val newNumber = request.accountNumberNew ?: account.accountNumber
            val newCircuit = request.circuitIdNew ?: account.circuitId
            if (newProvider != account.providerId || newNumber != account.accountNumber) {
                if (accounts.existsByIdentity(account.storeId, newProvider, newNumber, newCircuit)) {
                    throw DomainError.Conflict("account number $newNumber already exists for this provider")
                }
            }
        }

        accounts.update(request.accountId, updateReq)
            ?: throw DomainError.NotFound("account ${request.accountId} not found")

        // accounts.update writes only Accounts columns — it never touches the proof link
        // table. Passing the proof through AccountUpsertRequest silently dropped it, so
        // link it explicitly. It joins the account's subscription proofs as its own
        // single-proof activity (distinct linked_at).
        if (request.proofAttachmentId != null) {
            accounts.linkProofs(
                request.accountId,
                listOf(request.proofAttachmentId),
                AttachmentPurpose.SUBSCRIPTION_PROOF,
                approverId,
            )
        }

        val approved = requests.approve(requestId, approverId, clock.now())
            ?: throw DomainError.NotFound("change request $requestId not found")
        activity.record(approverId, "account_change_request.approved", "account_change_request", requestId)
        notifications.enqueue(
            NotificationEvent.ACCOUNT_UPDATED,
            NotificationContext(
                headline = "Account details updated: ${account.accountNumber} (change request approved)",
                details = listOf("Account number" to account.accountNumber),
                entityId = request.accountId,
                actorId = approverId,
                // The diff view, not the account's activity tab: the approval activity is
                // recorded against the *request* id, and the feed filters on entity id
                // alone, so this change would never show up on the account's tab.
                linkPath = DeepLinks.changeRequest(request.accountId, requestId),
            ),
        )
        approved
    }
}

class RejectAccountChangeRequestUseCase(
    private val requests: AccountChangeRequestRepository,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(requestId: String, reason: String, rejecterId: String): AccountChangeRequest =
        tx.inTransaction {
            val request = requests.findById(requestId)
                ?: throw DomainError.NotFound("change request $requestId not found")
            if (request.status != AccountChangeRequestStatus.PENDING) {
                throw DomainError.Conflict("only pending requests can be rejected")
            }
            if (reason.isBlank()) throw DomainError.Validation("rejection reason is required")
            if (reason.length > 1000) throw DomainError.Validation("rejection reason exceeds maximum length of 1000")

            val rejected = requests.reject(requestId, reason, clock.now())
                ?: throw DomainError.NotFound("change request $requestId not found")
            activity.record(rejecterId, "account_change_request.rejected", "account_change_request", requestId)
            rejected
        }
}

class CancelAccountChangeRequestUseCase(
    private val requests: AccountChangeRequestRepository,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(requestId: String, cancellerId: String): AccountChangeRequest =
        tx.inTransaction {
            val request = requests.findById(requestId)
                ?: throw DomainError.NotFound("change request $requestId not found")
            if (request.status != AccountChangeRequestStatus.PENDING) {
                throw DomainError.Conflict("only pending requests can be cancelled")
            }
            if (request.submittedById != cancellerId) {
                throw DomainError.Forbidden("only the submitter can cancel their own request")
            }

            val cancelled = requests.cancel(requestId, clock.now())
                ?: throw DomainError.NotFound("change request $requestId not found")
            activity.record(cancellerId, "account_change_request.cancelled", "account_change_request", requestId)
            cancelled
        }
}

class GetAccountChangeRequestWithDiffUseCase(
    private val requests: AccountChangeRequestRepository,
    private val accounts: AccountRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(requestId: String): AccountChangeRequestWithDiff = tx.inTransaction {
        val request = requests.findById(requestId)
            ?: throw DomainError.NotFound("change request $requestId not found")
        val account = accounts.findById(request.accountId)
            ?: throw DomainError.NotFound("account ${request.accountId} not found")

        val diffs = buildList {
            if (request.accountNumberNew != null) {
                add(FieldDiff("accountNumber", account.accountNumber, request.accountNumberNew))
            }
            if (request.installationDateNew != null) {
                add(FieldDiff("installationDate", account.installationDate.toString(), request.installationDateNew.toString()))
            }
            if (request.rateNew != null) {
                add(FieldDiff("rate", account.rate, request.rateNew))
            }
            if (request.providerIdNew != null) {
                add(FieldDiff("providerId", account.providerId, request.providerIdNew))
            }
            if (request.circuitIdNew != null) {
                add(FieldDiff("circuitId", account.circuitId, request.circuitIdNew))
            }
            if (request.planNameNew != null) {
                add(FieldDiff("planName", account.planName, request.planNameNew))
            }
            if (request.proofAttachmentId != null) {
                add(FieldDiff("proofAttachmentId", null, request.proofAttachmentId))
            }
        }

        AccountChangeRequestWithDiff(request, diffs)
    }
}

class ListAccountChangeRequestsUseCase(
    private val requests: AccountChangeRequestRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        accountId: String? = null,
        submittedById: String? = null,
        status: AccountChangeRequestStatus? = null,
        cursor: String? = null,
        limit: Int = 20,
    ): CursorPage<AccountChangeRequest> = tx.inTransaction {
        requests.page(accountId, submittedById, status, cursor, limit)
    }
}
