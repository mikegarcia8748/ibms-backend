package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.TransferAccountUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.TransferRecord
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TransferRepository
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.FakeIdempotencyKeyRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import com.puregoldbe.ibms.support.uploadedPdfAttachment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private fun account(
    id: String = "acc-1",
    storeId: String = "s1",
    status: AccountStatus = AccountStatus.ACTIVE,
    isProrated: Boolean = false,
) = Account(
    id = id, accountNumber = "ACC-001", circuitId = null, providerId = "p1", storeId = storeId,
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    isProrated = isProrated, status = status,
    createdAt = Instant.fromEpochSeconds(0),
)

class TransferAccountUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>(relaxed = true)
    val stores = mockk<StoreRepository>(relaxed = true)
    val transfers = mockk<TransferRepository>(relaxed = true)
    val attachments = mockk<AttachmentRepository>(relaxed = true)
    val idempotency = FakeIdempotencyKeyRepository()
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val notifications = mockk<NotificationEnqueuer>(relaxed = true)
    val clock = FakeClock(Instant.parse("2026-08-01T00:00:00Z"))
    val useCase = TransferAccountUseCase(
        accounts, stores, transfers, attachments, idempotency, activity, notifications, clock, ImmediateTransactionRunner(),
    )

    Given("an INACTIVE source account") {
        every { accounts.findById("acc-1") } returns account(status = AccountStatus.INACTIVE)
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.TRANSFER_PROOF)

        When("transferring") {
            Then("throws Conflict and does not create a new account") {
                shouldThrow<DomainError.Conflict> { useCase("acc-1", "s2", listOf("proof-1"), "actor-1") }
                verify(exactly = 0) { accounts.create(any(), any()) }
            }
        }
    }

    Given("a TERMINATION_REQUESTED source account") {
        every { accounts.findById("acc-1") } returns account(status = AccountStatus.TERMINATION_REQUESTED)
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.TRANSFER_PROOF)

        When("transferring") {
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("acc-1", "s2", listOf("proof-1"), "actor-1") }
            }
        }
    }

    Given("an already-TRANSFERRED source account") {
        every { accounts.findById("acc-1") } returns account(status = AccountStatus.TRANSFERRED)
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.TRANSFER_PROOF)

        When("transferring") {
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("acc-1", "s2", listOf("proof-1"), "actor-1") }
            }
        }
    }

    Given("an ACTIVE account transferred to its current store") {
        every { accounts.findById("acc-1") } returns account(storeId = "s1")

        When("transferring to the same store") {
            Then("throws Validation and does not touch the account") {
                shouldThrow<DomainError.Validation> { useCase("acc-1", "s1", listOf("proof-1"), "actor-1") }
                verify(exactly = 0) { accounts.updateStatus(any(), any()) }
                verify(exactly = 0) { accounts.create(any(), any()) }
            }
        }
    }

    Given("a destination store that already holds the same identity") {
        every { accounts.findById("acc-1") } returns account(storeId = "s1")
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.TRANSFER_PROOF)
        every { accounts.existsByIdentity("s2", "p1", "ACC-001", null) } returns true

        When("transferring") {
            Then("throws Conflict and does not mark the source transferred") {
                shouldThrow<DomainError.Conflict> { useCase("acc-1", "s2", listOf("proof-1"), "actor-1") }
                verify(exactly = 0) { accounts.updateStatus(any(), any()) }
            }
        }
    }

    Given("a prorated ACTIVE account transferred to a different store") {
        val slot = slot<AccountUpsertRequest>()
        every { accounts.findById("acc-1") } returns account(storeId = "s1", isProrated = true)
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.TRANSFER_PROOF)
        every { accounts.existsByIdentity("s2", "p1", "ACC-001", null) } returns false
        every { accounts.create(capture(slot), any()) } returns account(id = "acc-2", storeId = "s2", isProrated = true)

        When("transferring") {
            val moved = useCase("acc-1", "s2", listOf("proof-1"), "actor-1")
            Then("the source is marked TRANSFERRED and the moved account carries isProrated and the new store") {
                verify(exactly = 1) { accounts.updateStatus("acc-1", AccountStatus.TRANSFERRED) }
                moved.id shouldBe "acc-2"
                slot.captured.isProrated shouldBe true
                slot.captured.storeId shouldBe "s2"
            }
        }
    }

    Given("a transfer carrying three proofs") {
        val proofs = listOf("proof-1", "proof-2", "proof-3")
        every { accounts.findById("acc-1") } returns account(storeId = "s1")
        proofs.forEach {
            every { attachments.findById(it) } returns uploadedPdfAttachment(it, AttachmentPurpose.TRANSFER_PROOF)
        }
        every { accounts.existsByIdentity("s2", "p1", "ACC-001", null) } returns false
        every { accounts.create(any(), any()) } returns account(id = "acc-2", storeId = "s2")
        every { transfers.create(any(), any(), any(), any(), any(), any(), any()) } returns
            TransferRecord(
                id = "trn-1", oldStoreId = "s1", newStoreId = "s2",
                oldAccountId = "acc-1", newAccountId = "acc-2",
                proofId = "proof-1", proofIds = proofs,
                requestedById = "actor-1", transferDate = Instant.parse("2026-08-01T00:00:00Z"),
            )

        When("transferring") {
            useCase("acc-1", "s2", proofs, "actor-1")

            Then("only the first proof goes on the transfer row (the legacy single-proof column)") {
                verify(exactly = 1) {
                    transfers.create("s1", "s2", "acc-1", "acc-2", "proof-1", "actor-1", any())
                }
            }
            Then("the full set links to BOTH the source and destination account") {
                verify(exactly = 1) {
                    accounts.linkProofs("acc-1", proofs, AttachmentPurpose.TRANSFER_PROOF, "actor-1", "trn-1")
                }
                verify(exactly = 1) {
                    accounts.linkProofs("acc-2", proofs, AttachmentPurpose.TRANSFER_PROOF, "actor-1", "trn-1")
                }
            }
        }
    }

    Given("a transfer carrying four proofs") {
        every { accounts.findById("acc-1") } returns account(storeId = "s1")
        (1..4).forEach {
            every { attachments.findById("proof-$it") } returns
                uploadedPdfAttachment("proof-$it", AttachmentPurpose.TRANSFER_PROOF)
        }

        When("transferring") {
            Then("throws Validation and does not touch the source account") {
                val error = shouldThrow<DomainError.Validation> {
                    useCase("acc-1", "s2", listOf("proof-1", "proof-2", "proof-3", "proof-4"), "actor-1")
                }
                error.message shouldBe "at most 3 files may be attached to transfer proof"
                verify(exactly = 0) { accounts.updateStatus(any(), any()) }
            }
        }
    }

    Given("a transfer whose proof is a subscription proof") {
        every { accounts.findById("acc-1") } returns account(storeId = "s1")
        every { attachments.findById("sub-1") } returns
            uploadedPdfAttachment("sub-1", AttachmentPurpose.SUBSCRIPTION_PROOF)

        When("transferring") {
            Then("throws Validation — the purpose must match the activity") {
                val error = shouldThrow<DomainError.Validation> {
                    useCase("acc-1", "s2", listOf("sub-1"), "actor-1")
                }
                error.message shouldBe "transfer proof must reference a transfer_proof attachment"
            }
        }
    }
})
