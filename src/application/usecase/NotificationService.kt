package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.port.EmailLogRepository
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.NotificationSubscriptionRepository
import com.puregoldbe.ibms.domain.port.UserRepository

/**
 * Resolves recipients + renders a notification and writes it to the `email_log`
 * outbox as `queued`. Runs INSIDE the triggering use case's transaction (the
 * repos it calls use the ambient Exposed transaction), so the outbox row commits
 * atomically with the business change — the email analogue of `activity.record`.
 * The actual send is deferred to [DispatchQueuedEmailsUseCase].
 *
 * If nobody is subscribed to the event, nothing is written (the `to_emails`
 * column is NOT NULL — an empty send would be meaningless anyway).
 */
class NotificationService(
    private val subscriptions: NotificationSubscriptionRepository,
    private val emailLog: EmailLogRepository,
    private val users: UserRepository,
    /**
     * The **web client's** base URL, never this API's. The button in the email opens a
     * page for a human; every route here answers a browser with 401. See `DeepLinks`.
     */
    private val webClientUrl: String,
    private val fromEmail: String?,
) : NotificationEnqueuer {

    override fun enqueue(event: NotificationEvent, ctx: NotificationContext) {
        val recipients = subscriptions.subscribersOf(event)
        if (recipients.isEmpty()) return
        // Resolved once here rather than at nine call sites, and only after the early
        // return above, so a notification nobody is subscribed to still costs one query.
        // A blank or unresolvable actor yields null, which renders no "Performed by" line
        // instead of a dangling one.
        val named = ctx.actorId
            ?.let { users.findById(it)?.name?.takeIf(String::isNotBlank) }
            ?.let { ctx.copy(actorName = it) }
            ?: ctx
        val rendered = NotificationTemplates.render(event, named, webClientUrl)
        emailLog.enqueue(
            type = event.key,
            fromEmail = fromEmail,
            toEmails = recipients,
            subject = rendered.subject,
            bodyText = rendered.text,
            bodyHtml = rendered.html,
        )
    }
}
