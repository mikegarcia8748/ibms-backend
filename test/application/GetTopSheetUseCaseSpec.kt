package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.GetTopSheetDetailsUseCase
import com.puregoldbe.ibms.application.usecase.GetTopSheetUseCase
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant

private val topsheet = TopSheet(
    id = "ts1", invoiceNumber = "CONV-202607-0001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 1, totalAmount = "1000.00",
    status = TopSheetStatus.COMPILED, compilerId = "compiler", compilationDate = Instant.fromEpochSeconds(0),
)

private fun line(id: String) = TopSheetDetail(
    id = id, topsheetId = "ts1", accountId = "a1", billingPeriod = "2026-07",
    proratedAmount = "1000.00", fullAmount = "1000.00", status = TopSheetLineStatus.BILLED,
)

/** Single-topsheet lookups. Proven with mocks (no DB). */
class GetTopSheetUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>()
    val useCase = GetTopSheetUseCase(topsheets, ImmediateTransactionRunner())

    Given("an existing topsheet") {
        every { topsheets.findById("ts1") } returns topsheet

        When("fetching it") {
            Then("it is returned as-is") {
                useCase("ts1").id shouldBe "ts1"
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findById("nope") } returns null

        When("fetching it") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("nope") }
            }
        }
    }
})

/** Line-item lookups for a topsheet. Proven with mocks (no DB). */
class GetTopSheetDetailsUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>()
    val useCase = GetTopSheetDetailsUseCase(topsheets, ImmediateTransactionRunner())

    Given("a topsheet with lines") {
        every { topsheets.findLines("ts1") } returns listOf(line("l1"), line("l2"))

        When("fetching its details") {
            Then("both lines are returned") {
                useCase("ts1").size shouldBe 2
            }
        }
    }

    Given("a topsheet with no lines (or an unknown id)") {
        every { topsheets.findLines("ts-empty") } returns emptyList()

        When("fetching its details") {
            Then("it returns an empty list rather than throwing") {
                useCase("ts-empty").shouldBeEmpty()
            }
        }
    }
})
