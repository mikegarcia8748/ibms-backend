package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.Sessions
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.Session
import com.puregoldbe.ibms.domain.port.SessionRepository
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Session storage for refresh-token rotation. Rows are never deleted — revoking
 * stamps `revoked_at` — so the table stays a record of when each login started
 * and how it ended.
 */
class ExposedSessionRepository : SessionRepository {

    override fun create(
        userId: String,
        refreshTokenHash: String,
        issuedAt: Instant,
        expiresAt: Instant,
        userAgent: String?,
        ipAddress: String?,
    ): Session {
        val newId = Sessions.insertAndGetId {
            it[Sessions.userId] = userId.toUuid()
            it[Sessions.refreshTokenHash] = refreshTokenHash
            it[Sessions.issuedAt] = issuedAt.jt()
            it[Sessions.expiresAt] = expiresAt.jt()
            it[Sessions.lastUsedAt] = issuedAt.jt()
            // user_agent / ip_address are attacker-controlled headers; they are
            // audit breadcrumbs only and are never used in an auth decision.
            it[Sessions.userAgent] = userAgent?.take(512)
            it[Sessions.ipAddress] = ipAddress?.take(64)
        }.value
        return findById(newId.toString())!!
    }

    /**
     * The single lookup the refresh path relies on, so the liveness rules live
     * here in one place: not revoked, and not past its expiry.
     */
    override fun findLiveByHash(refreshTokenHash: String, now: Instant): Session? =
        Sessions.selectAll()
            .where {
                (Sessions.refreshTokenHash eq refreshTokenHash) and
                    Sessions.revokedAt.isNull() and
                    (Sessions.expiresAt greater now.jt())
            }
            .map { it.toSession() }
            .singleOrNull()

    override fun findById(id: String): Session? {
        val uuid = id.toUuidOrNull() ?: return null
        return Sessions.selectAll().where { Sessions.id eq uuid }.map { it.toSession() }.singleOrNull()
    }

    override fun revoke(id: String, at: Instant): Boolean {
        val uuid = id.toUuidOrNull() ?: return false
        // Guarding on revoked_at keeps the original revocation time intact when
        // logout is called twice, which matters for reading the audit trail.
        return Sessions.update({ (Sessions.id eq uuid) and Sessions.revokedAt.isNull() }) {
            it[Sessions.revokedAt] = at.jt()
        } > 0
    }

    override fun revokeAllForUser(userId: String, at: Instant): Int {
        val uuid = userId.toUuidOrNull() ?: return 0
        return Sessions.update({ (Sessions.userId eq uuid) and Sessions.revokedAt.isNull() }) {
            it[Sessions.revokedAt] = at.jt()
        }
    }

    override fun touch(id: String, at: Instant) {
        val uuid = id.toUuidOrNull() ?: return
        Sessions.update({ Sessions.id eq uuid }) { it[Sessions.lastUsedAt] = at.jt() }
    }

    private fun ResultRow.toSession() = Session(
        id = this[Sessions.id].value.toString(),
        userId = this[Sessions.userId].value.toString(),
        issuedAt = this[Sessions.issuedAt].kx(),
        expiresAt = this[Sessions.expiresAt].kx(),
        revokedAt = this[Sessions.revokedAt]?.kx(),
    )
}
