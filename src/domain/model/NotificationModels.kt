package com.puregoldbe.ibms.domain.model

/**
 * The user actions that trigger an automatic notification email. The [key] is the
 * stable wire/storage identifier: it is the value persisted in
 * `email_log.type` and `user_notification_subscriptions.event_type`, and the string
 * a sysadmin toggles through the subscription API. Keys mirror the existing
 * `activity.record(...)` action strings and the `email_log.type` comment in V1.
 */
enum class NotificationEvent(val key: String, val label: String) {
    STORE_CREATED("store.created", "New store added"),
    ACCOUNT_CREATED("account.created", "New account added"),
    ACCOUNT_DEACTIVATION_REQUESTED("account.deactivation_requested", "Account termination requested"),
    ACCOUNT_TERMINATED("account.terminated", "Account terminated"),
    ACCOUNT_TRANSFERRED("account.transferred", "Account transferred"),
    TOPSHEET_COMPILED("topsheet.compiled", "Topsheet compiled"),
    TOPSHEET_RELEASED("topsheet.released", "Topsheet released to finance"),
    ACCOUNT_UPDATED("account.updated", "Account details updated");

    companion object {
        fun fromKey(key: String): NotificationEvent? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Everything a template needs to render one notification, built by the triggering
 * use case. Kept deliberately generic (a headline + labelled detail rows + an
 * optional deep-link path) so a new event needs no new template plumbing. Not a
 * wire type — it never leaves the server.
 */
data class NotificationContext(
    val headline: String,
    val actorName: String? = null,
    val details: List<Pair<String, String>> = emptyList(),
    val entityId: String? = null,
    /** Relative path appended to `appUrl` for a "view in IBMS" link, e.g. "/accounts/{id}". */
    val linkPath: String? = null,
)

/** A fully-rendered email handed to [com.puregoldbe.ibms.domain.port.EmailPort]. */
data class EmailMessage(
    val fromEmail: String,
    val fromName: String?,
    val toEmails: List<String>,
    val subject: String,
    val bodyText: String,
    val bodyHtml: String?,
    /** [NotificationEvent.key] — carried through for the `email_log.type` audit column. */
    val type: String,
)

/** Terminal outcomes of a send attempt; the lowercase name is the `email_log.status`. */
enum class EmailDeliveryStatus { SENT, FAILED, SIMULATED }

data class EmailSendResult(
    val status: EmailDeliveryStatus,
    val providerResponse: String?,
)
