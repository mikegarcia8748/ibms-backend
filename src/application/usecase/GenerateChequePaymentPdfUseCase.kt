package com.puregoldbe.ibms.application.usecase

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.FontFactory
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

/**
 * Cheque Payment Voucher (PDF, OpenPDF). Generated once a topsheet has been paid:
 * it mirrors the TopSheet Excel layout (title, meta block, fixed signatories, the
 * line table, and a GRAND TOTAL) and surfaces the cheque number used to pay the
 * accounts. Guards that the cheque number is present so the document is only
 * produced for a genuinely-closed topsheet.
 */
class GenerateChequePaymentPdfUseCase(
    private val topsheets: TopSheetRepository,
    private val tx: TransactionRunner,
) {
    data class Export(val fileName: String, val bytes: ByteArray)

    suspend operator fun invoke(topsheetId: String): Export {
        val (ts, lines) = tx.inTransaction {
            val t = topsheets.findById(topsheetId) ?: throw DomainError.NotFound("topsheet $topsheetId not found")
            t to topsheets.findLines(topsheetId)
        }
        val cheque = ts.chequeNumber?.takeIf { it.isNotBlank() }
            ?: throw DomainError.Conflict("topsheet $topsheetId has no cheque number yet; pay it first", "cheque_missing")
        return Export(
            fileName = "Cheque_${ts.invoiceNumber}_${ts.billingPeriod}.pdf",
            bytes = build(ts, lines, cheque),
        )
    }

    private fun build(ts: TopSheet, lines: List<TopSheetDetail>, cheque: String): ByteArray {
        val title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14f)
        val subtitle = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12f)
        val label = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10f)
        val normal = FontFactory.getFont(FontFactory.HELVETICA, 10f)

        val out = ByteArrayOutputStream()
        val doc = Document(PageSize.A4, 36f, 36f, 36f, 36f)
        PdfWriter.getInstance(doc, out)
        doc.open()

        doc.add(Paragraph("PUREGOLD PRICE CLUB, INC.", title))
        doc.add(Paragraph("Cheque Payment Voucher", subtitle).apply { spacingAfter = 10f })

        fun meta(l: String, v: String?) = doc.add(
            Paragraph().apply {
                add(Phrase("$l ", label))
                add(Phrase(v ?: "", normal))
            },
        )
        meta("Provider:", ts.providerName ?: "N/A")
        meta("Invoice:", ts.invoiceNumber)
        meta("Billing Period:", ts.billingPeriod)
        meta("Total Accounts:", ts.accountCount.toString())
        meta("Cheque Number:", cheque)
        meta("Payment Date:", ts.paidAt?.toString())

        doc.add(Paragraph(" "))
        meta("Noted by:", "Gilbert Arciaga")
        meta("Approved by:", "Mr. Vincent Co")
        meta("By:", "Mary Ann Agustin")
        doc.add(Paragraph(" "))

        val headers = listOf("NO.", "STORE CO", "STORE NAME", "CID#", "ACCT#", "MRC", "INVOICE NUMBER")
        val table = PdfPTable(headers.size).apply {
            widthPercentage = 100f
            setWidths(floatArrayOf(4f, 10f, 22f, 10f, 12f, 12f, 18f))
        }
        headers.forEach { h ->
            table.addCell(PdfPCell(Phrase(h, label)).apply { horizontalAlignment = Element.ALIGN_CENTER })
        }

        var total = BigDecimal.ZERO
        lines.forEachIndexed { i, line ->
            total += BigDecimal(line.proratedAmount)
            table.addCell(PdfPCell(Phrase((i + 1).toString(), normal)))
            table.addCell(PdfPCell(Phrase(line.branchCode ?: "", normal)))
            table.addCell(PdfPCell(Phrase(line.storeName ?: "", normal)))
            table.addCell(PdfPCell(Phrase(line.circuitId ?: "", normal)))
            table.addCell(PdfPCell(Phrase(line.accountNumber ?: "", normal)))
            table.addCell(PdfPCell(Phrase(line.proratedAmount, normal)).apply { horizontalAlignment = Element.ALIGN_RIGHT })
            table.addCell(PdfPCell(Phrase(ts.invoiceNumber ?: "", normal)))
        }

        table.addCell(
            PdfPCell(Phrase("GRAND TOTAL", label)).apply {
                colspan = 5
                horizontalAlignment = Element.ALIGN_RIGHT
            },
        )
        table.addCell(PdfPCell(Phrase(total.toPlainString(), label)).apply { horizontalAlignment = Element.ALIGN_RIGHT })
        table.addCell(PdfPCell(Phrase("", normal)))
        doc.add(table)

        doc.close()
        return out.toByteArray()
    }
}
