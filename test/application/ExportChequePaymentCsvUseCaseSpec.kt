package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ExportChequePaymentCsvUseCase
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
import io.kotest.matchers.string.shouldContain
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

private fun line(id: String, prorated: String, store: String?, arrears: String = "0.00") = TopSheetDetail(
    id = id, topsheetId = "ts1", accountId = "a-$id", billingPeriod = "2026-07",
    proratedAmount = prorated, fullAmount = prorated, arrearsAmount = arrears, branchCode = "BR-$id",
    storeName = store, circuitId = "CID-$id", accountNumber = "ACC-$id",
)

/** Cheque Payment CSV. Proven with mocks (no DB); asserts structure, total, escaping, guard. */
class ExportChequePaymentCsvUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val useCase = ExportChequePaymentCsvUseCase(topsheets, ImmediateTransactionRunner())

    Given("a PAID topsheet with a cheque number and lines") {
        every { topsheets.findById("ts1") } returns paidTopsheet("CHQ-0001")
        every { topsheets.findLines("ts1") } returns listOf(
            line("1", "1200.00", "SM North"),
            line("2", "800.00", "SM South"),
        )

        When("exporting the CSV") {
            val export = useCase("ts1")
            val text = export.bytes.decodeToString()

            Then("it carries the cheque meta block, header row, and a correct grand total") {
                export.fileName shouldBe "Cheque_CONV-202607-0001_2026-07.csv"
                text shouldContain "Cheque Number,CHQ-0001"
                text shouldContain "NO.,STORE CO,STORE NAME,CID#,ACCT#,MRC,ARREARS,INVOICE NUMBER"
                text shouldContain "GRAND TOTAL,,,,,2000.00,0.00,2000.00"
            }
        }
    }

    // The voucher documents what the cheque actually paid, so its GRAND TOTAL must
    // reconcile with TopSheet.totalAmount (= Σ proratedAmount + Σ arrearsAmount).
    // Summing proratedAmount alone understated any topsheet carrying arrears.
    Given("a PAID topsheet whose lines carry arrears") {
        every { topsheets.findById("ts1") } returns paidTopsheet("CHQ-0001")
        every { topsheets.findLines("ts1") } returns listOf(
            line("1", "1200.00", "SM North", arrears = "500.00"),
            line("2", "800.00", "SM South", arrears = "0.00"),
        )

        When("exporting the CSV") {
            val text = useCase("ts1").bytes.decodeToString()

            Then("the arrears column and the combined grand total are carried") {
                text shouldContain "BR-1,SM North,CID-1,ACC-1,1200.00,500.00,CONV-202607-0001"
                text shouldContain "GRAND TOTAL,,,,,2000.00,500.00,2500.00"
            }
        }
    }

    Given("a line whose store name contains a comma") {
        every { topsheets.findById("ts1") } returns paidTopsheet("CHQ-0001")
        every { topsheets.findLines("ts1") } returns listOf(line("1", "500.00", "A, Inc."))

        When("exporting the CSV") {
            val text = useCase("ts1").bytes.decodeToString()

            Then("the field is quoted per RFC-4180") {
                text shouldContain "\"A, Inc.\""
            }
        }
    }

    Given("a topsheet with no cheque number yet") {
        every { topsheets.findById("ts1") } returns paidTopsheet(null)
        every { topsheets.findLines("ts1") } returns emptyList()

        When("exporting the CSV") {
            Then("it is rejected with a Conflict") {
                shouldThrow<DomainError.Conflict> { useCase("ts1") }
            }
        }
    }
})
