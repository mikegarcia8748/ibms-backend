package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.CancelTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.ConfirmTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.CreateDraftTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.GenerateRfpUseCase
import com.puregoldbe.ibms.application.usecase.GetTopSheetDetailsUseCase
import com.puregoldbe.ibms.application.usecase.GetTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.ListTopSheetsUseCase
import com.puregoldbe.ibms.application.usecase.PayTopSheetUseCase
import com.puregoldbe.ibms.application.usecase.PreviewCompilationUseCase
import com.puregoldbe.ibms.application.usecase.ReleaseToFinanceUseCase
import com.puregoldbe.ibms.application.usecase.RemoveDraftLineUseCase
import com.puregoldbe.ibms.application.usecase.UpdateDraftLineUseCase
import com.puregoldbe.ibms.domain.model.CompileRequest
import com.puregoldbe.ibms.domain.model.ConfirmRequest
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.infrastructure.config.TopSheetFeatures
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [features] gates the post-compile chain (generate-rfp -> release-to-finance -> pay).
 * Those three use cases stay wired and unmodified either way; only their doors close.
 */
fun Route.topSheetRoutes(
    preview: PreviewCompilationUseCase,
    createDraft: CreateDraftTopSheetUseCase,
    updateLine: UpdateDraftLineUseCase,
    generateRfp: GenerateRfpUseCase,
    releaseToFinance: ReleaseToFinanceUseCase,
    removeLine: RemoveDraftLineUseCase,
    confirmDraft: ConfirmTopSheetUseCase,
    cancel: CancelTopSheetUseCase,
    list: ListTopSheetsUseCase,
    get: GetTopSheetUseCase,
    details: GetTopSheetDetailsUseCase,
    pay: PayTopSheetUseCase,
    features: TopSheetFeatures,
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
            call.authorize(UserRole.SECRETARY)
            val req = call.receive<UpdateLineRequest>()
            val lineId = call.parameters["lineId"]!!
            call.ok(updateLine(call.pathId(), lineId, req.proratedAmount))
        }
        post("/{id}/generate-rfp") {
            requireEnabled(features.rfpFlowEnabled, "generate-rfp")
            val caller = call.authorize(UserRole.SECRETARY)
            val id = call.pathId()
            val idem = call.idempotencyContext(caller.userId, "topsheet.generate_rfp:$id")
            call.ok(generateRfp(id, caller.userId, idem))
        }
        post("/{id}/release-to-finance") {
            requireEnabled(features.rfpFlowEnabled, "release-to-finance")
            val caller = call.authorize(UserRole.SECRETARY)
            call.ok(releaseToFinance(call.pathId(), caller.userId))
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
        post("/{id}/cancel") {
            val caller = call.authorize(UserRole.SECRETARY)
            // Received defensively like /pay: a missing or malformed body funnels into the
            // use case's own Validation -> clean 400, not a raw deserialization error.
            val reason = runCatching { call.receiveNullable<CancelTopSheetRequest>() }.getOrNull()?.reason ?: ""
            call.ok(cancel(call.pathId(), caller.userId, reason))
        }
        get("/{id}") {
            call.authorize()
            call.ok(get(call.pathId()))
        }
        get("/{id}/details") {
            call.authorize()
            call.ok(details(call.pathId()))
        }
        post("/{id}/pay") {
            requireEnabled(features.rfpFlowEnabled, "topsheet payment")
            val caller = call.authorize(UserRole.FINANCE)
            val id = call.pathId()
            // Receive defensively: a missing/blank body funnels into the use case's
            // Validation check -> clean 400 rather than a raw deserialization error.
            val cheque = runCatching { call.receiveNullable<PayTopSheetRequest>() }.getOrNull()?.chequeNumber ?: ""
            call.ok(pay(id, cheque, call.idempotencyContext(caller.userId, "topsheet.pay:$id:$cheque")))
        }
    }
}
