package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.DeactivateAccountUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.FakeIdempotencyKeyRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import com.puregoldbe.ibms.support.uploadedPdfAttachment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private fun activeAccount(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", circuitId = "CIRC-1", providerId = "p1", storeId = "s1",
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    status = AccountStatus.ACTIVE,
    createdAt = Instant.fromEpochSeconds(0),
)

private fun terminationRequestedAccount(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", circuitId = "CIRC-1", providerId = "p1", storeId = "s1",
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    status = AccountStatus.TERMINATION_REQUESTED,
    terminationRequestedAt = Instant.parse("2026-08-01T00:00:00Z"),
    graceEndDate = Instant.parse("2026-08-31T00:00:00Z"),
    createdAt = Instant.fromEpochSeconds(0),
)

private fun transferredAccount(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    status = AccountStatus.TRANSFERRED,
    createdAt = Instant.fromEpochSeconds(0),
)

private fun inactiveAccount(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    status = AccountStatus.INACTIVE,
    createdAt = Instant.fromEpochSeconds(0),
)

class DeactivateAccountUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>(relaxed = true)
    val stores = mockk<StoreRepository>(relaxed = true)
    val attachments = mockk<AttachmentRepository>(relaxed = true)
    val idempotency = FakeIdempotencyKeyRepository()
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val notifications = mockk<NotificationEnqueuer>(relaxed = true)
    val clock = FakeClock(Instant.parse("2026-08-01T00:00:00Z"))
    val useCase = DeactivateAccountUseCase(
        accounts, stores, attachments, idempotency, activity, notifications, clock, ImmediateTransactionRunner(),
    )

    beforeTest {
        every { stores.findById("s1") } returns Store(
            id = "s1", storeType = StoreType.PUREGOLD, branchCode = "001", name = "Main",
            status = StoreStatus.ACTIVE, proofOfInstallationId = "inst-1",
            createdAt = Instant.fromEpochSeconds(0),
        )
    }

    Given("an active account") {
        val account = activeAccount()
        every { accounts.findByIdForUpdate("acc-1") } returns account
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        When("deactivating with valid proof") {
            val result = useCase("acc-1", listOf("proof-1"), "actor-1")
            Then("status becomes TERMINATION_REQUESTED and graceEndDate is set") {
                result.status shouldBe AccountStatus.TERMINATION_REQUESTED
                result.graceEndDate shouldNotBe null
                result.terminationRequestedAt shouldNotBe null
            }
        }
    }

    Given("a non-active account (TERMINATION_REQUESTED)") {
        every { accounts.findByIdForUpdate("acc-1") } returns terminationRequestedAccount()
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)

        When("deactivating") {
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", listOf("proof-1"), "actor-1")
                }
            }
        }
    }

    Given("a transferred account") {
        every { accounts.findByIdForUpdate("acc-1") } returns transferredAccount()
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)

        When("deactivating") {
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", listOf("proof-1"), "actor-1")
                }
            }
        }
    }

    Given("an inactive account") {
        every { accounts.findByIdForUpdate("acc-1") } returns inactiveAccount()
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)

        When("deactivating") {
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", listOf("proof-1"), "actor-1")
                }
            }
        }
    }

    Given("valid deactivation with proof linking") {
        val account = activeAccount()
        every { accounts.findByIdForUpdate("acc-1") } returns account
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        When("deactivating") {
            useCase("acc-1", listOf("proof-1"), "actor-1")
            Then("proof is linked to account tagged as a deactivation proof") {
                verify(exactly = 1) {
                    accounts.linkProofs("acc-1", listOf("proof-1"), AttachmentPurpose.DEACTIVATION_PROOF, "actor-1", null)
                }
            }
        }
    }

    Given("a deactivation carrying the maximum three proofs") {
        every { accounts.findByIdForUpdate("acc-1") } returns activeAccount()
        listOf("proof-1", "proof-2", "proof-3").forEach {
            every { attachments.findById(it) } returns uploadedPdfAttachment(it, AttachmentPurpose.DEACTIVATION_PROOF)
        }
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        When("deactivating") {
            useCase("acc-1", listOf("proof-1", "proof-2", "proof-3"), "actor-1")
            Then("all three link in a single call, preserving order") {
                verify(exactly = 1) {
                    accounts.linkProofs(
                        "acc-1",
                        listOf("proof-1", "proof-2", "proof-3"),
                        AttachmentPurpose.DEACTIVATION_PROOF,
                        "actor-1",
                        null,
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

    Given("a deactivation carrying four proofs") {
        every { accounts.findByIdForUpdate("acc-1") } returns activeAccount()
        (1..4).forEach {
            every { attachments.findById("proof-$it") } returns
                uploadedPdfAttachment("proof-$it", AttachmentPurpose.DEACTIVATION_PROOF)
        }

        When("deactivating") {
            Then("throws Validation naming the cap") {
                val error = shouldThrow<DomainError.Validation> {
                    useCase("acc-1", listOf("proof-1", "proof-2", "proof-3", "proof-4"), "actor-1")
                }
                error.message shouldBe "at most 3 files may be attached to deactivation proof"
            }
        }
    }

    Given("a deactivation with no proofs at all") {
        every { accounts.findByIdForUpdate("acc-1") } returns activeAccount()

        When("deactivating") {
            Then("throws Validation") {
                shouldThrow<DomainError.Validation> { useCase("acc-1", emptyList(), "actor-1") }
            }
        }
    }

    Given("a deactivation repeating the same proof") {
        every { accounts.findByIdForUpdate("acc-1") } returns activeAccount()
        every { attachments.findById("proof-1") } returns
            uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)

        When("deactivating") {
            Then("throws Validation") {
                val error = shouldThrow<DomainError.Validation> {
                    useCase("acc-1", listOf("proof-1", "proof-1"), "actor-1")
                }
                error.message shouldBe "deactivation proof contains duplicate attachment ids"
            }
        }
    }

    Given("a deactivation whose proof is a subscription proof") {
        every { accounts.findByIdForUpdate("acc-1") } returns activeAccount()
        every { attachments.findById("sub-1") } returns
            uploadedPdfAttachment("sub-1", AttachmentPurpose.SUBSCRIPTION_PROOF)

        When("deactivating") {
            Then("throws Validation — the purpose must match the activity") {
                val error = shouldThrow<DomainError.Validation> {
                    useCase("acc-1", listOf("sub-1"), "actor-1")
                }
                error.message shouldBe "deactivation proof must reference a deactivation_proof attachment"
            }
        }
    }

    Given("valid deactivation with activity recording") {
        val account = activeAccount()
        every { accounts.findByIdForUpdate("acc-1") } returns account
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        When("deactivating") {
            useCase("acc-1", listOf("proof-1"), "actor-1")
            Then("activity is recorded with correct action") {
                verify(exactly = 1) {
                    activity.record("actor-1", "account.deactivation_requested", "account", "acc-1")
                }
            }
        }
    }

    Given("invalid proofId (doesn't exist)") {
        val account = activeAccount()
        every { accounts.findByIdForUpdate("acc-1") } returns account
        every { attachments.findById("bad-proof") } returns null

        When("deactivating") {
            Then("throws Validation error") {
                shouldThrow<DomainError.Validation> {
                    useCase("acc-1", listOf("bad-proof"), "actor-1")
                }
            }
        }
    }

    Given("account not found") {
        every { accounts.findByIdForUpdate("missing") } returns null

        When("deactivating") {
            Then("throws NotFound") {
                shouldThrow<DomainError.NotFound> {
                    useCase("missing", listOf("proof-1"), "actor-1")
                }
            }
        }
    }

    Given("an account whose status moves between the guard and the write") {
        every { accounts.findByIdForUpdate("acc-1") } returns activeAccount()
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)
        // The conditional write matched no row: another transaction got there first.
        every { accounts.markTerminationRequested("acc-1", any()) } returns null

        When("deactivating") {
            Then("throws Conflict, not NotFound — the account exists, it just moved") {
                val error = shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", listOf("proof-1"), "actor-1")
                }
                error.message shouldBe "account acc-1 is no longer active; deactivation was not applied"
            }
        }
    }

    Given("a valid deactivation") {
        every { accounts.findByIdForUpdate("acc-1") } returns activeAccount()
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        When("deactivating") {
            useCase("acc-1", listOf("proof-1"), "actor-1")

            Then("the status is read under a row lock, not with a plain select") {
                verify(exactly = 1) { accounts.findByIdForUpdate("acc-1") }
                verify(exactly = 0) { accounts.findById(any()) }
            }

            Then("the grace window starts at the injected clock, not at wall time") {
                verify(exactly = 1) {
                    accounts.markTerminationRequested("acc-1", Instant.parse("2026-08-01T00:00:00Z"))
                }
            }

            Then("a notification is enqueued that can identify which circuit is retiring") {
                val ctx = slot<NotificationContext>()
                verify(exactly = 1) {
                    notifications.enqueue(NotificationEvent.ACCOUNT_DEACTIVATION_REQUESTED, capture(ctx))
                }
                val details = ctx.captured.details.toMap()
                details["Account number"] shouldBe "ACC-001"
                details["Circuit"] shouldBe "CIRC-1"
                details["Store"] shouldBe "Main (Branch 001)"
                // The deadline the whole notification exists to announce.
                details["Grace period ends"] shouldBe "2026-08-31T00:00:00Z"
            }
        }
    }

    Given("idempotency key with same request sent twice") {
        val account = activeAccount()
        every { accounts.findByIdForUpdate("acc-1") } returns account
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        val idem = IdempotencyContext(key = "idem-key-1", requestHash = "hash-abc", userId = "actor-1")

        When("same request sent twice") {
            val first = useCase("acc-1", listOf("proof-1"), "actor-1", idem)
            // Second call: account is now in TERMINATION_REQUESTED but idempotency replays
            every { accounts.findByIdForUpdate("acc-1") } returns terminationRequestedAccount()
            val second = useCase("acc-1", listOf("proof-1"), "actor-1", idem)

            Then("returns same result (replay)") {
                second.id shouldBe first.id
                second.status shouldBe first.status
            }
        }
    }

    Given("idempotency key with different request body") {
        val account = activeAccount()
        every { accounts.findByIdForUpdate("acc-1") } returns account
        every { attachments.findById("proof-1") } returns uploadedPdfAttachment("proof-1", AttachmentPurpose.DEACTIVATION_PROOF)
        every { attachments.findById("proof-2") } returns uploadedPdfAttachment("proof-2", AttachmentPurpose.DEACTIVATION_PROOF)
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        val idem1 = IdempotencyContext(key = "idem-key-2", requestHash = "hash-original", userId = "actor-1")
        val idem2 = IdempotencyContext(key = "idem-key-2", requestHash = "hash-different", userId = "actor-1")

        When("different request body sent with same key") {
            useCase("acc-1", listOf("proof-1"), "actor-1", idem1)
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", listOf("proof-2"), "actor-1", idem2)
                }
            }
        }
    }
})
