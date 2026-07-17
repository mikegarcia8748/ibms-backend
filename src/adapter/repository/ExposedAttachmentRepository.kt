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

    override fun exists(id: String): Boolean {
        val uuid = id.toUuidOrNull() ?: return false
        return Attachments.selectAll().where { Attachments.id eq uuid }.count() > 0
    }

    override fun create(
        purpose: AttachmentPurpose,
        entityType: String?,
        entityId: String?,
        storageKey: String,
        contentType: String?,
        sizeBytes: Long?,
        uploadedBy: String?,
    ): Attachment {
        val newId = Attachments.insertAndGetId {
            it[Attachments.purpose] = purpose
            if (entityType != null) it[Attachments.entityType] = entityType
            if (entityId != null) it[Attachments.entityId] = kotlin.uuid.Uuid.parse(entityId)
            it[Attachments.storageKey] = storageKey
            if (contentType != null) it[Attachments.contentType] = contentType
            if (sizeBytes != null) it[Attachments.sizeBytes] = sizeBytes
            if (uploadedBy != null) it[Attachments.uploadedBy] = EntityID(uploadedBy.toUuid(), Users)
        }.value
        return findById(newId.toString())!!
    }

    private fun ResultRow.toAttachment() = Attachment(
        id = this[Attachments.id].value.toString(),
        purpose = this[Attachments.purpose],
        entityType = this[Attachments.entityType],
        entityId = this[Attachments.entityId]?.toString(),
        storageKey = this[Attachments.storageKey],
        contentType = this[Attachments.contentType],
        sizeBytes = this[Attachments.sizeBytes],
        uploadedBy = this[Attachments.uploadedBy]?.value?.toString(),
        createdAt = this[Attachments.createdAt].kx(),
    )
}
