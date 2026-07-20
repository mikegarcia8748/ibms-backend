package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.IdempotencyKeys
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.domain.port.IdempotencyKeyRepository
import com.puregoldbe.ibms.domain.port.IdempotencyRecord
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant

class ExposedIdempotencyKeyRepository : IdempotencyKeyRepository {

    override fun find(scope: String, key: String): IdempotencyRecord? =
        IdempotencyKeys.selectAll()
            .where { (IdempotencyKeys.scope eq scope) and (IdempotencyKeys.idempotencyKey eq key) }
            .map {
                IdempotencyRecord(
                    requestHash = it[IdempotencyKeys.requestHash],
                    responseStatus = it[IdempotencyKeys.responseStatus],
                    responseBody = it[IdempotencyKeys.responseBody],
                )
            }
            .singleOrNull()

    override fun reserve(scope: String, key: String, userId: String?, requestHash: String): Boolean {
        val stmt = IdempotencyKeys.insertIgnore {
            it[IdempotencyKeys.scope] = scope
            it[IdempotencyKeys.idempotencyKey] = key
            if (userId != null) it[IdempotencyKeys.userId] = EntityID(userId.toUuid(), Users)
            it[IdempotencyKeys.requestHash] = requestHash
        }
        return stmt.insertedCount > 0
    }

    override fun complete(scope: String, key: String, responseStatus: Int, responseBody: String) {
        IdempotencyKeys.update({ (IdempotencyKeys.scope eq scope) and (IdempotencyKeys.idempotencyKey eq key) }) {
            it[IdempotencyKeys.responseStatus] = responseStatus
            it[IdempotencyKeys.responseBody] = responseBody
            it[IdempotencyKeys.completedAt] = Instant.now()
        }
    }
}
