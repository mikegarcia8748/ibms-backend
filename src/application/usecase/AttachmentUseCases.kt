package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AccountProof
import com.puregoldbe.ibms.domain.model.AccountProofLink
import com.puregoldbe.ibms.domain.model.Attachment
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.PresignOp
import com.puregoldbe.ibms.domain.port.PresignPort
import com.puregoldbe.ibms.domain.port.StoragePort
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.port.TransferRepository
import com.puregoldbe.ibms.domain.service.PdfProofPolicy
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
        // Fail a proof upload here rather than after the client has pushed 10 MB.
        if (purpose in PdfProofPolicy.PROOF_PURPOSES &&
            contentType != null &&
            !contentType.equals(PdfProofPolicy.CONTENT_TYPE, ignoreCase = true)
        ) {
            throw DomainError.Validation(
                "a ${purpose.name.lowercase()} must be uploaded as ${PdfProofPolicy.CONTENT_TYPE}",
            )
        }
        val safeName = fileName?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.isNotBlank() } ?: "file"
        val key = "${purpose.name.lowercase()}/${UUID.randomUUID()}-$safeName"
        // Keep the raw name too: the sanitizer above destroys spaces and non-ASCII, and
        // the client needs the original back to label the proof.
        val originalName = fileName?.trim()?.take(255)?.takeIf { it.isNotBlank() }
        val att = tx.inTransaction {
            attachments.create(purpose, null, null, key, contentType, null, uploadedBy, originalName)
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
        // Proof files must be real PDFs within the size cap; other purposes (OCR
        // sources, photos) are unrestricted.
        val isProof = att.purpose in PdfProofPolicy.PROOF_PURPOSES
        if (isProof) {
            if (bytes.size > PdfProofPolicy.MAX_BYTES) {
                throw DomainError.Validation("PDF exceeds the ${PdfProofPolicy.MAX_BYTES / (1024 * 1024)} MB limit")
            }
            if (!PdfProofPolicy.isPdfBytes(bytes)) throw DomainError.Validation("proof file must be a PDF")
        }
        storage.put(att.storageKey, bytes)
        // Stamp the row as uploaded so the account use cases can require an actually-
        // uploaded PDF (a presigned-but-never-uploaded row keeps sizeBytes == null).
        if (isProof) {
            tx.inTransaction { attachments.markUploaded(id, bytes.size.toLong(), PdfProofPolicy.CONTENT_TYPE) }
        }
    }
}

/**
 * An account's proofs with the metadata a client needs to label them, newest activity
 * first. Download URLs are minted outside the transaction, like [PresignDownloadUseCase].
 */
class ListAccountProofsUseCase(
    private val accounts: AccountRepository,
    private val attachments: AttachmentRepository,
    private val presign: PresignPort,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(accountId: String, purpose: AttachmentPurpose? = null): List<AccountProof> {
        val links = tx.inTransaction {
            accounts.findById(accountId) ?: throw DomainError.NotFound("account $accountId not found")
            val links = accounts.listProofs(accountId, purpose)
            links to attachments.findAllById(links.map { it.attachmentId }).associateBy { it.id }
        }
        return links.toProofs(presign)
    }
}

/** The proofs of one transfer, deduplicated across its source and destination account. */
class ListTransferProofsUseCase(
    private val transfers: TransferRepository,
    private val accounts: AccountRepository,
    private val attachments: AttachmentRepository,
    private val presign: PresignPort,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(transferId: String): List<AccountProof> {
        val links = tx.inTransaction {
            transfers.findById(transferId) ?: throw DomainError.NotFound("transfer $transferId not found")
            val links = accounts.listProofsByTransfer(transferId).distinctBy { it.attachmentId }
            links to attachments.findAllById(links.map { it.attachmentId }).associateBy { it.id }
        }
        return links.toProofs(presign)
    }
}

private fun Pair<List<AccountProofLink>, Map<String, Attachment>>.toProofs(presign: PresignPort): List<AccountProof> {
    val (links, byId) = this
    return links.map { link ->
        val att = byId[link.attachmentId]
        AccountProof(
            attachmentId = link.attachmentId,
            purpose = link.purpose,
            fileName = att?.fileName,
            contentType = att?.contentType,
            sizeBytes = att?.sizeBytes,
            sortOrder = link.sortOrder,
            linkedAt = link.linkedAt,
            linkedBy = link.linkedBy,
            transferId = link.transferId,
            downloadUrl = presign.presignedUrl(link.attachmentId, PresignOp.DOWNLOAD),
        )
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
