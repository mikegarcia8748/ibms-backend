package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.CancelTopSheetUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant

private fun topsheet(status: TopSheetStatus) = TopSheet(
    id = "ts1",
    invoiceNumber = if (status == TopSheetStatus.DRAFT) null else "CONV-202607-0001",
    batchNumber = if (status == TopSheetStatus.DRAFT) null else "CONV-202607-B001",
    billingPeriod = "2026-07", providerId = "p1", providerName = "Converge",
    accountCount = 2, totalAmount = "2000.00", status = status, compilerId = "compiler",
    compilationDate = Instant.fromEpochSeconds(0),
)

/**
 * Cancel/void a topsheet before RFP numbers are assigned. Allowed for DRAFT and COMPILED;
 * blocked once the topsheet has reached RFP_ASSIGNED or beyond. Proven with mocks (no DB).
 */
class CancelTopSheetUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val useCase = CancelTopSheetUseCase(topsheets, activity, ImmediateTransactionRunner())

    Given("a DRAFT topsheet") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.DRAFT)
        every { topsheets.cancel("ts1") } returns topsheet(TopSheetStatus.CANCELLED)

        When("cancelling it") {
            val result = useCase("ts1", "sec1")

            Then("it is voided and the action is recorded") {
                result.status shouldBe TopSheetStatus.CANCELLED
                verify(exactly = 1) { topsheets.cancel("ts1") }
                verify { activity.record("sec1", "topsheet.cancelled", "topsheet", "ts1") }
            }
        }
    }

    Given("a COMPILED topsheet (RFP not yet assigned)") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.COMPILED)
        every { topsheets.cancel("ts1") } returns topsheet(TopSheetStatus.CANCELLED)

        When("cancelling it") {
            val result = useCase("ts1", "sec1")

            Then("it is voided too — still before RFP assignment") {
                result.status shouldBe TopSheetStatus.CANCELLED
                verify(exactly = 1) { topsheets.cancel("ts1") }
            }
        }
    }

    listOf(TopSheetStatus.RFP_ASSIGNED, TopSheetStatus.APPROVED, TopSheetStatus.PAID).forEach { status ->
        Given("a topsheet already at $status (RFP assigned or beyond)") {
            every { topsheets.findById("ts1") } returns topsheet(status)

            When("cancelling it") {
                Then("it is rejected with a Conflict and nothing is voided") {
                    shouldThrow<DomainError.Conflict> { useCase("ts1", "sec1") }
                    verify(exactly = 0) { topsheets.cancel(any()) }
                }
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findById("nope") } returns null

        When("cancelling it") {
            Then("it fails as NotFound and nothing is voided") {
                shouldThrow<DomainError.NotFound> { useCase("nope", "sec1") }
                verify(exactly = 0) { topsheets.cancel(any()) }
            }
        }
    }

    Given("a DRAFT that loses a race (status changed before the guarded delete)") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.DRAFT)
        every { topsheets.cancel("ts1") } returns null

        When("cancelling it") {
            Then("it surfaces a Conflict rather than a silent success") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "sec1") }
            }
        }
    }
})
