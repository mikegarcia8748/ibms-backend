package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.BatchSequences
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.domain.port.BatchSequenceRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

class ExposedBatchSequenceRepository : BatchSequenceRepository {

    override fun seed(providerId: String) {
        val uuid = providerId.toUuid()
        BatchSequences.insertIgnore {
            it[BatchSequences.providerId] = uuid
            it[BatchSequences.currentValue] = 0
        }
    }

    override fun nextValue(providerId: String): Int {
        val uuid = providerId.toUuid()
        val current = BatchSequences.selectAll()
            .where { BatchSequences.providerId eq uuid }
            .forUpdate()
            .map { it[BatchSequences.currentValue] }
            .single()
        val next = current + 1
        BatchSequences.update({ BatchSequences.providerId eq uuid }) {
            it[BatchSequences.currentValue] = next
        }
        return next
    }
}
