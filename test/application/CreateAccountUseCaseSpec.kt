package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.CreateAccountUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.Provider
import com.puregoldbe.ibms.domain.model.ProviderStatus
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import com.puregoldbe.ibms.support.uploadedPdfAttachment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private fun request(vararg proofIds: String) = AccountUpsertRequest(
    accountNumber = "ACC-001",
    providerId = "p1",
    storeId = "s1",
    rate = "1000.00",
    installationDate = LocalDate(2025, 1, 1),
    subscriptionProofIds = proofIds.toList(),
)

private fun created(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    createdAt = Instant.fromEpochSeconds(0),
)

/**
 * The subscription-proof set is the focus here: before V23 only the FIRST id was
 * validated and the rest went straight to the insert, so an unknown id came back as a
 * foreign-key violation — a 500 — rather than a 400.
 */
class CreateAccountUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>(relaxed = true)
    val providers = mockk<ProviderRepository>(relaxed = true)
    val stores = mockk<StoreRepository>(relaxed = true)
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val attachments = mockk<AttachmentRepository>(relaxed = true)
    val notifications = mockk<NotificationEnqueuer>(relaxed = true)
    val useCase = CreateAccountUseCase(
        accounts, providers, stores, activity, attachments, notifications, ImmediateTransactionRunner(),
    )

    beforeTest {
        every { providers.findById("p1") } returns Provider(
            id = "p1", name = "Converge", paymentScheduleDay = 5,
            status = ProviderStatus.ACTIVE, createdAt = Instant.fromEpochSeconds(0),
        )
        every { stores.findById("s1") } returns Store(
            id = "s1", storeType = StoreType.PUREGOLD, branchCode = "001", name = "Main",
            status = StoreStatus.ACTIVE, proofOfInstallationId = "inst-1",
            createdAt = Instant.fromEpochSeconds(0),
        )
        every { accounts.existsByIdentity(any(), any(), any(), any()) } returns false
        every { accounts.create(any(), any()) } returns created()
        (1..4).forEach {
            every { attachments.findById("proof-$it") } returns
                uploadedPdfAttachment("proof-$it", AttachmentPurpose.SUBSCRIPTION_PROOF)
        }
    }

    Given("a create carrying one subscription proof") {
        When("creating") {
            Then("succeeds") {
                useCase(request("proof-1"), "actor-1").id shouldBe "acc-1"
            }
        }
    }

    Given("a create carrying the maximum three proofs") {
        When("creating") {
            useCase(request("proof-1", "proof-2", "proof-3"), "actor-1")

            Then("all three reach the repository in request order") {
                verify(exactly = 1) {
                    accounts.create(
                        withArg { it.subscriptionProofIds shouldBe listOf("proof-1", "proof-2", "proof-3") },
                        "actor-1",
                    )
                }
            }
            Then("each proof is stamped with the owning account") {
                listOf("proof-1", "proof-2", "proof-3").forEach {
                    verify(exactly = 1) { attachments.linkEntity(it, "account", "acc-1") }
                }
            }
        }
    }

    Given("a create carrying four proofs") {
        When("creating") {
            Then("throws Validation naming the cap, and nothing is written") {
                val error = shouldThrow<DomainError.Validation> {
                    useCase(request("proof-1", "proof-2", "proof-3", "proof-4"), "actor-1")
                }
                error.message shouldBe "at most 3 files may be attached to subscriptionProofIds"
                verify(exactly = 0) { accounts.create(any(), any()) }
            }
        }
    }

    Given("a create with no proof at all") {
        When("creating") {
            Then("throws Validation with the subscription-proof wording") {
                val error = shouldThrow<DomainError.Validation> { useCase(request(), "actor-1") }
                error.message shouldBe "a subscription proof (PDF) is required"
            }
        }
    }

    Given("a create repeating the same proof") {
        When("creating") {
            Then("throws Validation") {
                val error = shouldThrow<DomainError.Validation> {
                    useCase(request("proof-1", "proof-1"), "actor-1")
                }
                error.message shouldBe "subscriptionProofIds contains duplicate attachment ids"
            }
        }
    }

    Given("a create whose SECOND proof is an unknown id") {
        every { attachments.findById("ghost") } returns null

        When("creating") {
            Then("throws Validation, not a database error — every id is validated") {
                val error = shouldThrow<DomainError.Validation> {
                    useCase(request("proof-1", "ghost"), "actor-1")
                }
                error.message shouldBe "a valid subscriptionProofIds is required"
                verify(exactly = 0) { accounts.create(any(), any()) }
            }
        }
    }

    Given("a create whose SECOND proof was presigned but never uploaded") {
        every { attachments.findById("empty") } returns
            uploadedPdfAttachment("empty", AttachmentPurpose.SUBSCRIPTION_PROOF).copy(sizeBytes = null)

        When("creating") {
            Then("throws Validation") {
                val error = shouldThrow<DomainError.Validation> {
                    useCase(request("proof-1", "empty"), "actor-1")
                }
                error.message shouldBe "subscriptionProofIds has not been uploaded yet"
            }
        }
    }

    Given("a create whose proof is a deactivation proof") {
        every { attachments.findById("deact-1") } returns
            uploadedPdfAttachment("deact-1", AttachmentPurpose.DEACTIVATION_PROOF)

        When("creating") {
            Then("throws Validation — the purpose must match the activity") {
                val error = shouldThrow<DomainError.Validation> { useCase(request("deact-1"), "actor-1") }
                error.message shouldBe "subscriptionProofIds must reference a subscription_proof attachment"
            }
        }
    }
})
