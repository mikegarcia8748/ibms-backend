package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.CreateOcrTemplateUseCase
import com.puregoldbe.ibms.application.usecase.GetOcrBatchRowsUseCase
import com.puregoldbe.ibms.application.usecase.ListOcrBatchesUseCase
import com.puregoldbe.ibms.application.usecase.ListOcrTemplatesUseCase
import com.puregoldbe.ibms.application.usecase.TriggerOcrExtractionUseCase
import com.puregoldbe.ibms.application.usecase.UpdateOcrTemplateUseCase
import com.puregoldbe.ibms.domain.model.OcrTemplateUpsertRequest
import com.puregoldbe.ibms.domain.model.TriggerOcrRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.request.*
import io.ktor.server.routing.*

/**
 * OCR ingestion. Extraction/batches are Secretary-facing; templates are read by
 * sysadmin+secretary and edited by sysadmin. Extraction is stubbed (SimulatedOcrExtractor).
 */
fun Route.ocrRoutes(
    trigger: TriggerOcrExtractionUseCase,
    listBatches: ListOcrBatchesUseCase,
    getBatchRows: GetOcrBatchRowsUseCase,
    listTemplates: ListOcrTemplatesUseCase,
    createTemplate: CreateOcrTemplateUseCase,
    updateTemplate: UpdateOcrTemplateUseCase,
) {
    route("/ocr") {
        post("/extract") {
            val caller = call.authorize(UserRole.SECRETARY)
            val req = call.receive<TriggerOcrRequest>()
            call.created(trigger(req, caller.userId))
        }
        get("/batches") {
            call.authorize(UserRole.SECRETARY)
            val p = call.pageParams()
            call.ok(listBatches(p.cursor, p.limit))
        }
        get("/batches/{id}/rows") {
            call.authorize(UserRole.SECRETARY)
            call.ok(getBatchRows(call.pathId()))
        }
        get("/templates") {
            call.authorize(UserRole.SYSADMIN, UserRole.SECRETARY)
            call.ok(listTemplates())
        }
        post("/templates") {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<OcrTemplateUpsertRequest>()
            call.created(createTemplate(req))
        }
        put("/templates/{id}") {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<OcrTemplateUpsertRequest>()
            call.ok(updateTemplate(call.pathId(), req))
        }
    }
}
