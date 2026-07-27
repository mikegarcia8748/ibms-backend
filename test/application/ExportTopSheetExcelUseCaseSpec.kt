package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ExportTopSheetExcelUseCase
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
            val grandTotalRow: Row = XSSFWorkbook(ByteArrayInputStream(export.bytes)).use { wb ->
                val sheet = wb.getSheetAt(0)
                (0..sheet.lastRowNum).mapNotNull { sheet.getRow(it) }
                    .first { row ->
                        val c0 = row.getCell(0)
                        c0 != null && c0.cellType == CellType.STRING && c0.stringCellValue == "GRAND TOTAL"
                    }
            }

            Then("the MRC subtotal, arrears subtotal, and combined total reconcile with totalAmount") {
                grandTotalRow.getCell(5).numericCellValue shouldBe 2000.0 // MRC subtotal
                grandTotalRow.getCell(6).numericCellValue shouldBe 500.0  // arrears subtotal
                grandTotalRow.getCell(7).numericCellValue shouldBe 2500.0 // combined = TopSheet.totalAmount
            }
        }
    }
})
