package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.UpdateDraftLineUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetLineStatus
import com.puregoldbe.ibms.domain.model.TopSheetStatus
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

private fun draftTopsheet(status: TopSheetStatus = TopSheetStatus.DRAFT) = TopSheet(
    id = "ts1", invoiceNumber = null, batchNumber = "CONV-202607-B001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 2, totalAmount = "2000.00",
    status = status, compilerId = "compiler", compilationDate = Instant.fromEpochSeconds(0),
)

private fun line(id: String, rfpNumber: String? = null, amount: String = "1000.00") = TopSheetDetail(
    id = id, topsheetId = "ts1", accountId = "a1", billingPeriod = "2026-07",
    proratedAmount = amount, fullAmount = "1000.00", status = TopSheetLineStatus.BILLED,
    rfpNumber = rfpNumber, rfpSortOrder = 1,
)

/**
 * Edit a single line in a DRAFT topsheet. Proven with mocks (no DB). Covers two fixes
 * applied alongside these specs:
 *  - the line must actually belong to the stated topsheet (previously ungated, allowing
 *    a caller to mutate an arbitrary line as long as the *stated* topsheetId was DRAFT);
 *  - proratedAmount must be a positive, non-blank decimal (previously blank silently
 *    became "0.00" and negative amounts were accepted outright).
 */
class UpdateDraftLineUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val useCase = UpdateDraftLineUseCase(topsheets, ImmediateTransactionRunner())

    Given("a DRAFT topsheet with a line belonging to it") {
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns listOf(line("l1"))
        every { topsheets.updateLine("l1", "010001", "1200.00") } returns line("l1", "010001", "1200.00")

        When("setting the RFP number and overriding the prorated amount") {
            val result = useCase("ts1", "l1", "010001", "1200.00")

            Then("the line is updated") {
                result.rfpNumber shouldBe "010001"
                result.proratedAmount shouldBe "1200.00"
                verify(exactly = 1) { topsheets.updateLine("l1", "010001", "1200.00") }
            }
        }
    }

    Given("a topsheet that is no longer DRAFT (already COMPILED)") {
        every { topsheets.findById("ts1") } returns draftTopsheet(TopSheetStatus.COMPILED)

        When("editing a line") {
            Then("it is rejected with a Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "l1", "010001", null) }
                verify(exactly = 0) { topsheets.updateLine(any(), any(), any()) }
            }
        }
    }

    Given("a lineId that does not belong to the stated DRAFT topsheet") {
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns listOf(line("l1"))

        When("attempting to edit a foreign lineId under this topsheet's authorization") {
            Then("it is rejected as NotFound instead of silently mutating the other line") {
                shouldThrow<DomainError.NotFound> { useCase("ts1", "foreign-line", "010001", null) }
                verify(exactly = 0) { topsheets.updateLine(any(), any(), any()) }
            }
        }
    }

    Given("a DRAFT topsheet and a non-numeric RFP number") {
        every { topsheets.findById("ts1") } returns draftTopsheet()

        When("editing with letters in the RFP number") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", "RFP-01", null) }
                verify(exactly = 0) { topsheets.findLines(any()) }
            }
        }
    }

    Given("a DRAFT topsheet and a non-decimal proratedAmount") {
        every { topsheets.findById("ts1") } returns draftTopsheet()

        When("editing with garbage text as the amount") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", null, "not-a-number") }
            }
        }
    }

    Given("a DRAFT topsheet and a blank proratedAmount") {
        every { topsheets.findById("ts1") } returns draftTopsheet()

        When("editing with an empty string as the amount") {
            Then("it is rejected with a Validation error instead of silently becoming 0.00") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", null, "") }
                verify(exactly = 0) { topsheets.updateLine(any(), any(), any()) }
            }
        }
    }

    Given("a DRAFT topsheet and a negative proratedAmount") {
        every { topsheets.findById("ts1") } returns draftTopsheet()

        When("editing with a negative amount") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", null, "-500.00") }
                verify(exactly = 0) { topsheets.updateLine(any(), any(), any()) }
            }
        }
    }

    Given("a DRAFT topsheet and a zero proratedAmount") {
        every { topsheets.findById("ts1") } returns draftTopsheet()

        When("editing with an amount of exactly 0.00") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", null, "0.00") }
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findById("nope") } returns null

        When("editing a line") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("nope", "l1", "010001", null) }
            }
        }
    }
})
