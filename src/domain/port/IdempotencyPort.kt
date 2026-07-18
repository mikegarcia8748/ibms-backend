package com.puregoldbe.ibms.domain.port

/** A stored idempotency record; `responseBody` is null until the operation completes. */
data class IdempotencyRecord(
    val requestHash: String,
    val responseStatus: Int?,
    val responseBody: String?,
)

/** The idempotency inputs a controller extracts from the request for a money-mutating POST. */
data class IdempotencyContext(
    val key: String,
    val requestHash: String,
    val userId: String?,
)

/**
 * Persistence for [Idempotency-Key] replay. All three methods run inside the caller's
 * existing transaction so the reservation and the mutation commit or roll back together.
 */
interface IdempotencyKeyRepository {
    /** The record for (scope, key), or null if none has been reserved. */
    fun find(scope: String, key: String): IdempotencyRecord?

    /** `INSERT ... ON CONFLICT DO NOTHING`; returns true only if this call reserved the key. */
    fun reserve(scope: String, key: String, userId: String?, requestHash: String): Boolean

    /** Persist the completed response for later replay. */
    fun complete(scope: String, key: String, responseStatus: Int, responseBody: String)
}
