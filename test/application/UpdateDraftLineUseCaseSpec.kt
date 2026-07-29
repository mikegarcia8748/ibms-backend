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

private fun line(id: String, amount: String = "1000.00") = TopSheetDetail(
    id = id, topsheetId = "ts1", accountId = "a1", billingPeriod = "2026-07",
    proratedAmount = amount, fullAmount = "1000.00", status = TopSheetLineStatus.BILLED,
    rfpSortOrder = 1,
)

/**
 * Edit a single DRAFT line's prorated amount (RFP is assigned by the external system
 * after confirm, not here). Proven with mocks (no DB). Covers: the line must belong to
 * the stated topsheet, and the amount must be a positive, non-blank decimal.
 */
class UpdateDraftLineUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val useCase = UpdateDraftLineUseCase(topsheets, ImmediateTransactionRunner())

    Given("a DRAFT topsheet with a line belonging to it") {
        every { topsheets.findByIdForUpdate("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns listOf(line("l1"))
        every { topsheets.updateLineAmount("l1", "950.00") } returns line("l1", "950.00")

        When("overriding the prorated amount (within the full monthly charge)") {
            val result = useCase("ts1", "l1", "950.00")

            Then("the line is updated") {
                result.proratedAmount shouldBe "950.00"
                verify(exactly = 1) { topsheets.updateLineAmount("l1", "950.00") }
            }
        }
    }

    Given("a DRAFT topsheet and an override above the line's full monthly charge") {
        every { topsheets.findByIdForUpdate("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns listOf(line("l1"))

        When("editing with an amount greater than fullAmount (1000.00)") {
            Then("it is rejected with a Validation error (cannot exceed the full charge)") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", "5000.00") }
                verify(exactly = 0) { topsheets.updateLineAmount(any(), any()) }
            }
        }
    }

    Given("a topsheet that is no longer DRAFT (already COMPILED)") {
        every { topsheets.findByIdForUpdate("ts1") } returns draftTopsheet(TopSheetStatus.COMPILED)

        When("editing a line") {
            Then("it is rejected with a Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "l1", "1200.00") }
                verify(exactly = 0) { topsheets.updateLineAmount(any(), any()) }
            }
        }
    }

    Given("a lineId that does not belong to the stated DRAFT topsheet") {
        every { topsheets.findByIdForUpdate("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns listOf(line("l1"))

        When("attempting to edit a foreign lineId under this topsheet's authorization") {
            Then("it is rejected as NotFound instead of silently mutating the other line") {
                shouldThrow<DomainError.NotFound> { useCase("ts1", "foreign-line", "1200.00") }
                verify(exactly = 0) { topsheets.updateLineAmount(any(), any()) }
            }
        }
    }

    Given("a DRAFT topsheet and a PATCH with no proratedAmount") {
        every { topsheets.findByIdForUpdate("ts1") } returns draftTopsheet()

        When("editing with a null amount") {
            Then("it is rejected with a Validation error instead of a silent no-op 200") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", null) }
                verify(exactly = 0) { topsheets.findLines(any()) }
                verify(exactly = 0) { topsheets.updateLineAmount(any(), any()) }
            }
        }
    }

    Given("a DRAFT topsheet and a non-decimal proratedAmount") {
        every { topsheets.findByIdForUpdate("ts1") } returns draftTopsheet()

        When("editing with garbage text as the amount") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", "not-a-number") }
            }
        }
    }

    Given("a DRAFT topsheet and a blank proratedAmount") {
        every { topsheets.findByIdForUpdate("ts1") } returns draftTopsheet()

        When("editing with an empty string as the amount") {
            Then("it is rejected with a Validation error instead of silently becoming 0.00") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", "") }
                verify(exactly = 0) { topsheets.updateLineAmount(any(), any()) }
            }
        }
    }

    Given("a DRAFT topsheet and a negative proratedAmount") {
        every { topsheets.findByIdForUpdate("ts1") } returns draftTopsheet()

        When("editing with a negative amount") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", "-500.00") }
                verify(exactly = 0) { topsheets.updateLineAmount(any(), any()) }
            }
        }
    }

    Given("a DRAFT topsheet and a zero proratedAmount") {
        every { topsheets.findByIdForUpdate("ts1") } returns draftTopsheet()

        When("editing with an amount of exactly 0.00") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "l1", "0.00") }
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findByIdForUpdate("nope") } returns null

        When("editing a line") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("nope", "l1", "1200.00") }
            }
        }
    }
})
