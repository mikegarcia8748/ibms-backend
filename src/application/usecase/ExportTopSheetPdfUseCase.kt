package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.lowagie.text.*
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Server-side TopSheet PDF export using OpenPDF. Reproduces the production
 * layout: title, meta block, the fixed signatories (Noted by: Gilbert Arciaga /
 * Approved by: Mr. Vincent Co), the line table, and a GRAND TOTAL.
 */
class ExportTopSheetPdfUseCase(
    private val topsheets: TopSheetRepository,
    private val tx: TransactionRunner,
) {
    data class Export(val fileName: String, val bytes: ByteArray)

    suspend operator fun invoke(topsheetId: String): Export {
        val (topsheet, lines) = tx.inTransaction {
            val ts = topsheets.findById(topsheetId) ?: throw DomainError.NotFound("topsheet $topsheetId not found")
            ts to topsheets.findLines(topsheetId)
        }
        return Export(
            fileName = "TopSheet_${topsheet.invoiceNumber}_${topsheet.billingPeriod}.pdf",
            bytes = build(topsheet, lines),
        )
    }

    private fun build(ts: TopSheet, lines: List<TopSheetDetail>): ByteArray {
        val out = ByteArrayOutputStream()
        val document = Document(PageSize.A4.rotate(), 36f, 36f, 36f, 36f)
        PdfWriter.getInstance(document, out)
        document.open()

        val titleFont = Font(Font.HELVETICA, 14f, Font.BOLD)
        val subtitleFont = Font(Font.HELVETICA, 12f, Font.BOLD)
        val boldFont = Font(Font.HELVETICA, 9f, Font.BOLD)
        val normalFont = Font(Font.HELVETICA, 9f, Font.NORMAL)
        val headerFont = Font(Font.HELVETICA, 8f, Font.BOLD)
        val cellFont = Font(Font.HELVETICA, 8f, Font.NORMAL)

        // --- Title ---
        val title = Paragraph("PUREGOLD PRICE CLUB, INC.", titleFont)
        title.alignment = Element.ALIGN_CENTER
        document.add(title)

        val subtitle = Paragraph("TopSheet Report", subtitleFont)
        subtitle.alignment = Element.ALIGN_CENTER
        document.add(subtitle)
        document.add(Paragraph(" ")) // spacer

        // --- Metadata block (using a 7-column table to align with signatories) ---
        val metaTable = PdfPTable(7)
        metaTable.widthPercentage = 100f
        metaTable.setWidths(floatArrayOf(12f, 20f, 10f, 10f, 8f, 5f, 20f))

        fun metaRow(label: String, value: String, sigLabel: String = "", sigValue: String = "") {
            val labelCell = PdfPCell(Phrase(label, boldFont)).apply { border = Rectangle.NO_BORDER }
            val valueCell = PdfPCell(Phrase(value, normalFont)).apply { border = Rectangle.NO_BORDER; colspan = 2 }
            val spacer = PdfPCell(Phrase("", normalFont)).apply { border = Rectangle.NO_BORDER }
            val sigLabelCell = PdfPCell(Phrase(sigLabel, boldFont)).apply { border = Rectangle.NO_BORDER }
            val sigSpacerCell = PdfPCell(Phrase("", normalFont)).apply { border = Rectangle.NO_BORDER }
            val sigValueCell = PdfPCell(Phrase(sigValue, normalFont)).apply { border = Rectangle.NO_BORDER }
            metaTable.addCell(labelCell)
            metaTable.addCell(valueCell)
            metaTable.addCell(spacer)
            metaTable.addCell(sigLabelCell)
            metaTable.addCell(sigSpacerCell)
            metaTable.addCell(sigValueCell)
        }

        metaRow("Provider:", ts.providerName ?: "N/A")
        metaRow("Invoice:", ts.invoiceNumber ?: "")
        metaRow("Billing Period:", ts.billingPeriod, "By", "Mary Ann Agustin")
        metaRow("Total Accounts:", ts.accountCount.toString())
        metaRow("", "", "Noted by", "Gilbert Arciaga")
        metaRow("", "", "Approved by", "Mr. Vincent Co")

        document.add(metaTable)
        document.add(Paragraph(" ")) // spacer

        // --- Data table ---
        val table = PdfPTable(7)
        table.widthPercentage = 100f
        table.setWidths(floatArrayOf(4f, 6f, 25f, 12f, 10f, 8f, 15f))
        table.headerRows = 1

        val headers = listOf("NO.", "STORE CO", "STORE NAME", "CID#", "ACCT#", "MRC", "INVOICE NUMBER")
        val headerBg = Color(220, 220, 220)
        headers.forEach { h ->
            val cell = PdfPCell(Phrase(h, headerFont))
            cell.backgroundColor = headerBg
            cell.horizontalAlignment = Element.ALIGN_CENTER
            cell.paddingBottom = 4f
            cell.paddingTop = 4f
            table.addCell(cell)
        }

        // Data rows
        lines.forEachIndexed { i, line ->
            val invoiceNum = invoiceNumberForLine(line, ts.billingPeriod)
            val rowData = listOf(
                (i + 1).toString(),
                line.branchCode ?: "",
                line.storeName ?: "",
                line.circuitId ?: "",
                line.accountNumber ?: "",
                line.proratedAmount,
                invoiceNum,
            )
            rowData.forEachIndexed { col, value ->
                val cell = PdfPCell(Phrase(value.toString(), cellFont))
                cell.paddingBottom = 3f
                cell.paddingTop = 3f
                if (col == 5) cell.horizontalAlignment = Element.ALIGN_RIGHT // MRC right-aligned
                table.addCell(cell)
            }
        }

        // GRAND TOTAL row
        val total = lines.fold(BigDecimal.ZERO) { acc, l -> acc + BigDecimal(l.proratedAmount) }
        val grandTotalLabelCell = PdfPCell(Phrase("GRAND TOTAL", headerFont))
        grandTotalLabelCell.colspan = 5
        grandTotalLabelCell.paddingBottom = 4f
        grandTotalLabelCell.paddingTop = 4f
        table.addCell(grandTotalLabelCell)

        val grandTotalValueCell = PdfPCell(Phrase(total.toPlainString(), headerFont))
        grandTotalValueCell.horizontalAlignment = Element.ALIGN_RIGHT
        grandTotalValueCell.paddingBottom = 4f
        grandTotalValueCell.paddingTop = 4f
        table.addCell(grandTotalValueCell)

        // Empty cell for INVOICE NUMBER column in grand total row
        val emptyCell = PdfPCell(Phrase("", cellFont))
        emptyCell.paddingBottom = 4f
        emptyCell.paddingTop = 4f
        table.addCell(emptyCell)

        document.add(table)
        document.close()
        return out.toByteArray()
    }

    private fun invoiceNumberForLine(line: TopSheetDetail, billingPeriod: String): String {
        val acct = line.accountNumber ?: return ""
        val ym = YearMonth.parse(billingPeriod)
        val monthAbbrev = ym.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()
        val year = ym.year.toString()
        return "$acct$monthAbbrev$year"
    }
}
