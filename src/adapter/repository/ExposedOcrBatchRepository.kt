package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.Attachments
import com.puregoldbe.ibms.adapter.db.OcrBatches
import com.puregoldbe.ibms.adapter.db.OcrExtractedRows
import com.puregoldbe.ibms.adapter.db.Providers
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.keysetAfter
import com.puregoldbe.ibms.adapter.db.keysetAnchor
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toCursorPage
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.OcrBatch
import com.puregoldbe.ibms.domain.model.OcrExtractedRow
import com.puregoldbe.ibms.domain.port.NewOcrRow
import com.puregoldbe.ibms.domain.port.OcrBatchRepository
import com.puregoldbe.ibms.domain.valueobject.toMoney
import com.puregoldbe.ibms.domain.valueobject.toMoneyString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*

class ExposedOcrBatchRepository : OcrBatchRepository {

    override fun createBatch(
        uploadedBy: String?,
        providerId: String?,
        billingMonth: String?,
        fileName: String?,
        sourceId: String?,
        method: String?,
        usedTemplate: String?,
        status: String,
    ): OcrBatch {
        val id = OcrBatches.insertAndGetId {
            if (uploadedBy != null) it[OcrBatches.uploadedBy] = EntityID(uploadedBy.toUuid(), Users)
            if (providerId != null) it[OcrBatches.providerId] = EntityID(providerId.toUuid(), Providers)
            it[OcrBatches.billingMonth] = billingMonth
            it[OcrBatches.fileName] = fileName
            if (sourceId != null) it[OcrBatches.sourceId] = EntityID(sourceId.toUuid(), Attachments)
            it[OcrBatches.method] = method
            it[OcrBatches.usedTemplate] = usedTemplate
            it[OcrBatches.status] = status
        }.value
        return findBatch(id.toString())!!
    }

    override fun findBatch(id: String): OcrBatch? {
        val uuid = id.toUuidOrNull() ?: return null
        return OcrBatches.selectAll().where { OcrBatches.id eq uuid }.map { it.toBatch() }.singleOrNull()
    }

    override fun page(cursor: String?, limit: Int): CursorPage<OcrBatch> {
        val anchor = OcrBatches.keysetAnchor(OcrBatches.createdAt, cursor)
        return OcrBatches.selectAll()
            .apply { if (anchor != null) andWhere { keysetAfter(OcrBatches, OcrBatches.createdAt, anchor) } }
            .orderBy(OcrBatches.createdAt to SortOrder.ASC, OcrBatches.id to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toBatch() }
            .toCursorPage(limit) { it.id }
    }

    override fun addRow(batchId: String, row: NewOcrRow) {
        OcrExtractedRows.insert {
            it[OcrExtractedRows.batchId] = EntityID(batchId.toUuid(), OcrBatches)
            it[OcrExtractedRows.accountNumber] = row.accountNumber
            it[OcrExtractedRows.amount] = row.amount?.toMoney()
            it[OcrExtractedRows.outstandingBalance] = row.outstandingBalance?.toMoney()
            it[OcrExtractedRows.dueDate] = row.dueDate?.jt()
            it[OcrExtractedRows.ispName] = row.ispName
            it[OcrExtractedRows.storeName] = row.storeName
            it[OcrExtractedRows.invoiceNumber] = row.invoiceNumber
            it[OcrExtractedRows.billNumber] = row.billNumber
            it[OcrExtractedRows.billingPeriod] = row.billingPeriod
            it[OcrExtractedRows.reconciled] = false
        }
    }

    override fun rows(batchId: String): List<OcrExtractedRow> {
        val uuid = batchId.toUuidOrNull() ?: return emptyList()
        return OcrExtractedRows.selectAll().where { OcrExtractedRows.batchId eq uuid }.map { it.toRow() }
    }

    private fun ResultRow.toBatch() = OcrBatch(
        id = this[OcrBatches.id].value.toString(),
        uploadedBy = this[OcrBatches.uploadedBy]?.value?.toString(),
        providerId = this[OcrBatches.providerId]?.value?.toString(),
        billingMonth = this[OcrBatches.billingMonth],
        fileName = this[OcrBatches.fileName],
        sourceId = this[OcrBatches.sourceId]?.value?.toString(),
        method = this[OcrBatches.method],
        usedTemplate = this[OcrBatches.usedTemplate],
        status = this[OcrBatches.status],
        createdAt = this[OcrBatches.createdAt].kx(),
    )

    private fun ResultRow.toRow() = OcrExtractedRow(
        id = this[OcrExtractedRows.id].value.toString(),
        batchId = this[OcrExtractedRows.batchId].value.toString(),
        accountNumber = this[OcrExtractedRows.accountNumber],
        amount = this[OcrExtractedRows.amount]?.toMoneyString(),
        outstandingBalance = this[OcrExtractedRows.outstandingBalance]?.toMoneyString(),
        dueDate = this[OcrExtractedRows.dueDate]?.kx(),
        ispName = this[OcrExtractedRows.ispName],
        storeName = this[OcrExtractedRows.storeName],
        invoiceNumber = this[OcrExtractedRows.invoiceNumber],
        billNumber = this[OcrExtractedRows.billNumber],
        billingPeriod = this[OcrExtractedRows.billingPeriod],
        matchedAccountId = this[OcrExtractedRows.matchedAccountId]?.value?.toString(),
        reconciled = this[OcrExtractedRows.reconciled],
    )
}
