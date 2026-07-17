package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Attachment
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.StoragePort
import com.puregoldbe.ibms.domain.port.TransactionRunner
import java.util.UUID

/**
 * Streams uploaded bytes to the storage adapter (local disk now), then records the
 * attachment row. The generated storage_key is `purpose/<uuid>-<filename>`.
 */
class UploadAttachmentUseCase(
    private val attachments: AttachmentRepository,
    private val storage: StoragePort,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        purpose: AttachmentPurpose,
        fileName: String?,
        contentType: String?,
        bytes: ByteArray,
        uploadedBy: String?,
    ): Attachment {
        if (bytes.isEmpty()) throw DomainError.Validation("uploaded file is empty")
        val safeName = fileName?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.isNotBlank() } ?: "file"
        val key = "${purpose.name.lowercase()}/${UUID.randomUUID()}-$safeName"
        storage.put(key, bytes)
        return tx.inTransaction {
            attachments.create(purpose, null, null, key, contentType, bytes.size.toLong(), uploadedBy)
        }
    }
}

class DownloadAttachmentUseCase(
    private val attachments: AttachmentRepository,
    private val storage: StoragePort,
    private val tx: TransactionRunner,
) {
    data class Download(val attachment: Attachment, val bytes: ByteArray)

    suspend operator fun invoke(id: String): Download {
        val att = tx.inTransaction { attachments.findById(id) }
            ?: throw DomainError.NotFound("attachment $id not found")
        val bytes = storage.read(att.storageKey)
            ?: throw DomainError.NotFound("file for attachment $id not found")
        return Download(att, bytes)
    }
}
