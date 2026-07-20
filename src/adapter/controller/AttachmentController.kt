package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.PresignDownloadUseCase
import com.puregoldbe.ibms.application.usecase.PresignUploadUseCase
import com.puregoldbe.ibms.application.usecase.ReadBlobUseCase
import com.puregoldbe.ibms.application.usecase.StoreBlobUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.PresignDownloadResponse
import com.puregoldbe.ibms.domain.model.PresignUploadRequest
import com.puregoldbe.ibms.domain.model.PresignUploadResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Presigned attachment flow. Authenticated callers get a short-lived signed URL from
 * `/attachments/presign/upload` (or `/{id}/presign/download`); the bytes then move over
 * the PUBLIC, token-gated `/attachments/{id}/blob` route ([attachmentBlobRoutes],
 * registered outside the auth block). Local presign is backed by LocalDiskStorage; a
 * real S3/GCS presigner can replace it behind PresignPort.
 */
fun Route.attachmentRoutes(
    presignUpload: PresignUploadUseCase,
    presignDownload: PresignDownloadUseCase,
) {
    route("/attachments") {
        post("/presign/upload") {
            val caller = call.authorize()
            val req = call.receive<PresignUploadRequest>()
            val purpose = req.purpose ?: AttachmentPurpose.SUBSCRIPTION_PROOF
            val result = presignUpload(purpose, req.fileName, req.contentType, caller.userId)
            call.ok(PresignUploadResponse(result.url, result.attachmentId), "Presigned URL generated!")
        }
        get("/{id}/presign/download") {
            call.authorize()
            call.ok(PresignDownloadResponse(presignDownload(call.pathId())), "Presigned URL generated!")
        }
    }
}

/**
 * PUBLIC token-gated blob transfer — registered OUTSIDE authenticate("auth-jwt"), since
 * the presigned URL is itself the credential. The token binds attachment id + op + expiry.
 */
fun Route.attachmentBlobRoutes(
    storeBlob: StoreBlobUseCase,
    readBlob: ReadBlobUseCase,
) {
    route("/attachments/{id}/blob") {
        put {
            val token = call.request.queryParameters["token"] ?: throw DomainError.Validation("missing token")
            val bytes = call.receive<ByteArray>()
            storeBlob(call.pathId(), token, bytes)
            call.okEmpty("Upload complete!")
        }
        get {
            val token = call.request.queryParameters["token"] ?: throw DomainError.Validation("missing token")
            val blob = readBlob(call.pathId(), token)
            call.respondBytes(blob.bytes, ContentType.parse(blob.contentType ?: "application/octet-stream"))
        }
    }
}
