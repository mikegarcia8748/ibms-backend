package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.CancelDeactivationUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private fun terminationRequestedAccount(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    status = AccountStatus.TERMINATION_REQUESTED,
    terminationRequestedAt = Instant.parse("2026-08-01T00:00:00Z"),
    graceEndDate = Instant.parse("2026-08-31T00:00:00Z"),
    createdAt = Instant.fromEpochSeconds(0),
)

private fun activeAccount(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    status = AccountStatus.ACTIVE,
    createdAt = Instant.fromEpochSeconds(0),
)

private fun inactiveAccount(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    status = AccountStatus.INACTIVE,
    createdAt = Instant.fromEpochSeconds(0),
)

private fun cancelledResult(id: String = "acc-1") = Account(
    id = id, accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
    rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
    status = AccountStatus.ACTIVE,
    terminationRequestedAt = null,
    graceEndDate = null,
    createdAt = Instant.fromEpochSeconds(0),
)

class CancelDeactivationUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>(relaxed = true)
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val useCase = CancelDeactivationUseCase(accounts, activity, ImmediateTransactionRunner())

    Given("an account in TERMINATION_REQUESTED") {
        every { accounts.findByIdForUpdate("acc-1") } returns terminationRequestedAccount()
        every { accounts.cancelTerminationRequested("acc-1") } returns cancelledResult()

        When("cancelling with reason") {
            val result = useCase("acc-1", "Customer changed their mind", "actor-1")
            Then("status reverts to ACTIVE") {
                result.status shouldBe AccountStatus.ACTIVE
            }
            Then("the read takes a row lock, so the expiry job cannot archive it mid-cancel") {
                verify(exactly = 1) { accounts.findByIdForUpdate("acc-1") }
                verify(exactly = 0) { accounts.findById(any()) }
            }
        }
    }

    Given("the grace-expiry job winning the race between the guard and the write") {
        every { accounts.findByIdForUpdate("acc-1") } returns terminationRequestedAccount()
        // The conditional write matched no row: the account is already inactive.
        every { accounts.cancelTerminationRequested("acc-1") } returns null

        When("cancelling") {
            Then("throws Conflict rather than reporting a cancellation that did not happen") {
                val error = shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", "Customer changed their mind", "actor-1")
                }
                error.message shouldBe
                    "account acc-1 is no longer in termination_requested status; cancellation was not applied"
            }
        }
    }

    Given("an account in TERMINATION_REQUESTED for graceEndDate clearing") {
        every { accounts.findByIdForUpdate("acc-1") } returns terminationRequestedAccount()
        every { accounts.cancelTerminationRequested("acc-1") } returns cancelledResult()

        When("cancelling") {
            val result = useCase("acc-1", "Restored by manager request", "actor-1")
            Then("terminationRequestedAt is cleared (graceEndDate is null)") {
                result.terminationRequestedAt.shouldBeNull()
                result.graceEndDate.shouldBeNull()
            }
        }
    }

    Given("an ACTIVE account") {
        every { accounts.findByIdForUpdate("acc-1") } returns activeAccount()

        When("cancelling") {
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", "some reason", "actor-1")
                }
            }
        }
    }

    Given("an INACTIVE account") {
        every { accounts.findByIdForUpdate("acc-1") } returns inactiveAccount()

        When("cancelling") {
            Then("throws Conflict") {
                shouldThrow<DomainError.Conflict> {
                    useCase("acc-1", "some reason", "actor-1")
                }
            }
        }
    }

    Given("valid cancellation with activity recording") {
        every { accounts.findByIdForUpdate("acc-1") } returns terminationRequestedAccount()
        every { accounts.cancelTerminationRequested("acc-1") } returns cancelledResult()

        When("cancelling") {
            useCase("acc-1", "Customer retained", "actor-1")
            Then("activity is recorded with action account.deactivation_cancelled and the reason") {
                verify(exactly = 1) {
                    activity.record("actor-1", "account.deactivation_cancelled", "account", "acc-1", "Customer retained")
                }
            }
        }
    }

    Given("a termination-requested account with a blank reason") {
        every { accounts.findByIdForUpdate("acc-1") } returns terminationRequestedAccount()

        When("cancelling with a blank reason") {
            Then("throws Validation and does not cancel") {
                shouldThrow<DomainError.Validation> {
                    useCase("acc-1", "   ", "actor-1")
                }
                verify(exactly = 0) { accounts.cancelTerminationRequested(any()) }
            }
        }
    }

    Given("account not found") {
        every { accounts.findByIdForUpdate("missing") } returns null

        When("cancelling") {
            Then("throws NotFound") {
                shouldThrow<DomainError.NotFound> {
                    useCase("missing", "reason", "actor-1")
                }
            }
        }
    }
})
