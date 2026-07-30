@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.EmailLog
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.EmailDeliveryStatus
import com.puregoldbe.ibms.domain.port.EmailLogRepository
import com.puregoldbe.ibms.domain.port.QueuedEmail
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The `email_log` outbox. Insert `queued` inside the triggering transaction;
 * [findQueued] + [markResult] are the drain side run by the dispatcher.
 */
class ExposedEmailLogRepository : EmailLogRepository {

    override fun enqueue(
        type: String,
        fromEmail: String?,
        toEmails: List<String>,
        subject: String,
        bodyText: String,
        bodyHtml: String?,
    ): String {
        val id = EmailLog.insertAndGetId {
            it[EmailLog.type] = type
            it[EmailLog.fromEmail] = fromEmail
            it[EmailLog.toEmails] = toEmails
            it[EmailLog.subject] = subject
            it[EmailLog.bodyText] = bodyText
            it[EmailLog.bodyHtml] = bodyHtml
            it[EmailLog.status] = "queued"
        }.value
        return id.toString()
    }

    override fun findQueued(limit: Int): List<QueuedEmail> =
        EmailLog.selectAll()
            .where { EmailLog.status eq "queued" }
            .orderBy(EmailLog.createdAt to SortOrder.ASC)
            .limit(limit)
            .map { it.toQueuedEmail() }

    override fun markResult(id: String, status: EmailDeliveryStatus, providerResponse: String?, at: Instant) {
        val uuid = id.toUuidOrNull() ?: return
        EmailLog.update({ EmailLog.id eq uuid }) {
            it[EmailLog.status] = status.name.lowercase()
            it[EmailLog.providerResponse] = providerResponse
            it[EmailLog.sentAt] = at.jt()
        }
    }

    private fun ResultRow.toQueuedEmail() = QueuedEmail(
        id = this[EmailLog.id].value.toString(),
        type = this[EmailLog.type],
        fromEmail = this[EmailLog.fromEmail],
        toEmails = this[EmailLog.toEmails],
        subject = this[EmailLog.subject],
        bodyText = this[EmailLog.bodyText],
        bodyHtml = this[EmailLog.bodyHtml],
    )
}
