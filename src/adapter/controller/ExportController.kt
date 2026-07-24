package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ExportAccountsExcelUseCase
import com.puregoldbe.ibms.application.usecase.ExportTopSheetExcelUseCase
import com.puregoldbe.ibms.application.usecase.ExportTopSheetPdfUseCase
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Binary export endpoints. The topsheet Excel and PDF downloads deliberately bypass the
 * JSON response envelope (respondBytes) — they stream binary attachments. The
 * `{id}.xlsx` / `{id}.pdf` segments capture the topsheet id up to the literal suffix.
 */
fun Route.exportRoutes(
    exportTopSheet: ExportTopSheetExcelUseCase,
    exportTopSheetPdf: ExportTopSheetPdfUseCase,
    exportAccounts: ExportAccountsExcelUseCase,
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

    get("/exports/topsheet/{id}.pdf") {
        call.authorize(UserRole.SECRETARY, UserRole.FINANCE)
        val file = exportTopSheetPdf(call.pathId())
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName).toString(),
        )
        call.respondBytes(file.bytes, ContentType.parse("application/pdf"))
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
}
