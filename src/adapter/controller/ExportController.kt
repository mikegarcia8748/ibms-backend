package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ExportAccountsExcelUseCase
import com.puregoldbe.ibms.application.usecase.ExportChequePaymentCsvUseCase
import com.puregoldbe.ibms.application.usecase.ExportTopSheetExcelUseCase
import com.puregoldbe.ibms.application.usecase.GenerateChequePaymentPdfUseCase
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Binary export endpoints. The topsheet Excel and account Excel downloads
 * deliberately bypass the JSON response envelope (respondBytes) — they stream
 * binary attachments. The `{id}.xlsx` segment captures the topsheet id up to
 * the literal suffix.
 */
fun Route.exportRoutes(
    exportTopSheet: ExportTopSheetExcelUseCase,
    exportAccounts: ExportAccountsExcelUseCase,
    exportChequePdf: GenerateChequePaymentPdfUseCase,
    exportChequeCsv: ExportChequePaymentCsvUseCase,
) {
    get("/exports/topsheet/{id}.xlsx") {
        call.authorize(UserRole.SECRETARY, UserRole.FINANCE)
        val file = exportTopSheet(call.pathId())
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName).toString(),
        )
        call.respondBytes(
            file.bytes,
            ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        )
    }

    get("/exports/accounts.xlsx") {
        call.authorize()
        val file = exportAccounts(
            providerId = call.request.queryParameters["providerId"],
            status = parseAccountStatus(call.request.queryParameters["status"]),
        )
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName).toString(),
        )
        call.respondBytes(
            file.bytes,
            ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        )
    }

    // Cheque Payment Voucher for a paid topsheet. `{id}` is its own path segment
    // before the literal `/cheque.pdf|.csv` suffix, so call.pathId() reads it directly.
    get("/exports/topsheet/{id}/cheque.pdf") {
        call.authorize(UserRole.SECRETARY, UserRole.FINANCE)
        val file = exportChequePdf(call.pathId())
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName).toString(),
        )
        call.respondBytes(file.bytes, ContentType.Application.Pdf)
    }

    get("/exports/topsheet/{id}/cheque.csv") {
        call.authorize(UserRole.SECRETARY, UserRole.FINANCE)
        val file = exportChequeCsv(call.pathId())
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName).toString(),
        )
        call.respondBytes(file.bytes, ContentType.parse("text/csv; charset=UTF-8"))
    }
}
