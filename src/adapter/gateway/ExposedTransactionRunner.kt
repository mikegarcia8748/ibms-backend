package com.puregoldbe.ibms.adapter.gateway

import com.puregoldbe.ibms.domain.port.TransactionRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Opens one blocking Exposed transaction on Dispatchers.IO. Repo calls inside the
 * block run in this ambient transaction, giving use cases atomic composition
 * without importing Exposed.
 */
class ExposedTransactionRunner(private val db: Database) : TransactionRunner {
    override suspend fun <T> inTransaction(block: () -> T): T =
        withContext(Dispatchers.IO) {
            transaction(db) { block() }
        }
}
