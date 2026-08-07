package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ExportTopSheetExcelUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream

/**
 * O-T1: the exported GRAND TOTAL must reconcile with the stored invoice total
 * (TopSheet.totalAmount = Σ proratedAmount + Σ arrearsAmount). Before the fix the
 * export summed proratedAmount only, understating any topsheet with an arrears line.
 */
class ExportTopSheetExcelUseCaseSpec : BehaviorSpec({

    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val useCase = ExportTopSheetExcelUseCase(topsheets, ImmediateTransactionRunner())

    fun line(id: String, prorated: String, arrears: String) = TopSheetDetail(
        id = id, topsheetId = "ts-1", accountId = "acc-$id", billingPeriod = "2026-07",
        proratedAmount = prorated, fullAmount = "1000.00", arrearsAmount = arrears,
        accountNumber = "ACCT-$id",
    )

    Given("a confirmed topsheet with an arrears line (Σ prorated = 2000, Σ arrears = 500)") {
        every { topsheets.findById("ts-1") } returns TopSheet(
            id = "ts-1", invoiceNumber = "CONV-202607-0001", billingPeriod = "2026-07",
            providerName = "Converge", accountCount = 2, totalAmount = "2500.00",
            compilerId = "u1", compilationDate = Instant.parse("2026-07-05T00:00:00Z"),
        )
        every { topsheets.findLines("ts-1") } returns listOf(
            line("1", prorated = "1000.00", arrears = "500.00"),
            line("2", prorated = "1000.00", arrears = "0.00"),
        )

        When("exporting to Excel") {
            val export = useCase("ts-1")
            val rows: List<Row> = XSSFWorkbook(ByteArrayInputStream(export.bytes)).use { wb ->
                val sheet = wb.getSheetAt(0)
                (0..sheet.lastRowNum).mapNotNull { sheet.getRow(it) }
            }
            fun rowStartingWith(label: String): Row = rows.first { row ->
                val c0 = row.getCell(0)
                c0 != null && c0.cellType == CellType.STRING && c0.stringCellValue == label
            }
            val grandTotalRow = rowStartingWith("GRAND TOTAL")
            // The account rows are the ones between the header and the total.
            val headerIndex = rows.indexOf(rowStartingWith("NO."))
            val accountRows = rows.subList(headerIndex + 1, rows.indexOf(grandTotalRow))

            Then("the MRC subtotal, arrears subtotal, and combined total reconcile with totalAmount") {
                grandTotalRow.getCell(5).numericCellValue shouldBe 2000.0 // MRC subtotal
                grandTotalRow.getCell(6).numericCellValue shouldBe 500.0  // arrears subtotal
                grandTotalRow.getCell(7).numericCellValue shouldBe 2500.0 // combined = TopSheet.totalAmount
            }

            // The regression: the column used to repeat the topsheet's batch number
            // ("CONV-202607-0001") on every row, which identifies the compilation rather
            // than the account being billed and is identical for all of them.
            Then("the INVOICE NUMBER column holds a per-account reference, not the batch number") {
                accountRows.map { it.getCell(7).stringCellValue } shouldBe
                    listOf("ACCT-1JUL2026", "ACCT-2JUL2026")
            }
        }
    }

    // The workbook is the terminal deliverable of the shipped lifecycle, so it must only
    // ever be produced from a topsheet that has a minted invoice number and its lines.
    listOf(
        TopSheetStatus.DRAFT to "no invoice number yet and still-editable amounts",
        TopSheetStatus.CANCELLED to "its lines deleted, so the table would be empty",
    ).forEach { (status, why) ->
        Given("a ${status.name.lowercase()} topsheet — $why") {
            every { topsheets.findById("ts-2") } returns TopSheet(
                id = "ts-2",
                invoiceNumber = if (status == TopSheetStatus.DRAFT) null else "CONV-202607-0002",
                billingPeriod = "2026-07", providerName = "Converge", accountCount = 2,
                totalAmount = "2500.00", status = status, compilerId = "u1",
                compilationDate = Instant.parse("2026-07-05T00:00:00Z"),
            )

            When("exporting it to Excel") {
                Then("it is refused with a Conflict naming the status, and no lines are read") {
                    val ex = shouldThrow<DomainError.Conflict> { useCase("ts-2") }
                    ex.message!! shouldContain status.name.lowercase()
                    // Scoped to this id: the mock is shared with the happy-path Given above,
                    // which legitimately reads ts-1's lines.
                    verify(exactly = 0) { topsheets.findLines("ts-2") }
                }
            }
        }
    }
})
