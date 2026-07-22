package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.PayTopSheetUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.FakeIdempotencyKeyRepository
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

private fun topsheet(status: TopSheetStatus) = TopSheet(
    id = "ts1", invoiceNumber = "CONV-202607-0001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 2, totalAmount = "2000.00",
    status = status, compilerId = "compiler", compilationDate = Instant.fromEpochSeconds(0),
)

/** Finance payment: APPROVED -> PAID, cascading line items. Proven with mocks (no DB). */
class PayTopSheetUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val idempotency = FakeIdempotencyKeyRepository()
    val useCase = PayTopSheetUseCase(topsheets, idempotency, clock, ImmediateTransactionRunner())

    Given("an APPROVED topsheet") {
        val approved = topsheet(TopSheetStatus.APPROVED)
        val paid = topsheet(TopSheetStatus.PAID).copy(paidAt = clock.now())
        every { topsheets.findById("ts1") } returns approved
        every { topsheets.pay("ts1", clock.now()) } returns paid

        When("paying") {
            val result = useCase("ts1")

            Then("it transitions to PAID") {
                result.status shouldBe TopSheetStatus.PAID
                result.paidAt shouldBe clock.now()
            }
        }
    }

    Given("a topsheet that is only COMPILED (not yet approved)") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.COMPILED)

        When("paying") {
            Then("it is rejected with a Conflict (only APPROVED can be paid)") {
                shouldThrow<DomainError.Conflict> { useCase("ts1") }
                verify(exactly = 0) { topsheets.pay(any(), any()) }
            }
        }
    }

    Given("a topsheet that is already PAID") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.PAID)

        When("paying again") {
            Then("it is rejected with a Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("ts1") }
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findById("nope") } returns null

        When("paying") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("nope") }
            }
        }
    }

    Given("an Idempotency-Key and two identical pay requests") {
        val approved = topsheet(TopSheetStatus.APPROVED)
        val paid = topsheet(TopSheetStatus.PAID).copy(paidAt = clock.now())
        every { topsheets.findById("ts1") } returns approved
        every { topsheets.pay("ts1", clock.now()) } returns paid
        val ctx = IdempotencyContext(key = "idem-pay-1", requestHash = "hash-1", userId = "finance1")

        When("paying twice with the same key") {
            val first = useCase("ts1", ctx)
            val second = useCase("ts1", ctx)

            Then("the second call replays the stored result and pay ran only once") {
                first.status shouldBe TopSheetStatus.PAID
                second.status shouldBe TopSheetStatus.PAID
                verify(exactly = 1) { topsheets.pay(any(), any()) }
            }
        }
    }
})
