package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.AssignRfpNumbersUseCase
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
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant

private fun draftTopsheet(status: TopSheetStatus = TopSheetStatus.DRAFT) = TopSheet(
    id = "ts1", invoiceNumber = null, batchNumber = "CONV-202607-B001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 3, totalAmount = "3000.00",
    status = status, compilerId = "compiler", compilationDate = Instant.fromEpochSeconds(0),
)

private fun line(id: String, branchCode: String?, accountNumber: String = "acc-$id") = TopSheetDetail(
    id = id, topsheetId = "ts1", accountId = "a-$id", billingPeriod = "2026-07",
    proratedAmount = "1000.00", fullAmount = "1000.00", status = TopSheetLineStatus.BILLED,
    branchCode = branchCode, accountNumber = accountNumber,
)

/**
 * Bulk RFP-number sequencing over a DRAFT topsheet. Store codes (branchCode) are numbered
 * in display order — descending, so the top line (highest store code) claims startRfpNumber
 * and the numbers grow downward; accounts sharing a store code share an RFP number, and
 * storeless lines (null branchCode) are skipped. Proven with mocks (no DB).
 */
class AssignRfpNumbersUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val useCase = AssignRfpNumbersUseCase(topsheets, activity, ImmediateTransactionRunner())

    Given("a DRAFT topsheet whose lines are out of store-code order") {
        val lines = listOf(line("l3", "119"), line("l1", "118"), line("l2", "120"))
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns lines

        When("assigning the range 0100021..0100023") {
            useCase("ts1", "0100021", "0100023", "sec1")

            Then("the highest store code gets startRfpNumber, decreasing down the codes") {
                verify(exactly = 1) { topsheets.updateLine("l2", "0100021", null) } // 120 (highest)
                verify(exactly = 1) { topsheets.updateLine("l3", "0100022", null) } // 119
                verify(exactly = 1) { topsheets.updateLine("l1", "0100023", null) } // 118 (lowest)
                verify(exactly = 1) { activity.record("sec1", "topsheet.rfp_assigned", "topsheet", "ts1") }
            }
        }
    }

    Given("a DRAFT topsheet with two accounts sharing one store code") {
        val lines = listOf(line("l1", "118", "accA"), line("l2", "118", "accB"), line("l3", "119"))
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns lines

        When("assigning a range that matches the distinct store-code count") {
            useCase("ts1", "0100021", "0100022", "sec1")

            Then("both accounts under store 118 share the same (second) RFP number") {
                verify(exactly = 1) { topsheets.updateLine("l3", "0100021", null) } // 119 (highest)
                verify(exactly = 1) { topsheets.updateLine("l1", "0100022", null) } // 118
                verify(exactly = 1) { topsheets.updateLine("l2", "0100022", null) } // 118
            }
        }
    }

    Given("a DRAFT topsheet with a storeless line (null branchCode)") {
        val lines = listOf(line("l1", "118"), line("l2", null))
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns lines

        When("assigning a range sized for the single real store code") {
            useCase("ts1", "0100021", "0100021", "sec1")

            Then("only the coded line is numbered; the storeless line is skipped") {
                verify(exactly = 1) { topsheets.updateLine("l1", "0100021", null) }
                verify(exactly = 0) { topsheets.updateLine("l2", any(), any()) }
            }
        }
    }

    Given("a DRAFT topsheet whose codes span differing input widths") {
        // start "9" (1 digit) / end "11" (2 digits): numbers keep the wider width (2).
        val lines = listOf(line("l1", "120"), line("l2", "119"), line("l3", "118"))
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns lines

        When("assigning the range 9..11") {
            useCase("ts1", "9", "11", "sec1")

            Then("numbers are zero-padded to the wider width, highest code first") {
                verify(exactly = 1) { topsheets.updateLine("l1", "09", null) } // 120 (highest)
                verify(exactly = 1) { topsheets.updateLine("l2", "10", null) } // 119
                verify(exactly = 1) { topsheets.updateLine("l3", "11", null) } // 118 (lowest)
            }
        }
    }

    Given("a DRAFT topsheet and lines already in descending display order") {
        val lines = listOf(line("l1", "120"), line("l2", "119"), line("l3", "118"))
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns lines

        When("assigning a matching range") {
            val result = useCase("ts1", "0100021", "0100023", "sec1")

            Then("the returned lines preserve the repository (display) order, not re-sorted") {
                result.map { it.id } shouldBe listOf("l1", "l2", "l3")
            }
        }
    }

    Given("a DRAFT topsheet and a non-numeric end number") {
        every { topsheets.findById("ts1") } returns draftTopsheet()

        When("assigning with letters in the end number") {
            Then("it is rejected with a Validation error before touching lines") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "0100021", "RFP-2", "sec1") }
                verify(exactly = 0) { topsheets.findLines(any()) }
            }
        }
    }

    Given("a range whose size does not match the number of store codes") {
        val lines = listOf(line("l1", "118"), line("l2", "119"))
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns lines

        When("the range covers three numbers but there are two store codes") {
            Then("it is rejected with a Validation error and nothing is written") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "0100021", "0100023", "sec1") }
                verify(exactly = 0) { topsheets.updateLine(any(), any(), any()) }
            }
        }
    }

    Given("a topsheet that is no longer DRAFT") {
        every { topsheets.findById("ts1") } returns draftTopsheet(TopSheetStatus.COMPILED)

        When("assigning RFP numbers") {
            Then("it is rejected with a Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "0100021", "0100021", "sec1") }
                verify(exactly = 0) { topsheets.findLines(any()) }
            }
        }
    }

    Given("a DRAFT topsheet and a non-numeric start number") {
        every { topsheets.findById("ts1") } returns draftTopsheet()

        When("assigning with letters in the start number") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "RFP-1", "RFP-2", "sec1") }
                verify(exactly = 0) { topsheets.findLines(any()) }
            }
        }
    }

    Given("a DRAFT topsheet with an inverted range") {
        every { topsheets.findById("ts1") } returns draftTopsheet()

        When("end is smaller than start") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "0100023", "0100021", "sec1") }
                verify(exactly = 0) { topsheets.findLines(any()) }
            }
        }
    }

    Given("a DRAFT topsheet with no lines") {
        every { topsheets.findById("ts1") } returns draftTopsheet()
        every { topsheets.findLines("ts1") } returns emptyList()

        When("assigning RFP numbers") {
            Then("it is rejected with a Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "0100021", "0100021", "sec1") }
                verify(exactly = 0) { topsheets.updateLine(any(), any(), any()) }
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findById("nope") } returns null

        When("assigning RFP numbers") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("nope", "0100021", "0100021", "sec1") }
            }
        }
    }
})
