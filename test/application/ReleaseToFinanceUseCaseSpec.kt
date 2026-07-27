package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ReleaseToFinanceUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetLineStatus
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.RfpGateway
import com.puregoldbe.ibms.domain.port.RfpReleaseResult
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

private fun releaseTopsheet(status: TopSheetStatus) = TopSheet(
    id = "ts1", invoiceNumber = "CONV-202607-0001", batchNumber = "CONV-202607-B001",
    billingPeriod = "2026-07", providerId = "p1", providerName = "Converge", accountCount = 1,
    totalAmount = "1000.00", status = status, compilerId = "compiler",
    compilationDate = Instant.fromEpochSeconds(0),
)

private fun releaseLine() = TopSheetDetail(
    id = "l1", topsheetId = "ts1", accountId = "a1", billingPeriod = "2026-07",
    proratedAmount = "1000.00", fullAmount = "1000.00", status = TopSheetLineStatus.BILLED,
    rfpNumber = "0100001", rfpUniqueKey = "KEY-l1", rfpSortOrder = 1,
)

/** Secretary release-to-finance handoff (RFP_ASSIGNED -> APPROVED). Mocks + fake gateway. */
class ReleaseToFinanceUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val rfp = mockk<RfpGateway>()
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val clock = FakeClock()
    val useCase = ReleaseToFinanceUseCase(topsheets, rfp, activity, clock, ImmediateTransactionRunner())

    Given("an RFP_ASSIGNED topsheet") {
        every { topsheets.findById("ts1") } returns releaseTopsheet(TopSheetStatus.RFP_ASSIGNED)
        every { topsheets.findLines("ts1") } returns listOf(releaseLine())
        every { topsheets.releaseToFinance("ts1", "caller", clock.now()) } returns releaseTopsheet(TopSheetStatus.APPROVED)

        When("the external system accepts the release") {
            every { rfp.notifyReleaseToFinance(any()) } returns RfpReleaseResult(success = true)
            val result = useCase("ts1", "caller")

            Then("the topsheet is released to finance and the action is audited") {
                result.status shouldBe TopSheetStatus.APPROVED
                verify(exactly = 1) { rfp.notifyReleaseToFinance(any()) }
                verify(exactly = 1) { topsheets.releaseToFinance("ts1", "caller", clock.now()) }
                verify(exactly = 1) { activity.record("caller", "topsheet.released_to_finance", "topsheet", "ts1", any()) }
            }
        }

        When("the external system rejects the release") {
            every { rfp.notifyReleaseToFinance(any()) } returns RfpReleaseResult(success = false)

            Then("it is a Conflict and the local status is not changed") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "caller") }
                verify(exactly = 0) { topsheets.releaseToFinance(any(), any(), any()) }
            }
        }
    }

    Given("a topsheet that is not RFP_ASSIGNED (still COMPILED)") {
        every { topsheets.findById("ts1") } returns releaseTopsheet(TopSheetStatus.COMPILED)

        When("releasing to finance") {
            Then("it is rejected with a Conflict and no external call is made") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "caller") }
                verify(exactly = 0) { rfp.notifyReleaseToFinance(any()) }
                verify(exactly = 0) { topsheets.releaseToFinance(any(), any(), any()) }
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findById("nope") } returns null

        When("releasing to finance") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("nope", "caller") }
            }
        }
    }
})
