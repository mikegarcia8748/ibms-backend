package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ApproveTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.AssignRfpNumbersUseCase
import com.puregoldbe.ibms.application.usecase.CompileTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.ConfirmTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.CreateDraftTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.GetTopSheetDetailsUseCase
import com.puregoldbe.ibms.application.usecase.GetTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.ListTopSheetsUseCase
import com.puregoldbe.ibms.application.usecase.PayTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.PreviewCompilationUseCase
import com.puregoldbe.ibms.application.usecase.RemoveDraftLineUseCase
import com.puregoldbe.ibms.application.usecase.UpdateDraftLineUseCase
import com.puregoldbe.ibms.domain.model.CompileRequest
import com.puregoldbe.ibms.domain.model.ConfirmRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.topSheetRoutes(
    preview: PreviewCompilationUseCase,
    compile: CompileTopSheetUseCase,
    createDraft: CreateDraftTopSheetUseCase,
    updateLine: UpdateDraftLineUseCase,
    assignRfp: AssignRfpNumbersUseCase,
    removeLine: RemoveDraftLineUseCase,
    confirmDraft: ConfirmTopSheetUseCase,
    list: ListTopSheetsUseCase,
    get: GetTopSheetUseCase,
    details: GetTopSheetDetailsUseCase,
    approve: ApproveTopSheetUseCase,
    pay: PayTopSheetUseCase,
) {
    route("/topsheets") {
        get {
            call.authorize()
            val p = call.pageParams()
            call.ok(
                list(
                    providerId = call.request.queryParameters["providerId"],
                    billingPeriod = call.request.queryParameters["billingPeriod"],
                    status = parseTopSheetStatus(call.request.queryParameters["status"]),
                    cursor = p.cursor,
                    limit = p.limit,
                ),
            )
        }
        post("/preview") {
            call.authorize(UserRole.SECRETARY)
            val req = call.receive<CompileRequest>()
            call.ok(preview(req.providerId, req.billingPeriod))
        }
        post("/compile") {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = call.receive<CompileRequest>()
            val idem = call.idempotencyContext(caller.userId, Json.encodeToString(req))
            call.created(compile(req.providerId, req.billingPeriod, caller.userId, idem))
        }
        post("/draft") {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = call.receive<CompileRequest>()
            val idem = call.idempotencyContext(caller.userId, Json.encodeToString(req))
            call.created(createDraft(req.providerId, req.billingPeriod, caller.userId, idem))
        }
        get("/{id}/lines") {
            call.authorize()
            call.ok(details(call.pathId()))
        }
        patch("/{id}/lines/{lineId}") {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = call.receive<UpdateLineRequest>()
            val lineId = call.parameters["lineId"]!!
            call.ok(updateLine(call.pathId(), lineId, req.rfpNumber, req.proratedAmount))
        }
        post("/{id}/assign-rfp") {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = call.receive<AssignRfpNumbersRequest>()
            call.ok(assignRfp(call.pathId(), req.startRfpNumber, req.endRfpNumber, caller.userId))
        }
        delete("/{id}/lines/{lineId}") {
            val caller = call.authorize(UserRole.SECRETARY)
            val lineId = call.parameters["lineId"]!!
            removeLine(call.pathId(), lineId, caller.userId)
            call.noContent()
        }
        post("/{id}/confirm") {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = runCatching { call.receiveNullable<ConfirmRequest>() }.getOrNull() ?: ConfirmRequest()
            val idem = call.idempotencyContext(caller.userId, "topsheet.confirm:${call.pathId()}")
            call.ok(confirmDraft(call.pathId(), caller.userId, req.acknowledgeArrears, idem))
        }
        get("/{id}") {
            call.authorize()
            call.ok(get(call.pathId()))
        }
        get("/{id}/details") {
            call.authorize()
            call.ok(details(call.pathId()))
        }
        post("/{id}/approve") {
            val caller = call.authorize(UserRole.FINANCE)
            call.ok(approve(call.pathId(), caller.userId))
        }
        post("/{id}/pay") {
            val caller = call.authorize(UserRole.FINANCE)
            val id = call.pathId()
            call.ok(pay(id, call.idempotencyContext(caller.userId, "topsheet.pay:$id")))
        }
    }
}
