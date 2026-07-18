package com.puregoldbe.ibms.support

import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.IdempotencyKeyRepository
import com.puregoldbe.ibms.domain.port.IdempotencyRecord
import com.puregoldbe.ibms.domain.port.TransactionRunner
import kotlinx.datetime.Instant

/** Runs the block with no real transaction — for fast use-case unit specs. */
class ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: () -> T): T = block()
}

/** In-memory idempotency store mirroring the reserve/replay contract, for use-case specs. */
class FakeIdempotencyKeyRepository : IdempotencyKeyRepository {
    private class Row(val requestHash: String, var status: Int?, var body: String?)

    private val rows = mutableMapOf<Pair<String, String>, Row>()

    override fun find(scope: String, key: String): IdempotencyRecord? =
        rows[scope to key]?.let { IdempotencyRecord(it.requestHash, it.status, it.body) }

    override fun reserve(scope: String, key: String, userId: String?, requestHash: String): Boolean {
        if (rows.containsKey(scope to key)) return false
        rows[scope to key] = Row(requestHash, null, null)
        return true
    }

    override fun complete(scope: String, key: String, responseStatus: Int, responseBody: String) {
        rows[scope to key]?.let { it.status = responseStatus; it.body = responseBody }
    }
}

/** Fixed clock so grace-period / timestamp logic is deterministic under test. */
class FakeClock(var instant: Instant = Instant.fromEpochSeconds(1_700_000_000)) : Clock {
    override fun now(): Instant = instant
}
