package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.service.InvoiceNumberFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

/**
 * Server-side TopSheet Excel (Apache POI), replacing the client-side ExcelJS
 * generateCSV. Reproduces the production layout: title, meta block, the fixed
 * signatories (Noted by: Gilbert Arciaga / Approved by: Mr. Vincent Co), the line
 * table, and a GRAND TOTAL.
 */
class ExportTopSheetExcelUseCase(
    private val topsheets: TopSheetRepository,
    private val tx: TransactionRunner,
) {
    data class Export(val fileName: String, val bytes: ByteArray)

    suspend operator fun invoke(topsheetId: String): Export {
        val (topsheet, lines) = tx.inTransaction {
            val ts = topsheets.findById(topsheetId) ?: throw DomainError.NotFound("topsheet $topsheetId not found")
            // An allow-list, so a status added later is refused until someone decides it
            // belongs here. DRAFT has no invoice number yet (minted at confirm) and its
            // amounts are still editable, so the workbook would be filed as
            // "TopSheet_null_2026-07.xlsx" with a blank INVOICE NUMBER column. CANCELLED
            // has had every line deleted by the cancel, so it would export a header over
            // an empty table whose 0.00 GRAND TOTAL contradicts the retained totalAmount.
            if (ts.status !in EXPORTABLE) {
                throw DomainError.Conflict(
                    "topsheet $topsheetId cannot be exported in ${ts.status.name.lowercase()} status; " +
                        "confirm it first",
                    "not_exportable",
                )
            }
            ts to topsheets.findLines(topsheetId)
        }
        return Export(
            fileName = "TopSheet_${topsheet.invoiceNumber}_${topsheet.billingPeriod}.xlsx",
            bytes = build(topsheet, lines),
        )
    }

    private fun build(ts: TopSheet, lines: List<TopSheetDetail>): ByteArray = XSSFWorkbook().use { wb ->
        val sheet = wb.createSheet("TopSheet Report")
        val bold = wb.createCellStyle().apply { setFont(wb.createFont().apply { bold = true }) }
        var r = 0

        sheet.createRow(r++).createCell(0).setCellValue("PUREGOLD PRICE CLUB, INC.")
        r++
        sheet.createRow(r++).createCell(0).setCellValue("TopSheet Report")
        r++

        fun meta(label: String, value: String?) {
            val row = sheet.createRow(r++)
            row.createCell(0).apply { setCellValue(label); cellStyle = bold }
            row.createCell(1).setCellValue(value ?: "")
        }
        meta("Provider:", ts.providerName ?: "N/A")
        meta("Invoice:", ts.invoiceNumber)
        sheet.createRow(r++).apply {
            createCell(0).apply { setCellValue("Billing Period:"); cellStyle = bold }
            createCell(1).setCellValue(ts.billingPeriod)
            createCell(4).setCellValue("By")
            createCell(6).setCellValue("Mary Ann Agustin")
        }
        meta("Total Accounts:", ts.accountCount.toString())

        sheet.createRow(r++).apply {
            createCell(4).apply { setCellValue("Noted by"); cellStyle = bold }
            createCell(6).setCellValue("Gilbert Arciaga")
        }
        r++
        sheet.createRow(r++).apply {
            createCell(4).apply { setCellValue("Approved by"); cellStyle = bold }
            createCell(6).setCellValue("Mr. Vincent Co")
        }
        r++

        val headers = listOf("NO.", "STORE CO", "STORE NAME", "CID#", "ACCT#", "MRC", "ARREARS", "INVOICE NUMBER")
        sheet.createRow(r++).apply {
            headers.forEachIndexed { i, h -> createCell(i).apply { setCellValue(h); cellStyle = bold } }
        }

        lines.forEachIndexed { i, line ->
            sheet.createRow(r++).apply {
                createCell(0).setCellValue((i + 1).toDouble())
                createCell(1).setCellValue(line.branchCode ?: "")
                createCell(2).setCellValue(line.storeName ?: "")
                createCell(3).setCellValue(line.circuitId ?: "")
                createCell(4).setCellValue(line.accountNumber ?: "")
                createCell(5).setCellValue(line.proratedAmount.toDouble()) // display only; totals are BigDecimal
                createCell(6).setCellValue(line.arrearsAmount.toDouble()) // display only; totals are BigDecimal
                // Per-account reference (ACCT# + rental period), NOT the topsheet's batch
                // number — that one belongs in the meta block above and is the same on
                // every row, which is exactly what made this column useless.
                createCell(7).setCellValue(InvoiceNumberFormatter.forAccount(line.accountNumber, line.billingPeriod))
            }
        }

        // The signed document's bottom line must reconcile with the stored invoice total
        // (TopSheet.totalAmount = Σ proratedAmount + Σ arrearsAmount). Break out the MRC and
        // arrears subtotals for transparency, then the combined GRAND TOTAL.
        val mrcTotal = lines.fold(BigDecimal.ZERO) { acc, l -> acc + BigDecimal(l.proratedAmount) }
        val arrearsTotal = lines.fold(BigDecimal.ZERO) { acc, l -> acc + BigDecimal(l.arrearsAmount) }
        val grandTotal = mrcTotal + arrearsTotal
        sheet.createRow(r++).apply {
            createCell(0).apply { setCellValue("GRAND TOTAL"); cellStyle = bold }
            createCell(5).apply { setCellValue(mrcTotal.toDouble()); cellStyle = bold }
            createCell(6).apply { setCellValue(arrearsTotal.toDouble()); cellStyle = bold }
            createCell(7).apply { setCellValue(grandTotal.toDouble()); cellStyle = bold }
        }

        ByteArrayOutputStream().use { out ->
            wb.write(out)
            out.toByteArray()
        }
    }

    private companion object {
        /** Statuses that carry a minted invoice number and intact line items. */
        val EXPORTABLE = setOf(
            TopSheetStatus.COMPILED,
            TopSheetStatus.RFP_ASSIGNED,
            TopSheetStatus.APPROVED,
            TopSheetStatus.PAID,
        )
    }
}
