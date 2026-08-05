package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.port.EmailLogRepository
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.NotificationSubscriptionRepository

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
    private val appUrl: String,
    private val fromEmail: String?,
) : NotificationEnqueuer {

    override fun enqueue(event: NotificationEvent, ctx: NotificationContext) {
        val recipients = subscriptions.subscribersOf(event)
        if (recipients.isEmpty()) return
        val rendered = NotificationTemplates.render(event, ctx, appUrl)
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
