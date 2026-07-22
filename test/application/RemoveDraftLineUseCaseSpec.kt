package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.RemoveDraftLineUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetLineStatus
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant

private fun draftTopsheet(status: TopSheetStatus = TopSheetStatus.DRAFT) = TopSheet(
    id = "ts1", invoiceNumber = null, batchNumber = "CONV-202607-B001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 2, totalAmount = "2000.00",
    status = status, compilerId = "compiler", compilationDate = Instant.fromEpochSeconds(0),
)

private fun line(id: String, accountId: String) = TopSheetDetail(
    id = id, topsheetId = "ts1", accountId = accountId, billingPeriod = "2026-07",
    proratedAmount = "1000.00", fullAmount = "1000.00", status = TopSheetLineStatus.BILLED,
    rfpNumber = null, rfpSortOrder = 1,
)

/**
 * Remove a line from a DRAFT topsheet. The last remaining line cannot be removed --
 * the entire draft must be deleted instead. Proven with mocks (no DB).
 */
class RemoveDraftLineUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val useCase = RemoveDraftLineUseCase(topsheets, activity, ImmediateTransactionRunner())

    Given("a DRAFT topsheet with two lines") {
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns listOf(line("l1", "a1"), line("l2", "a2"))
        every { topsheets.removeLine("l2") } returns true

        When("removing one of them") {
            useCase("ts1", "l2", "caller1")

            Then("the line is removed and activity is recorded") {
                verify(exactly = 1) { topsheets.removeLine("l2") }
                verify { activity.record("caller1", "topsheet.line_removed", "topsheet", "ts1") }
            }
        }
    }

    Given("a topsheet that is no longer DRAFT (already COMPILED)") {
        every { topsheets.findById("ts1") } returns draftTopsheet(TopSheetStatus.COMPILED)

        When("removing a line") {
            Then("it is rejected with a Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "l1", "caller1") }
                verify(exactly = 0) { topsheets.removeLine(any()) }
            }
        }
    }

    Given("a lineId that does not belong to the topsheet") {
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns listOf(line("l1", "a1"), line("l2", "a2"))

        When("removing a foreign lineId") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("ts1", "foreign-line", "caller1") }
                verify(exactly = 0) { topsheets.removeLine(any()) }
            }
        }
    }

    Given("a DRAFT topsheet with only one remaining line") {
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns listOf(line("l1", "a1"))

        When("attempting to remove the last line") {
            Then("it is rejected with a Conflict (delete the draft instead)") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "l1", "caller1") }
                verify(exactly = 0) { topsheets.removeLine(any()) }
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findById("nope") } returns null

        When("removing a line") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("nope", "l1", "caller1") }
            }
        }
    }
})
