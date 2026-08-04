package com.puregoldbe.ibms.domain.port

import com.puregoldbe.ibms.domain.model.BulkSubscriptionMode
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.EmailDeliveryStatus
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.UserNotificationSubscriptionRow
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.model.UserStatus
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
 * (V20). A sysadmin edits these through the user profile or the bulk admin endpoint;
 * recipients for an event are resolved from them at enqueue time.
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

    /**
     * One page of the org-wide subscription matrix, keyset-ordered by `(created_at, id)`
     * over users. Users subscribed to nothing are included so the admin grid can offer
     * them for opt-in.
     *
     * [deliverable] filters on whether mail would actually reach the user, and is
     * deliberately asymmetric: `true` means "active with an email address", while
     * `false` means "cannot receive **and** is subscribed to at least one event" — the
     * misconfiguration worklist, rather than every dormant account.
     */
    fun pageUserSubscriptions(
        role: UserRole?,
        status: UserStatus?,
        event: NotificationEvent?,
        deliverable: Boolean?,
        cursor: String?,
        limit: Int,
    ): CursorPage<UserNotificationSubscriptionRow>

    /**
     * Per-event count of recipients [subscribersOf] would resolve today. Events absent
     * from the map have none, which means the notification is silently going nowhere.
     */
    fun deliverableSubscriberCounts(): Map<NotificationEvent, Int>

    /**
     * Apply [events] to every user in [userIds] per [mode], returning how many users'
     * subscription sets actually changed (the rest were already in the target state).
     */
    fun applyForUsers(userIds: List<String>, events: Set<NotificationEvent>, mode: BulkSubscriptionMode): Int
}

/**
 * Per-role default subscriptions (`notification_role_defaults`, V21). Read once when a
 * user is provisioned to seed their subscriptions; never consulted afterwards, so
 * editing a default does not retrofit existing users.
 */
interface NotificationRoleDefaultsRepository {
    /** Defaults for every role that has any. Roles with none are simply absent. */
    fun all(): Map<UserRole, Set<NotificationEvent>>

    fun forRole(role: UserRole): Set<NotificationEvent>

    /** Replace one role's defaults wholesale; an empty [events] clears them. */
    fun setForRole(role: UserRole, events: Set<NotificationEvent>)
}
