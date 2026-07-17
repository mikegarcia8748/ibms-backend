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

/** Response after a successful upload; the id is then referenced by stores/accounts/etc. */
@Serializable
data class AttachmentUploadResponse(val attachmentId: String)
