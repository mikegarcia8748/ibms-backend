package com.puregoldbe.ibms.support

import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.TransactionRunner
import kotlinx.datetime.Instant

/** Runs the block with no real transaction — for fast use-case unit specs. */
class ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: () -> T): T = block()
}

/** Fixed clock so grace-period / timestamp logic is deterministic under test. */
class FakeClock(var instant: Instant = Instant.fromEpochSeconds(1_700_000_000)) : Clock {
    override fun now(): Instant = instant
}
