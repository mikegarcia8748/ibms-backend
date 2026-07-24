package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.DeactivateAccountUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.FakeIdempotencyKeyRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private fun activeAccount(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    status = AccountStatus.ACTIVE,
    createdAt = Instant.fromEpochSeconds(0),
)

private fun terminationRequestedAccount(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
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
    val attachments = mockk<AttachmentRepository>(relaxed = true)
    val idempotency = FakeIdempotencyKeyRepository()
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val clock = FakeClock(Instant.parse("2026-08-01T00:00:00Z"))
    val useCase = DeactivateAccountUseCase(accounts, attachments, idempotency, activity, clock, ImmediateTransactionRunner())

    Given("an active account") {
        val account = activeAccount()
        every { accounts.findById("acc-1") } returns account
        every { attachments.exists("proof-1") } returns true
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        When("deactivating with valid proof") {
            val result = useCase("acc-1", "proof-1", "actor-1")
            Then("status becomes TERMINATION_REQUESTED and graceEndDate is set") {
                result.status shouldBe AccountStatus.TERMINATION_REQUESTED
                result.graceEndDate shouldNotBe null
                result.terminationRequestedAt shouldNotBe null
            }
        }
    }

    Given("a non-active account (TERMINATION_REQUESTED)") {
        every { accounts.findById("acc-1") } returns terminationRequestedAccount()
        every { attachments.exists("proof-1") } returns true

        When("deactivating") {
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", "proof-1", "actor-1")
                }
            }
        }
    }

    Given("a transferred account") {
        every { accounts.findById("acc-1") } returns transferredAccount()
        every { attachments.exists("proof-1") } returns true

        When("deactivating") {
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", "proof-1", "actor-1")
                }
            }
        }
    }

    Given("an inactive account") {
        every { accounts.findById("acc-1") } returns inactiveAccount()
        every { attachments.exists("proof-1") } returns true

        When("deactivating") {
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", "proof-1", "actor-1")
                }
            }
        }
    }

    Given("valid deactivation with proof linking") {
        val account = activeAccount()
        every { accounts.findById("acc-1") } returns account
        every { attachments.exists("proof-1") } returns true
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        When("deactivating") {
            useCase("acc-1", "proof-1", "actor-1")
            Then("proof is linked to account via linkProof()") {
                verify(exactly = 1) { accounts.linkProof("acc-1", "proof-1") }
            }
        }
    }

    Given("valid deactivation with activity recording") {
        val account = activeAccount()
        every { accounts.findById("acc-1") } returns account
        every { attachments.exists("proof-1") } returns true
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        When("deactivating") {
            useCase("acc-1", "proof-1", "actor-1")
            Then("activity is recorded with correct action") {
                verify(exactly = 1) {
                    activity.record("actor-1", "account.deactivation_requested", "account", "acc-1")
                }
            }
        }
    }

    Given("invalid proofId (doesn't exist)") {
        val account = activeAccount()
        every { accounts.findById("acc-1") } returns account
        every { attachments.exists("bad-proof") } returns false

        When("deactivating") {
            Then("throws Validation error") {
                shouldThrow<DomainError.Validation> {
                    useCase("acc-1", "bad-proof", "actor-1")
                }
            }
        }
    }

    Given("account not found") {
        every { accounts.findById("missing") } returns null

        When("deactivating") {
            Then("throws NotFound") {
                shouldThrow<DomainError.NotFound> {
                    useCase("missing", "proof-1", "actor-1")
                }
            }
        }
    }

    Given("idempotency key with same request sent twice") {
        val account = activeAccount()
        every { accounts.findById("acc-1") } returns account
        every { attachments.exists("proof-1") } returns true
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        val idem = IdempotencyContext(key = "idem-key-1", requestHash = "hash-abc", userId = "actor-1")

        When("same request sent twice") {
            val first = useCase("acc-1", "proof-1", "actor-1", idem)
            // Second call: account is now in TERMINATION_REQUESTED but idempotency replays
            every { accounts.findById("acc-1") } returns terminationRequestedAccount()
            val second = useCase("acc-1", "proof-1", "actor-1", idem)

            Then("returns same result (replay)") {
                second.id shouldBe first.id
                second.status shouldBe first.status
            }
        }
    }

    Given("idempotency key with different request body") {
        val account = activeAccount()
        every { accounts.findById("acc-1") } returns account
        every { attachments.exists("proof-1") } returns true
        every { attachments.exists("proof-2") } returns true
        every { accounts.markTerminationRequested("acc-1", any()) } returns terminationRequestedAccount()

        val idem1 = IdempotencyContext(key = "idem-key-2", requestHash = "hash-original", userId = "actor-1")
        val idem2 = IdempotencyContext(key = "idem-key-2", requestHash = "hash-different", userId = "actor-1")

        When("different request body sent with same key") {
            useCase("acc-1", "proof-1", "actor-1", idem1)
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", "proof-2", "actor-1", idem2)
                }
            }
        }
    }
})
