package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.PresignOp
import com.puregoldbe.ibms.domain.port.PresignPort
import com.puregoldbe.ibms.domain.port.StoragePort
import com.puregoldbe.ibms.domain.port.TransactionRunner
import java.util.UUID

/**
 * Presigned upload: reserve an attachment row (bytes not yet present) and hand back a
 * short-lived signed URL the client PUTs the file to. The generated storage key is
 * `purpose/<uuid>-<filename>`; the bytes arrive later via [StoreBlobUseCase].
 */
class PresignUploadUseCase(
    private val attachments: AttachmentRepository,
    private val presign: PresignPort,
    private val tx: TransactionRunner,
) {
    data class Presigned(val attachmentId: String, val url: String)

    suspend operator fun invoke(
        purpose: AttachmentPurpose,
        fileName: String?,
        contentType: String?,
        uploadedBy: String?,
    ): Presigned {
        val safeName = fileName?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.isNotBlank() } ?: "file"
        val key = "${purpose.name.lowercase()}/${UUID.randomUUID()}-$safeName"
        val att = tx.inTransaction {
            attachments.create(purpose, null, null, key, contentType, null, uploadedBy)
        }
        return Presigned(att.id, presign.presignedUrl(att.id, PresignOp.UPLOAD))
    }
}

/** Presigned download: verify the attachment exists, then return a signed GET URL. */
class PresignDownloadUseCase(
    private val attachments: AttachmentRepository,
    private val presign: PresignPort,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String): String {
        tx.inTransaction { attachments.findById(id) } ?: throw DomainError.NotFound("attachment $id not found")
        return presign.presignedUrl(id, PresignOp.DOWNLOAD)
    }
}

/** Public blob write: token-gated, stores the bytes at the reserved attachment's key. */
class StoreBlobUseCase(
    private val attachments: AttachmentRepository,
    private val storage: StoragePort,
    private val presign: PresignPort,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String, token: String, bytes: ByteArray) {
        if (!presign.isValid(id, PresignOp.UPLOAD, token)) {
            throw DomainError.Unauthorized("invalid or expired upload token")
        }
        if (bytes.isEmpty()) throw DomainError.Validation("uploaded file is empty")
        val att = tx.inTransaction { attachments.findById(id) }
            ?: throw DomainError.NotFound("attachment $id not found")
        storage.put(att.storageKey, bytes)
    }
}

/** Public blob read: token-gated, streams the bytes for the attachment. */
class ReadBlobUseCase(
    private val attachments: AttachmentRepository,
    private val storage: StoragePort,
    private val presign: PresignPort,
    private val tx: TransactionRunner,
) {
    data class Blob(val bytes: ByteArray, val contentType: String?)

    suspend operator fun invoke(id: String, token: String): Blob {
        if (!presign.isValid(id, PresignOp.DOWNLOAD, token)) {
            throw DomainError.Unauthorized("invalid or expired download token")
        }
        val att = tx.inTransaction { attachments.findById(id) }
            ?: throw DomainError.NotFound("attachment $id not found")
        val bytes = storage.read(att.storageKey) ?: throw DomainError.NotFound("file for attachment $id not found")
        return Blob(bytes, att.contentType)
    }
}
