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

    /** No single activity may carry more than this many proof PDFs. */
    const val MAX_PROOFS = 3

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

    /**
     * Collapse the deprecated single-`proofId` body and the `proofIds` array into one
     * list. The array wins when present, so a client that sends both does not get a
     * silent merge — it gets whichever set it actually meant. Blanks are dropped.
     */
    fun mergeProofIds(single: String?, many: List<String>): List<String> =
        (many.ifEmpty { listOfNotNull(single) }).map { it.trim() }.filter { it.isNotBlank() }

    /**
     * The whole proof-set rule for one activity: 1..[MAX_PROOFS] distinct ids, each an
     * existing, fully-uploaded PDF whose purpose matches the activity attaching it.
     * Returns the attachments in request order — that order becomes `sort_order` on the
     * link rows. [lookup] is the repository call, injected so this stays a pure rule.
     *
     * Validating every id up front (not just the first) is what turns an unknown id into
     * a 400 here rather than a foreign-key violation surfacing as a 500 at insert time.
     */
    fun requireProofSet(
        ids: List<String>,
        expected: AttachmentPurpose,
        field: String,
        missingMessage: String = "at least one $field is required",
        lookup: (String) -> Attachment?,
    ): List<Attachment> {
        if (ids.isEmpty()) throw DomainError.Validation(missingMessage)
        if (ids.size > MAX_PROOFS) {
            throw DomainError.Validation("at most $MAX_PROOFS files may be attached to $field")
        }
        if (ids.distinct().size != ids.size) {
            throw DomainError.Validation("$field contains duplicate attachment ids")
        }
        return ids.map { id ->
            val attachment = lookup(id)
            requireUploadedPdf(attachment, field)
            attachment!!
            if (attachment.purpose != expected) {
                throw DomainError.Validation(
                    "$field must reference a ${expected.name.lowercase()} attachment",
                )
            }
            attachment
        }
    }
}
