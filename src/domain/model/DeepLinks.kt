package com.puregoldbe.ibms.domain.model

/**
 * The paths a notification email's "View … in IBMS" button opens, in the **web
 * client**, not this API.
 *
 * The distinction is the whole point of this file. Backend routes are mounted at the
 * root with no `/api` prefix, so `/topsheets/{id}` is simultaneously a JSON endpoint
 * here and a page over there. A link built against `APP_URL` reaches the endpoint,
 * which answers a browser with 401 — so these paths are only ever joined to
 * `AppConfig.webClientUrl`, via [absolute].
 *
 * The shapes are a contract with the front end, not an implementation detail:
 * `apicontracts/NOTIFICATION_DEEP_LINK_CONTRACT.md` is the copy the client team reads,
 * and the two must change together. Emails are permanent — a path renamed on one side
 * only breaks every message already in an inbox.
 *
 * Builders interpolate **ids only**. Nothing here percent-encodes a segment, which is
 * harmless while every segment is a UUID and a defect the moment a name or an invoice
 * number is passed instead.
 */
object DeepLinks {

    /** Query flag asking the account screen to open on its activity tab. */
    private const val ACTIVITY_TAB = "?tab=activity"

    fun store(storeId: String): String = "/stores/$storeId"

    fun account(accountId: String): String = "/accounts/$accountId"

    /**
     * The account screen, opened on its activity log — for events whose interest is
     * *what changed*, which the account's own fields don't show. Only worth linking
     * where the triggering use case actually records an activity row.
     */
    fun accountActivity(accountId: String): String = account(accountId) + ACTIVITY_TAB

    fun topsheet(topsheetId: String): String = "/topsheets/$topsheetId"

    /**
     * The change-request diff view. Nested under the account, mirroring the API's own
     * `/accounts/{id}/change-requests/{requestId}`.
     *
     * Preferred over [accountActivity] for an approved change request for a concrete
     * reason: the approval's activity row is recorded against the *request* id, and
     * the activity feed filters on entity id alone, so the change would never appear
     * on the account's activity tab at all.
     */
    fun changeRequest(accountId: String, requestId: String): String =
        account(accountId) + "/change-requests/$requestId"

    /** Joins a relative path from this object onto the web client's base URL. */
    fun absolute(webClientUrl: String, path: String): String = webClientUrl.trimEnd('/') + path
}
