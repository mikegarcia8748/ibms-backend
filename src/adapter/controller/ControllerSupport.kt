package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.db.clampLimit
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.ApiResponse
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.ProviderStatus
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.model.UserStatus
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/** Shared controller helpers: path/enum query-param parsing. */

internal fun ApplicationCall.pathId(): String =
    parameters["id"] ?: throw DomainError.Validation("missing path id")

/** Cursor + clamped page size parsed from `?cursor=&limit=` for paginated list endpoints. */
internal data class PageParams(val cursor: String?, val limit: Int)

internal fun ApplicationCall.pageParams(): PageParams = PageParams(
    cursor = request.queryParameters["cursor"]?.takeIf { it.isNotBlank() },
    limit = clampLimit(request.queryParameters["limit"]?.toIntOrNull()),
)

/**
 * Build an [IdempotencyContext] from the `Idempotency-Key` header, or null when the
 * header is absent. [canonicalBody] is a stable string form of the request (the
 * request hash guards against the same key being reused for a different payload).
 */
internal fun ApplicationCall.idempotencyContext(userId: String?, canonicalBody: String): IdempotencyContext? =
    request.headers["Idempotency-Key"]?.takeIf { it.isNotBlank() }?.let { key ->
        IdempotencyContext(key = key, requestHash = sha256Hex(canonicalBody), userId = userId)
    }

private fun sha256Hex(input: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

private inline fun <reified T : Enum<T>> parseEnum(raw: String?): T? =
    raw?.takeIf { it.isNotBlank() }?.let { value ->
        runCatching { enumValueOf<T>(value.uppercase()) }.getOrNull()
    }

internal fun parseUserRole(raw: String?): UserRole? = parseEnum<UserRole>(raw)
internal fun parseUserStatus(raw: String?): UserStatus? = parseEnum<UserStatus>(raw)
internal fun parseProviderStatus(raw: String?): ProviderStatus? = parseEnum<ProviderStatus>(raw)
internal fun parseStoreStatus(raw: String?): StoreStatus? = parseEnum<StoreStatus>(raw)
internal fun parseAccountStatus(raw: String?): AccountStatus? = parseEnum<AccountStatus>(raw)
internal fun parseAttachmentPurpose(raw: String?): AttachmentPurpose? = parseEnum<AttachmentPurpose>(raw)
internal fun parseTopSheetStatus(raw: String?): TopSheetStatus? = parseEnum<TopSheetStatus>(raw)

// ---------------------------------------------------------------------------
//  Response envelope helpers (API_CONTRACT: {result, message, status, data}).
//  `inline reified` keeps the generic ApiResponse<T> serializer resolved at the
//  call site — never respond an erased `Any`. Binary responses (xlsx, blobs)
//  deliberately bypass these and use respondBytes.
// ---------------------------------------------------------------------------
suspend inline fun <reified T> ApplicationCall.envelope(status: HttpStatusCode, data: T, message: String) =
    respond(status, ApiResponse("success", message, status.value.toString(), data))

suspend inline fun <reified T> ApplicationCall.ok(data: T, message: String = "success") =
    envelope(HttpStatusCode.OK, data, message)

suspend inline fun <reified T> ApplicationCall.created(data: T, message: String = "created") =
    envelope(HttpStatusCode.Created, data, message)

suspend inline fun <reified T> ApplicationCall.accepted(data: T, message: String = "accepted") =
    envelope(HttpStatusCode.Accepted, data, message)

/** Success envelope carrying `data: null` (concrete-typed; avoids serializing Unit as `{}`). */
suspend fun ApplicationCall.okEmpty(message: String = "success") =
    respond(HttpStatusCode.OK, ApiResponse<String?>("success", message, HttpStatusCode.OK.value.toString(), null))
