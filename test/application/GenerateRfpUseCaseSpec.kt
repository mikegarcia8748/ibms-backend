package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.GenerateRfpUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetLineStatus
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.domain.port.RfpGateway
import com.puregoldbe.ibms.domain.port.RfpGenerationInput
import com.puregoldbe.ibms.domain.port.RfpGenerationResult
import com.puregoldbe.ibms.domain.port.RfpLineAssignment
import com.puregoldbe.ibms.domain.port.RfpReleaseInput
import com.puregoldbe.ibms.domain.port.RfpReleaseResult
import com.puregoldbe.ibms.domain.port.TopSheetLineRfp
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.FakeIdempotencyKeyRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Instant

/** Records how many times generateRfp was called so idempotent-replay can be proven. */
private class FakeRfpGateway(
    private val omitFirst: Boolean = false,
) : RfpGateway {
    var generateCalls = 0

    override fun generateRfp(input: RfpGenerationInput): RfpGenerationResult {
        generateCalls++
        val lines = if (omitFirst) input.lines.drop(1) else input.lines
        return RfpGenerationResult(
            lines.map { RfpLineAssignment(it.lineId, "RFP-${it.lineId}", "KEY-${it.lineId}") },
        )
    }

    override fun notifyReleaseToFinance(input: RfpReleaseInput): RfpReleaseResult =
        RfpReleaseResult(success = true)
}

private fun topsheet(status: TopSheetStatus) = TopSheet(
    id = "ts1", invoiceNumber = "CONV-202607-0001", batchNumber = "CONV-202607-B001",
    billingPeriod = "2026-07", providerId = "p1", providerName = "Converge", accountCount = 2,
    totalAmount = "2000.00", status = status, compilerId = "compiler",
    compilationDate = Instant.fromEpochSeconds(0),
)

private fun line(id: String) = TopSheetDetail(
    id = id, topsheetId = "ts1", accountId = "a-$id", billingPeriod = "2026-07",
    proratedAmount = "1000.00", fullAmount = "1000.00", status = TopSheetLineStatus.BILLED,
    branchCode = "11$id", rfpSortOrder = 1,
)

/**
 * Generate RFP numbers for a COMPILED topsheet via the external system. Proven with
 * mocks + a fake gateway (no DB, no network).
 */
class GenerateRfpUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val activity = mockk<ActivityRecorder>(relaxed = true)

    Given("a COMPILED topsheet with two lines") {
        val gateway = FakeRfpGateway()
        val idempotency = FakeIdempotencyKeyRepository()
        val useCase = GenerateRfpUseCase(topsheets, gateway, idempotency, activity, ImmediateTransactionRunner())
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.COMPILED)
        every { topsheets.findLines("ts1") } returns listOf(line("1"), line("2"))
        every { topsheets.assignExternalRfp("ts1", any()) } returns topsheet(TopSheetStatus.RFP_ASSIGNED)

        When("generating RFP numbers") {
            val captured = slot<List<TopSheetLineRfp>>()
            every { topsheets.assignExternalRfp("ts1", capture(captured)) } returns topsheet(TopSheetStatus.RFP_ASSIGNED)
            useCase("ts1", "caller")

            Then("each line's RFP number + unique key from the gateway are persisted") {
                gateway.generateCalls shouldBe 1
                captured.captured.map { it.lineId } shouldBe listOf("1", "2")
                captured.captured.map { it.rfpNumber } shouldBe listOf("RFP-1", "RFP-2")
                captured.captured.map { it.uniqueKey } shouldBe listOf("KEY-1", "KEY-2")
                verify(exactly = 1) { activity.record("caller", "topsheet.rfp_assigned", "topsheet", "ts1", any()) }
            }
        }
    }

    Given("a topsheet that is not COMPILED (still DRAFT)") {
        val gateway = FakeRfpGateway()
        val useCase = GenerateRfpUseCase(topsheets, gateway, FakeIdempotencyKeyRepository(), activity, ImmediateTransactionRunner())
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.DRAFT)

        When("generating RFP numbers") {
            Then("it is rejected with a Conflict and no external call is made") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "caller") }
                gateway.generateCalls shouldBe 0
                verify(exactly = 0) { topsheets.assignExternalRfp(any(), any()) }
            }
        }
    }

    Given("a COMPILED topsheet with no lines") {
        val gateway = FakeRfpGateway()
        val useCase = GenerateRfpUseCase(topsheets, gateway, FakeIdempotencyKeyRepository(), activity, ImmediateTransactionRunner())
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.COMPILED)
        every { topsheets.findLines("ts1") } returns emptyList()

        When("generating RFP numbers") {
            Then("it is rejected with a Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "caller") }
                gateway.generateCalls shouldBe 0
            }
        }
    }

    Given("an external system that returns fewer assignments than there are lines") {
        val gateway = FakeRfpGateway(omitFirst = true)
        val useCase = GenerateRfpUseCase(topsheets, gateway, FakeIdempotencyKeyRepository(), activity, ImmediateTransactionRunner())
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.COMPILED)
        every { topsheets.findLines("ts1") } returns listOf(line("1"), line("2"))

        When("generating RFP numbers") {
            Then("it is rejected with a Conflict and nothing is persisted") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "caller") }
                verify(exactly = 0) { topsheets.assignExternalRfp(any(), any()) }
            }
        }
    }

    Given("a COMPILED topsheet and a repeated Idempotency-Key") {
        val gateway = FakeRfpGateway()
        val idempotency = FakeIdempotencyKeyRepository()
        val useCase = GenerateRfpUseCase(topsheets, gateway, idempotency, activity, ImmediateTransactionRunner())
        every { topsheets.findById("ts1") } returns topsheet(TopSheetStatus.COMPILED)
        every { topsheets.findLines("ts1") } returns listOf(line("1"), line("2"))
        every { topsheets.assignExternalRfp("ts1", any()) } returns topsheet(TopSheetStatus.RFP_ASSIGNED)
        val idem = IdempotencyContext(key = "k1", requestHash = "h1", userId = "caller")

        When("the same request is sent twice") {
            val first = useCase("ts1", "caller", idem)
            val second = useCase("ts1", "caller", idem)

            Then("the external system is called only once and the stored result is replayed") {
                gateway.generateCalls shouldBe 1
                second shouldBe first
            }
        }
    }
})
