package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ExportTopSheetExcelUseCase
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Binary export endpoints. The topsheet Excel download deliberately bypasses the
 * JSON response envelope (respondBytes) — it streams an .xlsx attachment. The
 * `{id}.xlsx` segment captures the topsheet id up to the literal suffix.
 */
fun Route.exportRoutes(exportTopSheet: ExportTopSheetExcelUseCase) {
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
}
