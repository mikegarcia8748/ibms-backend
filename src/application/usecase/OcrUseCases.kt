package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.OcrBatch
import com.puregoldbe.ibms.domain.model.OcrExtractedRow
import com.puregoldbe.ibms.domain.model.OcrTemplate
import com.puregoldbe.ibms.domain.model.OcrTemplateUpsertRequest
import com.puregoldbe.ibms.domain.model.TriggerOcrRequest
import com.puregoldbe.ibms.domain.port.OcrBatchRepository
import com.puregoldbe.ibms.domain.port.OcrExtractionInput
import com.puregoldbe.ibms.domain.port.OcrGateway
import com.puregoldbe.ibms.domain.port.OcrTemplateRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner

/**
 * Runs (stubbed) extraction on a source, then persists a batch + its rows atomically.
 * The gateway call runs OUTSIDE the transaction so a future networked adapter won't
 * hold a DB transaction open while it works.
 */
class TriggerOcrExtractionUseCase(
    private val batches: OcrBatchRepository,
    private val gateway: OcrGateway,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(req: TriggerOcrRequest, actorId: String?): OcrBatch {
        val result = gateway.extract(
            OcrExtractionInput(req.providerId, req.billingMonth, req.templateKey, req.sampleText),
        )
        return tx.inTransaction {
            val batch = batches.createBatch(
                uploadedBy = actorId,
                providerId = req.providerId,
                billingMonth = req.billingMonth,
                fileName = req.fileName,
                sourceId = req.sourceId,
                method = result.method,
                usedTemplate = result.usedTemplate,
                status = "extracted",
            )
            result.rows.forEach { batches.addRow(batch.id, it) }
            batch
        }
    }
}

class ListOcrBatchesUseCase(
    private val batches: OcrBatchRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(cursor: String?, limit: Int): CursorPage<OcrBatch> =
        tx.inTransaction { batches.page(cursor, limit) }
}

class GetOcrBatchRowsUseCase(
    private val batches: OcrBatchRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(batchId: String): List<OcrExtractedRow> = tx.inTransaction {
        batches.findBatch(batchId) ?: throw DomainError.NotFound("ocr batch $batchId not found")
        batches.rows(batchId)
    }
}

class ListOcrTemplatesUseCase(
    private val templates: OcrTemplateRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(): List<OcrTemplate> = tx.inTransaction { templates.list() }
}

class CreateOcrTemplateUseCase(
    private val templates: OcrTemplateRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(input: OcrTemplateUpsertRequest): OcrTemplate = tx.inTransaction {
        if (input.configKey.isBlank()) throw DomainError.Validation("configKey is required")
        if (input.formatName.isBlank()) throw DomainError.Validation("formatName is required")
        if (templates.findByKey(input.configKey) != null) {
            throw DomainError.Conflict("ocr template ${input.configKey} already exists", "duplicate_config_key")
        }
        templates.create(input)
    }
}

class UpdateOcrTemplateUseCase(
    private val templates: OcrTemplateRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String, input: OcrTemplateUpsertRequest): OcrTemplate = tx.inTransaction {
        if (input.configKey.isBlank()) throw DomainError.Validation("configKey is required")
        if (input.formatName.isBlank()) throw DomainError.Validation("formatName is required")
        templates.update(id, input) ?: throw DomainError.NotFound("ocr template $id not found")
    }
}
