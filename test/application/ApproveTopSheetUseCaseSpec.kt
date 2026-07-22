package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ApproveTopSheetUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant

private val clock = FakeClock(Instant.parse("2026-07-15T08:00:00Z"))

private fun topsheet(status: TopSheetStatus, compilerId: String = "compiler") = TopSheet(
    id = "ts1", invoiceNumber = "CONV-202607-0001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 2, totalAmount = "2000.00",
    status = status, compilerId = compilerId, compilationDate = Instant.fromEpochSeconds(0),
)

/**
 * Finance sign-off: COMPILED -> APPROVED. Proven with mocks (no DB). Unlike
 * Draft/Compile/Confirm/Pay, this use case has no Idempotency-Key support; a
 * retried approve naturally 409s instead of replaying (documented, not fixed here).
 */
class ApproveTopSheetUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val useCase = ApproveTopSheetUseCase(topsheets, activity, clock, ImmediateTransactionRunner())

    Given("a COMPILED topsheet") {
        val compiled = topsheet(TopSheetStatus.COMPILED)
        val approved = topsheet(TopSheetStatus.COMPILED).copy(
            status = TopSheetStatus.APPROVED, approvedByFinanceId = "finance1", approvedAt = clock.now(),
        )
        every { topsheets.findById("ts1") } returns compiled
        every { topsheets.approve("ts1", "finance1", clock.now()) } returns approved

        When("approving") {
            val result = useCase("ts1", "finance1")

            Then("it transitions to APPROVED and records activity") {
                result.status shouldBe TopSheetStatus.APPROVED
                result.approvedByFinanceId shouldBe "finance1"
                verify { activity.record("finance1", "topsheet.approved", "topsheet", "ts1") }
            }
        }
    }

    Given("a topsheet that is still DRAFT") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.DRAFT)

        When("approving") {
            Then("it is rejected with a Conflict (only COMPILED can be approved)") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "finance1") }
                verify(exactly = 0) { topsheets.approve(any(), any(), any()) }
            }
        }
    }

    Given("a topsheet that is already APPROVED") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.APPROVED)

        When("approving again") {
            Then("it is rejected with a Conflict rather than replaying (no idempotency support here)") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "finance1") }
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findById("nope") } returns null

        When("approving") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("nope", "finance1") }
            }
        }
    }

    Given("a topsheet compiled and approved by the same user") {
        val compiled = topsheet(TopSheetStatus.COMPILED, compilerId = "same-user")
        val approved = compiled.copy(status = TopSheetStatus.APPROVED, approvedByFinanceId = "same-user")
        every { topsheets.findById("ts1") } returns compiled
        every { topsheets.approve("ts1", "same-user", clock.now()) } returns approved

        When("that same user approves their own compilation") {
            Then("it currently succeeds -- no segregation-of-duties check exists (gap, not fixed here)") {
                val result = useCase("ts1", "same-user")
                result.status shouldBe TopSheetStatus.APPROVED
            }
        }
    }
})
