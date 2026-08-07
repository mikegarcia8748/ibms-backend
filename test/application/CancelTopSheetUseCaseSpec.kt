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
            val result = useCase("ts1", "sec1", "duplicate batch")

            Then("it is voided and the reason is recorded on the audit trail") {
                result.status shouldBe TopSheetStatus.CANCELLED
                verify(exactly = 1) { topsheets.cancel("ts1") }
                verify { activity.record("sec1", "topsheet.cancelled", "topsheet", "ts1", "duplicate batch") }
            }
        }
    }

    Given("a COMPILED topsheet (RFP not yet assigned)") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.COMPILED)
        every { topsheets.cancel("ts1") } returns topsheet(TopSheetStatus.CANCELLED)

        When("cancelling it") {
            val result = useCase("ts1", "sec1", "duplicate batch")

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
                    shouldThrow<DomainError.Conflict> { useCase("ts1", "sec1", "duplicate batch") }
                    verify(exactly = 0) { topsheets.cancel(any()) }
                }
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findById("nope") } returns null

        When("cancelling it") {
            Then("it fails as NotFound and nothing is voided") {
                shouldThrow<DomainError.NotFound> { useCase("nope", "sec1", "duplicate batch") }
                verify(exactly = 0) { topsheets.cancel(any()) }
            }
        }
    }

    // Cancelling deletes the line items, which erases a billing-history record carrying a
    // minted invoice number and frees the accounts to be billed again. With the RFP chain
    // off, COMPILED is terminal, so nothing else ever closes that window — the stated
    // reason is what makes the erasure attributable.
    Given("a cancel request with no reason") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.COMPILED)

        listOf("" to "empty", "   " to "whitespace-only").forEach { (reason, kind) ->
            When("the reason is $kind") {
                Then("it is rejected as a Validation before the repository is touched") {
                    shouldThrow<DomainError.Validation> { useCase("ts1", "sec1", reason) }
                    verify(exactly = 0) { topsheets.findById(any()) }
                    verify(exactly = 0) { topsheets.cancel(any()) }
                }
            }
        }
    }

    Given("a cancel reason longer than the 500-character limit") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.COMPILED)

        When("cancelling with it") {
            Then("it is rejected as a Validation and nothing is voided") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "sec1", "x".repeat(501)) }
                verify(exactly = 0) { topsheets.cancel(any()) }
            }
        }
    }

    Given("a cancel reason padded with whitespace") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.COMPILED)
        every { topsheets.cancel("ts1") } returns topsheet(TopSheetStatus.CANCELLED)

        When("cancelling with it") {
            useCase("ts1", "sec1", "  wrong billing period  ")

            Then("the trimmed reason is what reaches the audit trail") {
                verify { activity.record("sec1", "topsheet.cancelled", "topsheet", "ts1", "wrong billing period") }
            }
        }
    }

    Given("a DRAFT that loses a race (status changed before the guarded delete)") {
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.DRAFT)
        every { topsheets.cancel("ts1") } returns null

        When("cancelling it") {
            Then("it surfaces a Conflict rather than a silent success") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "sec1", "duplicate batch") }
            }
        }
    }
})
