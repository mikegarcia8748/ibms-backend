package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.InvoiceSequences
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.port.InvoiceSequenceRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

class ExposedInvoiceSequenceRepository : InvoiceSequenceRepository {

    override fun seed(providerId: String, prefix: String) {
        val uuid = providerId.toUuid()
        InvoiceSequences.insertIgnore {
            it[InvoiceSequences.providerId] = uuid
            it[InvoiceSequences.prefix] = prefix
            it[InvoiceSequences.currentValue] = 0
        }
    }

    override fun nextValue(providerId: String): Int {
        val uuid = providerId.toUuid()
        // Row-lock to serialize concurrent compilations for the same provider.
        val current = InvoiceSequences.selectAll()
            .where { InvoiceSequences.providerId eq uuid }
            .forUpdate()
            .map { it[InvoiceSequences.currentValue] }
            .single()
        val next = current + 1
        InvoiceSequences.update({ InvoiceSequences.providerId eq uuid }) {
            it[InvoiceSequences.currentValue] = next
        }
        return next
    }

    override fun prefixOf(providerId: String): String? {
        val uuid = providerId.toUuidOrNull() ?: return null
        return InvoiceSequences.selectAll()
            .where { InvoiceSequences.providerId eq uuid }
            .map { it[InvoiceSequences.prefix] }
            .singleOrNull()
    }
}
