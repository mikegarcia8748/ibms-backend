package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import java.math.BigDecimal

/**
 * Cheque Payment CSV. The flat-file counterpart to [GenerateChequePaymentPdfUseCase]:
 * a meta block (incl. the cheque number), a blank separator line, the account line
 * table, and a GRAND TOTAL. Hand-rolled with RFC-4180 escaping (no CSV library).
 * Guards that the cheque number is present, same as the PDF.
 */
class ExportChequePaymentCsvUseCase(
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
            fileName = "Cheque_${ts.invoiceNumber}_${ts.billingPeriod}.csv",
            bytes = build(ts, lines, cheque),
        )
    }

    /** RFC-4180: wrap in quotes and double inner quotes when the value needs it. */
    private fun q(field: String?): String {
        val v = field ?: ""
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
    }

    private fun build(ts: TopSheet, lines: List<TopSheetDetail>, cheque: String): ByteArray {
        val nl = "\r\n"
        val sb = StringBuilder()

        sb.append("Provider,").append(q(ts.providerName)).append(nl)
        sb.append("Invoice,").append(q(ts.invoiceNumber)).append(nl)
        sb.append("Billing Period,").append(q(ts.billingPeriod)).append(nl)
        sb.append("Total Accounts,").append(ts.accountCount).append(nl)
        sb.append("Cheque Number,").append(q(cheque)).append(nl)
        sb.append("Payment Date,").append(q(ts.paidAt?.toString())).append(nl)
        sb.append(nl) // blank separator line

        sb.append("NO.,STORE CO,STORE NAME,CID#,ACCT#,MRC,INVOICE NUMBER").append(nl)
        var total = BigDecimal.ZERO
        lines.forEachIndexed { i, l ->
            total += BigDecimal(l.proratedAmount)
            sb.append(i + 1).append(',')
                .append(q(l.branchCode)).append(',')
                .append(q(l.storeName)).append(',')
                .append(q(l.circuitId)).append(',')
                .append(q(l.accountNumber)).append(',')
                .append(q(l.proratedAmount)).append(',')
                .append(q(ts.invoiceNumber)).append(nl)
        }
        sb.append("GRAND TOTAL,,,,,").append(total.toPlainString()).append(',').append(nl)

        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
