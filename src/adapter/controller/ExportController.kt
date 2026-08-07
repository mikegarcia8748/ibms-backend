package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ExportAccountsExcelUseCase
import com.puregoldbe.ibms.application.usecase.ExportChequePaymentCsvUseCase
import com.puregoldbe.ibms.application.usecase.ExportTopSheetExcelUseCase
import com.puregoldbe.ibms.application.usecase.GenerateChequePaymentPdfUseCase
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.infrastructure.config.TopSheetFeatures
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Binary export endpoints. The topsheet Excel and account Excel downloads
 * deliberately bypass the JSON response envelope (respondBytes) — they stream
 * binary attachments. The `{id}.xlsx` segment captures the topsheet id up to
 * the literal suffix.
 *
 * [features] gates the two cheque vouchers along with the payment flow that produces
 * the cheque number they read. The topsheet and account workbooks are never gated.
 */
fun Route.exportRoutes(
    exportTopSheet: ExportTopSheetExcelUseCase,
    exportAccounts: ExportAccountsExcelUseCase,
    exportChequePdf: GenerateChequePaymentPdfUseCase,
    exportChequeCsv: ExportChequePaymentCsvUseCase,
    features: TopSheetFeatures,
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
    //
    // Gated with the payment flow rather than left to fall through: cheque_number is
    // written only by topsheets.pay, so with payment off these would answer
    // "no cheque number yet; pay it first" — an instruction the client cannot follow.
    get("/exports/topsheet/{id}/cheque.pdf") {
        requireEnabled(features.rfpFlowEnabled, "the cheque payment voucher (PDF)")
        call.authorize(UserRole.SECRETARY, UserRole.FINANCE)
        val file = exportChequePdf(call.pathId())
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName).toString(),
        )
        call.respondBytes(file.bytes, ContentType.Application.Pdf)
    }

    get("/exports/topsheet/{id}/cheque.csv") {
        requireEnabled(features.rfpFlowEnabled, "the cheque payment voucher (CSV)")
        call.authorize(UserRole.SECRETARY, UserRole.FINANCE)
        val file = exportChequeCsv(call.pathId())
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName).toString(),
        )
        call.respondBytes(file.bytes, ContentType.parse("text/csv; charset=UTF-8"))
    }
}
