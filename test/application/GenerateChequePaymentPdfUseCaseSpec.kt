package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.GenerateChequePaymentPdfUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant

private fun paidTopsheet(cheque: String?) = TopSheet(
    id = "ts1", invoiceNumber = "CONV-202607-0001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 2, totalAmount = "2000.00",
    status = TopSheetStatus.PAID, compilerId = "compiler",
    paidAt = Instant.parse("2026-07-20T02:00:00Z"), chequeNumber = cheque,
    compilationDate = Instant.fromEpochSeconds(0),
)

private fun line(id: String, prorated: String, store: String?) = TopSheetDetail(
    id = id, topsheetId = "ts1", accountId = "a-$id", billingPeriod = "2026-07",
    proratedAmount = prorated, fullAmount = prorated, branchCode = "BR-$id",
    storeName = store, circuitId = "CID-$id", accountNumber = "ACC-$id",
)

/** Cheque Payment Voucher (PDF). Proven with mocks (no DB); asserts %PDF magic + guard. */
class GenerateChequePaymentPdfUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val useCase = GenerateChequePaymentPdfUseCase(topsheets, ImmediateTransactionRunner())

    Given("a PAID topsheet with a cheque number and lines") {
        every { topsheets.findById("ts1") } returns paidTopsheet("CHQ-0001")
        every { topsheets.findLines("ts1") } returns listOf(
            line("1", "1200.00", "SM North"),
            line("2", "800.00", "SM South"),
        )

        When("generating the PDF") {
            val export = useCase("ts1")

            Then("it returns a non-empty PDF named for the invoice") {
                export.fileName shouldEndWith ".pdf"
                export.fileName shouldBe "Cheque_CONV-202607-0001_2026-07.pdf"
                export.bytes.isNotEmpty() shouldBe true
                export.bytes.copyOfRange(0, 4).decodeToString() shouldBe "%PDF"
            }
        }
    }

    Given("a topsheet with no cheque number yet") {
        every { topsheets.findById("ts1") } returns paidTopsheet(null)
        every { topsheets.findLines("ts1") } returns emptyList()

        When("generating the PDF") {
            Then("it is rejected with a Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("ts1") }
            }
        }
    }

    Given("an unknown topsheet id") {
        every { topsheets.findById("nope") } returns null

        When("generating the PDF") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("nope") }
            }
        }
    }
})
