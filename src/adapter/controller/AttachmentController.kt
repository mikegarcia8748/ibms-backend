package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.DownloadAttachmentUseCase
import com.puregoldbe.ibms.application.usecase.UploadAttachmentUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AttachmentUploadResponse
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Proof-file upload/download. Phase 1 uses a raw-body upload (Content-Type header +
 * ?purpose=&fileName= query) straight to the local-disk StoragePort; a presigned
 * GCS/S3 flow can replace this later without changing callers.
 */
fun Route.attachmentRoutes(
    uploadAttachment: UploadAttachmentUseCase,
    downloadAttachment: DownloadAttachmentUseCase,
) {
    route("/attachments") {
        post {
            val caller = call.authorize(UserRole.SYSADMIN, UserRole.SECRETARY, UserRole.PAYABLES)
            val purpose = parseAttachmentPurpose(call.request.queryParameters["purpose"])
                ?: throw DomainError.Validation("a valid ?purpose= is required")
            val fileName = call.request.queryParameters["fileName"]
            val contentType = call.request.contentType().takeIf { it != ContentType.Any }?.toString()
            val bytes = call.receive<ByteArray>()
            val attachment = uploadAttachment(purpose, fileName, contentType, bytes, caller.userId)
            call.respond(HttpStatusCode.Created, AttachmentUploadResponse(attachment.id))
        }
        get("/{id}") {
            call.authorize()
            val download = downloadAttachment(call.pathId())
            call.respondBytes(
                download.bytes,
                ContentType.parse(download.attachment.contentType ?: "application/octet-stream"),
            )
        }
    }
}
