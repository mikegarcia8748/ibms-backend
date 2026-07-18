package com.puregoldbe.ibms.domain.port

import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.OcrBatch
import com.puregoldbe.ibms.domain.model.OcrExtractedRow
import com.puregoldbe.ibms.domain.model.OcrTemplate
import com.puregoldbe.ibms.domain.model.OcrTemplateUpsertRequest
import kotlinx.datetime.LocalDate

interface OcrTemplateRepository {
    fun list(): List<OcrTemplate>
    fun findById(id: String): OcrTemplate?
    fun findByKey(configKey: String): OcrTemplate?
    fun create(input: OcrTemplateUpsertRequest): OcrTemplate
    fun update(id: String, input: OcrTemplateUpsertRequest): OcrTemplate?
}

/** A row the extractor produced, before it is persisted/reconciled. */
data class NewOcrRow(
    val accountNumber: String?,
    val amount: String?,
    val outstandingBalance: String?,
    val dueDate: LocalDate?,
    val ispName: String?,
    val storeName: String?,
    val invoiceNumber: String?,
    val billNumber: String?,
    val billingPeriod: String?,
)

interface OcrBatchRepository {
    fun createBatch(
        uploadedBy: String?,
        providerId: String?,
        billingMonth: String?,
        fileName: String?,
        sourceId: String?,
        method: String?,
        usedTemplate: String?,
        status: String,
    ): OcrBatch

    fun findBatch(id: String): OcrBatch?
    fun page(cursor: String?, limit: Int): CursorPage<OcrBatch>
    fun addRow(batchId: String, row: NewOcrRow)
    fun rows(batchId: String): List<OcrExtractedRow>
}

// --- Extraction seam -------------------------------------------------------
data class OcrExtractionInput(
    val providerId: String?,
    val billingMonth: String?,
    val templateKey: String?,
    val sampleText: String?,
)

data class OcrExtractionResult(
    val method: String,
    val usedTemplate: String?,
    val rows: List<NewOcrRow>,
)

/**
 * The OCR extraction seam. The local [SimulatedOcrExtractor] returns deterministic
 * rows so the whole batch flow works without an external call; a real Gemini adapter
 * can replace it behind this interface.
 */
interface OcrGateway {
    fun extract(input: OcrExtractionInput): OcrExtractionResult
}
