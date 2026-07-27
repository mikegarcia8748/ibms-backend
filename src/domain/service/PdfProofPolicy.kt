package com.puregoldbe.ibms.domain.service

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Attachment
import com.puregoldbe.ibms.domain.model.AttachmentPurpose

/**
 * Proof-file rule shared by the upload path and the account operations that require a
 * proof. A proof must be a real PDF that has actually been uploaded — not just a
 * presigned, still-empty attachment row. Byte-level PDF/size checks run once in
 * [com.puregoldbe.ibms.application.usecase.StoreBlobUseCase]; the account use cases
 * re-check the stored metadata before they commit, so an ISP create, transfer or
 * deactivation cannot slip through with a missing or non-PDF proof.
 *
 * Only [PROOF_PURPOSES] are restricted — OCR sources and installation/closure photos
 * keep accepting any file type.
 */
object PdfProofPolicy {
    const val CONTENT_TYPE = "application/pdf"

    /** Max size for an uploaded proof PDF (10 MB). */
    const val MAX_BYTES = 10L * 1024 * 1024

    /** Purposes whose bytes must be a PDF. */
    val PROOF_PURPOSES = setOf(
        AttachmentPurpose.SUBSCRIPTION_PROOF,
        AttachmentPurpose.TRANSFER_PROOF,
        AttachmentPurpose.DEACTIVATION_PROOF,
    )

    /** Every PDF begins with the 4-byte magic `%PDF`. */
    private val MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46)

    fun isPdfBytes(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * Guard for the account use cases: the referenced proof must exist, have been
     * uploaded ([Attachment.sizeBytes] is stamped by StoreBlobUseCase on a successful
     * PDF upload) and be a PDF. [field] names the offending request field in the error.
     */
    fun requireUploadedPdf(attachment: Attachment?, field: String) {
        if (attachment == null) throw DomainError.Validation("a valid $field is required")
        if (attachment.sizeBytes == null) throw DomainError.Validation("$field has not been uploaded yet")
        if (!attachment.contentType.equals(CONTENT_TYPE, ignoreCase = true)) {
            throw DomainError.Validation("$field must be a PDF file")
        }
    }
}
