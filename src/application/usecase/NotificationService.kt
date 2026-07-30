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

/**
 * One generic template for every event: a subject from the event label, then the
 * headline, labelled detail rows, actor, and an optional deep link. Adding an event
 * needs no template change — the triggering use case just supplies a richer context.
 */
internal object NotificationTemplates {

    data class Rendered(val subject: String, val text: String, val html: String)

    fun render(event: NotificationEvent, ctx: NotificationContext, appUrl: String): Rendered {
        val subject = "[IBMS] ${event.label}"
        val link = ctx.linkPath?.let { appUrl.trimEnd('/') + it }

        val textLines = buildList {
            add(event.label)
            add("")
            add(ctx.headline)
            if (ctx.details.isNotEmpty()) {
                add("")
                ctx.details.forEach { (label, value) -> add("$label: $value") }
            }
            ctx.actorName?.let { add(""); add("Performed by: $it") }
            link?.let { add(""); add("View in IBMS: $it") }
            add("")
            add("— This is an automated notification from IBMS. Please do not reply.")
        }

        val detailRows = ctx.details.joinToString("") { (label, value) ->
            "<li><strong>${esc(label)}:</strong> ${esc(value)}</li>"
        }
        val html = buildString {
            append("<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#1f2937\">")
            append("<h2 style=\"margin:0 0 8px\">${esc(event.label)}</h2>")
            append("<p style=\"margin:0 0 12px\">${esc(ctx.headline)}</p>")
            if (ctx.details.isNotEmpty()) append("<ul style=\"margin:0 0 12px;padding-left:18px\">$detailRows</ul>")
            ctx.actorName?.let { append("<p style=\"margin:0 0 4px;color:#6b7280\">Performed by: ${esc(it)}</p>") }
            link?.let { append("<p style=\"margin:8px 0\"><a href=\"${esc(it)}\">View in IBMS</a></p>") }
            append("<hr style=\"border:none;border-top:1px solid #e5e7eb;margin:16px 0\"/>")
            append("<p style=\"font-size:12px;color:#9ca3af;margin:0\">This is an automated notification from IBMS. Please do not reply.</p>")
            append("</div>")
        }

        return Rendered(subject, textLines.joinToString("\n"), html)
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
