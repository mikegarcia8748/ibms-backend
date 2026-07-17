package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ApproveTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.CompileTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.ExportTopSheetExcelUseCase
import com.puregoldbe.ibms.application.usecase.GetTopSheetDetailsUseCase
import com.puregoldbe.ibms.application.usecase.GetTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.ListTopSheetsUseCase
import com.puregoldbe.ibms.application.usecase.PayTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.PreviewCompilationUseCase
import com.puregoldbe.ibms.domain.model.CompileRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.topSheetRoutes(
    preview: PreviewCompilationUseCase,
    compile: CompileTopSheetUseCase,
    list: ListTopSheetsUseCase,
    get: GetTopSheetUseCase,
    details: GetTopSheetDetailsUseCase,
    approve: ApproveTopSheetUseCase,
    pay: PayTopSheetUseCase,
    export: ExportTopSheetExcelUseCase,
) {
    route("/topsheets") {
        get {
            call.authorize()
            call.respond(
                list(
                    providerId = call.request.queryParameters["providerId"],
                    billingPeriod = call.request.queryParameters["billingPeriod"],
                    status = parseTopSheetStatus(call.request.queryParameters["status"]),
                ),
            )
        }
        post("/preview") {
            call.authorize(UserRole.SYSADMIN, UserRole.SECRETARY)
            val req = call.receive<CompileRequest>()
            call.respond(preview(req.providerId, req.billingPeriod))
        }
        post("/compile") {
            val caller = call.authorize(UserRole.SYSADMIN, UserRole.SECRETARY)
            val req = call.receive<CompileRequest>()
            call.respond(HttpStatusCode.Created, compile(req.providerId, req.billingPeriod, caller.userId))
        }
        get("/{id}") {
            call.authorize()
            call.respond(get(call.pathId()))
        }
        get("/{id}/details") {
            call.authorize()
            call.respond(details(call.pathId()))
        }
        get("/{id}/export.xlsx") {
            call.authorize()
            val file = export(call.pathId())
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName).toString(),
            )
            call.respondBytes(
                file.bytes,
                ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            )
        }
        post("/{id}/approve") {
            val caller = call.authorize(UserRole.SYSADMIN, UserRole.FINANCE)
            call.respond(approve(call.pathId(), caller.userId))
        }
        post("/{id}/pay") {
            call.authorize(UserRole.SYSADMIN, UserRole.FINANCE)
            call.respond(pay(call.pathId()))
        }
    }
}
