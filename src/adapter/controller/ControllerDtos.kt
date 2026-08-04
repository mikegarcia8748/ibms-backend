package com.puregoldbe.ibms.adapter.controller

import kotlinx.serialization.Serializable

/** Small request bodies that aren't part of the shared domain model. */

@Serializable
data class CreateProviderRequest(val name: String, val paymentScheduleDay: Int)

@Serializable
data class UpdateProviderRequest(val name: String? = null, val paymentScheduleDay: Int? = null)

@Serializable
data class RejectChangeRequestBody(val reason: String)

/** Sysad replaces a user's notification subscriptions wholesale (list of event keys). */
@Serializable
data class UpdateNotificationSubscriptionsRequest(val events: List<String>)

/** A user's current subscriptions plus the full catalogue of selectable events (key + label). */
@Serializable
data class NotificationSubscriptionsResponse(
    val subscribed: List<String>,
    val available: List<NotificationEventInfo>,
)

/**
 * One entry of the event catalogue. [deliverableSubscribers] is populated only by
 * `GET /admin/notifications/events`; elsewhere it is null and therefore omitted from
 * the JSON (`encodeDefaults` is off), keeping the embedded `available[]` arrays lean.
 */
@Serializable
data class NotificationEventInfo(
    val key: String,
    val label: String,
    val description: String,
    val deliverableSubscribers: Int? = null,
)

/** The full catalogue of subscribable events, with live recipient counts. */
@Serializable
data class NotificationEventCatalogResponse(val events: List<NotificationEventInfo>)

/**
 * A bulk subscription write. `mode` and `roles` are plain strings rather than enums
 * on purpose: an unrecognised enum value would fail inside content negotiation and
 * surface as a 500, whereas parsing here yields the documented 400.
 */
@Serializable
data class BulkNotificationSubscriptionRequest(
    val mode: String,
    val events: List<String> = emptyList(),
    val userIds: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
)

/** One role's default subscriptions; `role` is the lowercase role wire value. */
@Serializable
data class NotificationRoleDefaultEntry(val role: String, val events: List<String>)

/** Defaults for every role, in enum declaration order — roles with none carry `[]`. */
@Serializable
data class NotificationRoleDefaultsResponse(val defaults: List<NotificationRoleDefaultEntry>)

@Serializable
data class UpdateNotificationRoleDefaultsRequest(val defaults: List<NotificationRoleDefaultEntry>)

/** Set or clear a user's notification delivery address. An explicit `null` clears it. */
@Serializable
data class UpdateUserEmailRequest(val email: String? = null)

/**
 * Summary of a bulk-import run: counts of entities created vs reused, plus reasons.
 *
 * `rowsSkipped`/`skipReasons` are rows rejected during pre-DB validation (missing
 * required fields, invalid/zero amount). `rowsFailed`/`failureReasons` are rows that
 * passed validation but threw while committing to the DB — the import is partial:
 * valid rows commit and failed rows are reported here rather than aborting the run.
 */
@Serializable
data class BulkImportSummary(
    val providers: List<ProviderImportSummary>,
    val storesCreated: Int,
    val storesReused: Int,
    val accountsCreated: Int,
    val accountsReused: Int,
    val rowsSkipped: Int,
    val skipReasons: List<String>,
    val totalRows: Int,
    val rowsFailed: Int = 0,
    val failureReasons: List<String> = emptyList(),
)

@Serializable
data class ProviderImportSummary(
    val name: String,
    val created: Boolean,
    val accountsCreated: Int,
    val accountsReused: Int,
)

@Serializable
data class UpdateLineRequest(
    val proratedAmount: String? = null,
)

/** Cheque number recorded by Finance to fully pay (close) a topsheet. */
@Serializable
data class PayTopSheetRequest(val chequeNumber: String)
