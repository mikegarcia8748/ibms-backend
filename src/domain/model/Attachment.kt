package com.puregoldbe.ibms.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Metadata for a proof file. The bytes live in object storage (local-disk
 * adapter for now); [storageKey] is the path/key. Replaces base64 blobs and
 * Firebase Storage URLs.
 */
@Serializable
data class Attachment(
    val id: String,
    val purpose: AttachmentPurpose,
    val entityType: String? = null,
    val entityId: String? = null,
    val storageKey: String,
    /** Original client-supplied name. [storageKey] carries only a sanitized copy. */
    val fileName: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val uploadedBy: String? = null,
    val createdAt: Instant,
)

/**
 * One proof of one account activity, carrying everything a client needs to label it
 * and fetch it. [purpose] is the purpose of the ACTIVITY that attached the file.
 * Proofs sharing a [linkedAt] belong to the same activity — that is how a client
 * groups "the 2 PDFs from this deactivation request".
 */
@Serializable
data class AccountProof(
    val attachmentId: String,
    val purpose: AttachmentPurpose,
    val fileName: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val sortOrder: Int,
    val linkedAt: Instant,
    val linkedBy: String? = null,
    val transferId: String? = null,
    /** Short-lived signed GET URL; clients must fetch it fresh rather than cache it. */
    val downloadUrl: String,
)

/** The raw `account_attachments` link row backing [AccountProof]. */
data class AccountProofLink(
    val accountId: String,
    val attachmentId: String,
    val purpose: AttachmentPurpose,
    val sortOrder: Int,
    val linkedAt: Instant,
    val linkedBy: String? = null,
    val transferId: String? = null,
)

/**
 * Request for a presigned upload URL. `purpose` is optional (the contract sends only
 * fileName + contentType); it defaults server-side and is metadata on the row — the
 * proof FKs on stores/accounts reference the attachment by id, not by purpose.
 */
@Serializable
data class PresignUploadRequest(
    val fileName: String? = null,
    val contentType: String? = null,
    val purpose: AttachmentPurpose? = null,
)

/** Presigned upload response: the URL to PUT the bytes to, and the reserved attachment id. */
@Serializable
data class PresignUploadResponse(val url: String, val attachmentId: String)

/** Presigned download response: the URL to GET the bytes from. */
@Serializable
data class PresignDownloadResponse(val url: String)
