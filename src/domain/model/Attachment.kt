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
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val uploadedBy: String? = null,
    val createdAt: Instant,
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
