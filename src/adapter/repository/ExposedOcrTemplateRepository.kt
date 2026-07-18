package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.OcrTemplates
import com.puregoldbe.ibms.adapter.db.Providers
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.OcrTemplate
import com.puregoldbe.ibms.domain.model.OcrTemplateUpsertRequest
import com.puregoldbe.ibms.domain.port.OcrTemplateRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*

class ExposedOcrTemplateRepository : OcrTemplateRepository {

    override fun list(): List<OcrTemplate> =
        OcrTemplates.selectAll().orderBy(OcrTemplates.configKey).map { it.toTemplate() }

    override fun findById(id: String): OcrTemplate? {
        val uuid = id.toUuidOrNull() ?: return null
        return OcrTemplates.selectAll().where { OcrTemplates.id eq uuid }.map { it.toTemplate() }.singleOrNull()
    }

    override fun findByKey(configKey: String): OcrTemplate? =
        OcrTemplates.selectAll().where { OcrTemplates.configKey eq configKey }.map { it.toTemplate() }.singleOrNull()

    override fun create(input: OcrTemplateUpsertRequest): OcrTemplate {
        val id = OcrTemplates.insertAndGetId {
            it[OcrTemplates.configKey] = input.configKey
            it[OcrTemplates.formatName] = input.formatName
            it[OcrTemplates.providerId] = input.providerId?.let { p -> EntityID(p.toUuid(), Providers) }
            it[OcrTemplates.ispName] = input.ispName
            it[OcrTemplates.aiPromptInstruction] = input.aiPromptInstruction
            it[OcrTemplates.accountNumberPattern] = input.accountNumberPattern
            it[OcrTemplates.amountPattern] = input.amountPattern
            it[OcrTemplates.dueDatePattern] = input.dueDatePattern
            it[OcrTemplates.invoiceNumberPattern] = input.invoiceNumberPattern
            it[OcrTemplates.billingPeriodPattern] = input.billingPeriodPattern
            it[OcrTemplates.detectorKeyword] = input.detectorKeyword
            it[OcrTemplates.sampleFileText] = input.sampleFileText
        }.value
        return findById(id.toString())!!
    }

    override fun update(id: String, input: OcrTemplateUpsertRequest): OcrTemplate? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = OcrTemplates.update({ OcrTemplates.id eq uuid }) {
            it[OcrTemplates.configKey] = input.configKey
            it[OcrTemplates.formatName] = input.formatName
            it[OcrTemplates.providerId] = input.providerId?.let { p -> EntityID(p.toUuid(), Providers) }
            it[OcrTemplates.ispName] = input.ispName
            it[OcrTemplates.aiPromptInstruction] = input.aiPromptInstruction
            it[OcrTemplates.accountNumberPattern] = input.accountNumberPattern
            it[OcrTemplates.amountPattern] = input.amountPattern
            it[OcrTemplates.dueDatePattern] = input.dueDatePattern
            it[OcrTemplates.invoiceNumberPattern] = input.invoiceNumberPattern
            it[OcrTemplates.billingPeriodPattern] = input.billingPeriodPattern
            it[OcrTemplates.detectorKeyword] = input.detectorKeyword
            it[OcrTemplates.sampleFileText] = input.sampleFileText
        }
        return if (n == 0) null else findById(id)
    }

    private fun ResultRow.toTemplate() = OcrTemplate(
        id = this[OcrTemplates.id].value.toString(),
        configKey = this[OcrTemplates.configKey],
        providerId = this[OcrTemplates.providerId]?.value?.toString(),
        ispName = this[OcrTemplates.ispName],
        formatName = this[OcrTemplates.formatName],
        aiPromptInstruction = this[OcrTemplates.aiPromptInstruction],
        accountNumberPattern = this[OcrTemplates.accountNumberPattern],
        amountPattern = this[OcrTemplates.amountPattern],
        dueDatePattern = this[OcrTemplates.dueDatePattern],
        invoiceNumberPattern = this[OcrTemplates.invoiceNumberPattern],
        billingPeriodPattern = this[OcrTemplates.billingPeriodPattern],
        detectorKeyword = this[OcrTemplates.detectorKeyword],
        sampleFileText = this[OcrTemplates.sampleFileText],
        createdAt = this[OcrTemplates.createdAt].kx(),
        updatedAt = this[OcrTemplates.updatedAt].kx(),
    )
}
