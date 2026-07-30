package com.puregoldbe.ibms.domain.port

import com.puregoldbe.ibms.domain.model.EmailDeliveryStatus
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import kotlinx.datetime.Instant

/**
 * Narrow in-transaction hook, the email analogue of [ActivityRecorder]. A mutating
 * use case calls [enqueue] beside `activity.record(...)`, so the outbox row commits
 * iff the mutation does (a transactional outbox). Resolving recipients + rendering
 * happens here; the actual send is deferred to the background dispatcher.
 */
interface NotificationEnqueuer {
    fun enqueue(event: NotificationEvent, ctx: NotificationContext)
}

/** An outbox row (`email_log`, status='queued') the dispatcher has yet to send. */
data class QueuedEmail(
    val id: String,
    val type: String?,
    val fromEmail: String?,
    val toEmails: List<String>,
    val subject: String?,
    val bodyText: String?,
    val bodyHtml: String?,
)

/**
 * The `email_log` outbox. Rows are inserted `queued` inside the triggering
 * transaction and drained by [com.puregoldbe.ibms.application.usecase.DispatchQueuedEmailsUseCase].
 */
interface EmailLogRepository {
    /** Insert a `queued` row; returns its id. [toEmails] must be non-empty (column is NOT NULL). */
    fun enqueue(
        type: String,
        fromEmail: String?,
        toEmails: List<String>,
        subject: String,
        bodyText: String,
        bodyHtml: String?,
    ): String

    fun findQueued(limit: Int): List<QueuedEmail>

    /** Move a row out of `queued` to its terminal [status], stamping the response + sent time. */
    fun markResult(id: String, status: EmailDeliveryStatus, providerResponse: String?, at: Instant)
}

/**
 * Per-user notification subscriptions, backed by `user_notification_subscriptions`
 * (V19). A sysadmin edits these through the user profile; recipients for an event
 * are resolved from them at enqueue time.
 */
interface NotificationSubscriptionRepository {
    /**
     * Distinct emails of ACTIVE users (with a non-null email) subscribed to [event].
     * In practice these are the secretary/finance/manager users the sysadmin opted in.
     */
    fun subscribersOf(event: NotificationEvent): List<String>

    /** The set of events a single user is currently subscribed to. */
    fun getForUser(userId: String): Set<NotificationEvent>

    /** Replace a user's subscription set wholesale (delete-then-insert in the ambient tx). */
    fun setForUser(userId: String, events: Set<NotificationEvent>)
}
