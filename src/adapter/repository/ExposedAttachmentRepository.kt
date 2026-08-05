@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.Attachments
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.Attachment
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*

class ExposedAttachmentRepository : AttachmentRepository {

    override fun findById(id: String): Attachment? {
        val uuid = id.toUuidOrNull() ?: return null
        return Attachments.selectAll().where { Attachments.id eq uuid }.map { it.toAttachment() }.singleOrNull()
    }

    override fun findAllById(ids: List<String>): List<Attachment> {
        val uuids = ids.mapNotNull { it.toUuidOrNull() }
        if (uuids.isEmpty()) return emptyList()
        return Attachments.selectAll().where { Attachments.id inList uuids }.map { it.toAttachment() }
    }

    override fun exists(id: String): Boolean {
        val uuid = id.toUuidOrNull() ?: return false
        return Attachments.selectAll().where { Attachments.id eq uuid }.count() > 0
    }

    override fun markUploaded(id: String, sizeBytes: Long, contentType: String) {
        val uuid = id.toUuidOrNull() ?: return
        Attachments.update({ Attachments.id eq uuid }) {
            it[Attachments.sizeBytes] = sizeBytes
            it[Attachments.contentType] = contentType
        }
    }

    override fun create(
        purpose: AttachmentPurpose,
        entityType: String?,
        entityId: String?,
        storageKey: String,
        contentType: String?,
        sizeBytes: Long?,
        uploadedBy: String?,
        fileName: String?,
    ): Attachment {
        val newId = Attachments.insertAndGetId {
            it[Attachments.purpose] = purpose
            if (entityType != null) it[Attachments.entityType] = entityType
            if (entityId != null) it[Attachments.entityId] = kotlin.uuid.Uuid.parse(entityId)
            it[Attachments.storageKey] = storageKey
            if (fileName != null) it[Attachments.fileName] = fileName
            if (contentType != null) it[Attachments.contentType] = contentType
            if (sizeBytes != null) it[Attachments.sizeBytes] = sizeBytes
            if (uploadedBy != null) it[Attachments.uploadedBy] = EntityID(uploadedBy.toUuid(), Users)
        }.value
        return findById(newId.toString())!!
    }

    override fun linkEntity(id: String, entityType: String, entityId: String) {
        val uuid = id.toUuidOrNull() ?: return
        Attachments.update({ Attachments.id eq uuid }) {
            it[Attachments.entityType] = entityType
            it[Attachments.entityId] = kotlin.uuid.Uuid.parse(entityId)
        }
    }

    private fun ResultRow.toAttachment() = Attachment(
        id = this[Attachments.id].value.toString(),
        purpose = this[Attachments.purpose],
        entityType = this[Attachments.entityType],
        entityId = this[Attachments.entityId]?.toString(),
        storageKey = this[Attachments.storageKey],
        fileName = this[Attachments.fileName],
        contentType = this[Attachments.contentType],
        sizeBytes = this[Attachments.sizeBytes],
        uploadedBy = this[Attachments.uploadedBy]?.value?.toString(),
        createdAt = this[Attachments.createdAt].kx(),
    )
}
