package com.puregoldbe.ibms.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The user actions that trigger an automatic notification email. The [key] is the
 * stable wire/storage identifier: it is the value persisted in
 * `email_log.type` and `user_notification_subscriptions.event_type`, and the string
 * a sysadmin toggles through the subscription API. Keys mirror the existing
 * `activity.record(...)` action strings and the `email_log.type` comment in V1.
 *
 * [label] and [description] are the sysadmin-facing copy for the subscription screen;
 * the frontend renders whatever this catalogue returns rather than hardcoding the set,
 * so adding an event here is all it takes to make it configurable.
 */
enum class NotificationEvent(val key: String, val label: String, val description: String) {
    STORE_CREATED(
        "store.created", "New store added",
        "A secretary registered a new store branch.",
    ),
    ACCOUNT_CREATED(
        "account.created", "New account added",
        "A new ISP account was created for a store.",
    ),
    ACCOUNT_DEACTIVATION_REQUESTED(
        "account.deactivation_requested", "Account termination requested",
        "Deactivation was requested and the 30-day grace period began.",
    ),
    ACCOUNT_TERMINATED(
        "account.terminated", "Account terminated",
        "A grace period expired and the account became inactive.",
    ),
    ACCOUNT_TRANSFERRED(
        "account.transferred", "Account transferred",
        "An account was moved to a different store.",
    ),
    TOPSHEET_COMPILED(
        "topsheet.compiled", "Topsheet compiled",
        "A draft top sheet was confirmed into a compiled billing batch.",
    ),
    // The key stays even where the flow that fires it is switched off: existing
    // user_notification_subscriptions rows store it verbatim, and parseNotificationEvent
    // turns an unrecognised key into a 400 — dropping the constant would make the
    // subscription admin screens fail on data they themselves wrote.
    TOPSHEET_RELEASED(
        "topsheet.released", "Topsheet released to finance",
        "A compiled top sheet was released to Finance for payment. Only fires where the " +
            "external RFP/finance flow is enabled (TOPSHEET_RFP_FLOW_ENABLED).",
    ),
    ACCOUNT_UPDATED(
        "account.updated", "Account details updated",
        "Account details changed directly, or a change request was approved.",
    );

    companion object {
        fun fromKey(key: String): NotificationEvent? = entries.firstOrNull { it.key == key }
    }
}

/** How a bulk subscription write combines the requested events with each target's current set. */
@Serializable
enum class BulkSubscriptionMode {
    /** Union — add the events, keep everything else. */
    @SerialName("add") ADD,

    /** Difference — drop the events, keep everything else. */
    @SerialName("remove") REMOVE,

    /** Overwrite — the target's set becomes exactly the requested events. */
    @SerialName("replace") REPLACE,
}

/**
 * Outcome of a bulk subscription write.
 *
 * [usersChanged] below [usersMatched] is a successful no-op for the difference — those
 * users were already in the requested state. [undeliverableTargets] above zero means
 * the write succeeded but will not reach some of the users it touched.
 */
@Serializable
data class BulkNotificationSubscriptionResult(
    val mode: BulkSubscriptionMode,
    val events: List<String>,
    val usersMatched: Int,
    val usersChanged: Int,
    val undeliverableTargets: Int,
)

/**
 * One row of the sysadmin subscription matrix: a user plus what they are subscribed to.
 *
 * No field carries a default, deliberately. `encodeDefaults` is off, so a defaulted
 * field equal to its default would be dropped from the JSON — and [deliverable] /
 * [notDeliverableReason] are exactly the fields a client must never see as absent
 * (same reasoning as `UserProfile.mustChangePassword`).
 */
@Serializable
data class UserNotificationSubscriptionRow(
    val userId: String,
    val username: String,
    val name: String,
    /** `null` means this user can never receive email, whatever they are subscribed to. */
    val email: String?,
    val role: UserRole,
    val status: UserStatus,
    /** Subscribed event keys, sorted. Empty when the user receives nothing. */
    val subscribed: List<String>,
    /** Whether mail would actually reach them — mirrors the `subscribersOf` filter. */
    val deliverable: Boolean,
    val notDeliverableReason: String?,
) {
    companion object {
        /**
         * Build a row, deriving [deliverable] from the same rule recipient resolution
         * uses (ACTIVE with a non-null email) so the grid cannot disagree with reality.
         */
        fun of(
            userId: String,
            username: String,
            name: String,
            email: String?,
            role: UserRole,
            status: UserStatus,
            subscribed: Set<NotificationEvent>,
        ): UserNotificationSubscriptionRow {
            val noEmail = email.isNullOrBlank()
            val inactive = status != UserStatus.ACTIVE
            return UserNotificationSubscriptionRow(
                userId = userId,
                username = username,
                name = name,
                email = email,
                role = role,
                status = status,
                subscribed = subscribed.map { it.key }.sorted(),
                deliverable = !noEmail && !inactive,
                notDeliverableReason = when {
                    noEmail && inactive -> "no_email_and_inactive"
                    noEmail -> "no_email"
                    inactive -> "inactive"
                    else -> null
                },
            )
        }
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
    /**
     * Who performed the action. Call sites set [actorId]; `NotificationService`
     * resolves the display name into here once, so no use case needs a user
     * repository of its own. Null for the system jobs, which have no actor.
     */
    val actorName: String? = null,
    val details: List<Pair<String, String>> = emptyList(),
    val entityId: String? = null,
    /** The acting user's id, resolved to [actorName] at enqueue time. */
    val actorId: String? = null,
    /**
     * Relative path into the **web client** for the "view in IBMS" button — build it
     * with [DeepLinks], which documents why this is never a path on this API.
     */
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
