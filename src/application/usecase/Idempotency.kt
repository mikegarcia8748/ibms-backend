package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.domain.port.IdempotencyKeyRepository
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Reserve/replay wrapper for money-mutating operations. Runs INSIDE the caller's
 * transaction so the idempotency row and the mutation are atomic:
 *  - the first call reserves the key, runs [mutate], and stores the serialized result;
 *  - a duplicate key with the same request replays the stored result (no re-run);
 *  - a duplicate key with a *different* request body is rejected (409 Conflict);
 *  - a failing [mutate] rolls back the whole transaction, reservation included, so
 *    only successful responses are ever persisted and replayed.
 * Pass [ctx] = null (no Idempotency-Key header) to run [mutate] directly.
 */
internal inline fun <reified T> idempotent(
    idempotency: IdempotencyKeyRepository,
    scope: String,
    ctx: IdempotencyContext?,
    successStatus: Int,
    mutate: () -> T,
): T {
    if (ctx == null) return mutate()

    idempotency.find(scope, ctx.key)?.let { rec ->
        if (rec.requestHash != ctx.requestHash) {
            throw DomainError.Conflict("Idempotency-Key reused with a different request", "idempotency_conflict")
        }
        rec.responseBody?.let { return Json.decodeFromString<T>(it) }
        throw DomainError.Conflict("a request with this Idempotency-Key is still in progress", "idempotency_in_progress")
    }

    if (!idempotency.reserve(scope, ctx.key, ctx.userId, ctx.requestHash)) {
        val rec = idempotency.find(scope, ctx.key)
        if (rec?.responseBody != null && rec.requestHash == ctx.requestHash) {
            return Json.decodeFromString<T>(rec.responseBody)
        }
        throw DomainError.Conflict("a request with this Idempotency-Key is still in progress", "idempotency_in_progress")
    }

    val result = mutate()
    idempotency.complete(scope, ctx.key, successStatus, Json.encodeToString(result))
    return result
}
