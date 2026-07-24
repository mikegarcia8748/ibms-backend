package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ExpireGracePeriodAccountsUseCase
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private fun terminationRequested(id: String, requestedAt: Instant) = Account(
    id = id, accountNumber = "acc-$id", providerId = "p1", storeId = "s1",
    rate = "1000", installationDate = LocalDate(2025, 1, 1),
    status = AccountStatus.TERMINATION_REQUESTED, terminationRequestedAt = requestedAt,
    createdAt = Instant.fromEpochSeconds(0),
)

/** The daily grace job: only accounts past their 30-day window expire. */
class ExpireGracePeriodAccountsUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>(relaxed = true)
    val clock = FakeClock(Instant.parse("2026-08-01T00:00:00Z"))
    val useCase = ExpireGracePeriodAccountsUseCase(accounts, clock, ImmediateTransactionRunner())

    Given("one account past grace and one still within it") {
        // requested 2026-06-25 -> grace ends 2026-07-25 (< now 2026-08-01): expired
        val expired = terminationRequested("exp", Instant.parse("2026-06-25T00:00:00Z"))
        // The DB-side findExpiredGrace only returns accounts past their grace period
        every { accounts.findExpiredGrace(any()) } returns listOf(expired)

        When("running the job") {
            val count = useCase()
            Then("only the expired account is moved to inactive") {
                count shouldBe 1
                verify(exactly = 1) { accounts.findExpiredGrace(any()) }
                verify(exactly = 1) { accounts.updateStatus("exp", AccountStatus.INACTIVE) }
            }
        }
    }

    Given("no accounts awaiting termination") {
        every { accounts.findExpiredGrace(any()) } returns emptyList()
        When("running the job") {
            Then("nothing is expired") {
                useCase() shouldBe 0
                verify(exactly = 1) { accounts.findExpiredGrace(any()) }
            }
        }
    }
})
