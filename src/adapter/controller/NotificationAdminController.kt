package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.BulkUpdateNotificationSubscriptionsUseCase
import com.puregoldbe.ibms.application.usecase.CountDeliverableSubscribersUseCase
import com.puregoldbe.ibms.application.usecase.GetNotificationRoleDefaultsUseCase
import com.puregoldbe.ibms.application.usecase.ListUserNotificationSubscriptionsUseCase
import com.puregoldbe.ibms.application.usecase.UpdateNotificationRoleDefaultsUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.BulkSubscriptionMode
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.request.*
import io.ktor.server.routing.*

/**
 * Sysadmin administration of notification subscriptions — the catalogue, the org-wide
 * matrix, bulk writes and per-role defaults. See
 * `apicontracts/NOTIFICATION_SUBSCRIPTION_ADMIN_API_CONTRACT.md`.
 *
 * The single-user read/write pair stays on `/users/{id}/notification-subscriptions`
 * (see [userRoutes]) because it belongs to the user's profile screen.
 */
fun Route.notificationAdminRoutes(
    countDeliverableSubscribers: CountDeliverableSubscribersUseCase,
    listUserSubscriptions: ListUserNotificationSubscriptionsUseCase,
    bulkUpdateSubscriptions: BulkUpdateNotificationSubscriptionsUseCase,
    getRoleDefaults: GetNotificationRoleDefaultsUseCase,
    updateRoleDefaults: UpdateNotificationRoleDefaultsUseCase,
) {
    route("/admin/notifications") {
        // The catalogue the frontend renders its grid columns from — never hardcoded
        // client-side, so shipping a ninth event needs no frontend release.
        get("/events") {
            call.authorize(UserRole.SYSADMIN)
            val counts = countDeliverableSubscribers()
            call.ok(
                NotificationEventCatalogResponse(
                    NotificationEvent.entries.map { event ->
                        NotificationEventInfo(
                            key = event.key,
                            label = event.label,
                            description = event.description,
                            deliverableSubscribers = counts[event] ?: 0,
                        )
                    },
                ),
            )
        }

        get("/subscriptions") {
            call.authorize(UserRole.SYSADMIN)
            val p = call.pageParams()
            val q = call.request.queryParameters
            call.ok(
                listUserSubscriptions(
                    role = parseUserRole(q["role"]),
                    status = parseUserStatus(q["status"]),
                    event = q["event"]?.takeIf { it.isNotBlank() }?.let { parseNotificationEvent(it) },
                    // Unparseable means "no filter", matching the lenient role/status filters.
                    deliverable = q["deliverable"]?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull(),
                    cursor = p.cursor,
                    limit = p.limit,
                ),
            )
        }

        post("/subscriptions/bulk") {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<BulkNotificationSubscriptionRequest>()
            call.ok(
                bulkUpdateSubscriptions(
                    mode = parseBulkSubscriptionMode(req.mode),
                    events = req.events.map { parseNotificationEvent(it) }.toSet(),
                    userIds = req.userIds,
                    roles = req.roles.map { parseRoleOrFail(it) }.toSet(),
                ),
            )
        }

        get("/defaults") {
            call.authorize(UserRole.SYSADMIN)
            call.ok(roleDefaultsResponse(getRoleDefaults()))
        }
        // Partial by role: only the roles named in the body are rewritten. The response
        // carries every role so the client can replace its cache without a re-read.
        put("/defaults") {
            call.authorize(UserRole.SYSADMIN)
            val req = call.receive<UpdateNotificationRoleDefaultsRequest>()
            val updates = req.defaults.map { entry ->
                parseRoleOrFail(entry.role) to entry.events.map { parseNotificationEvent(it) }.toSet()
            }
            call.ok(roleDefaultsResponse(updateRoleDefaults(updates)))
        }
    }
}

/**
 * Every role, in enum declaration order, with `[]` for roles that have no defaults —
 * so the frontend never has to handle a missing key.
 */
private fun roleDefaultsResponse(defaults: Map<UserRole, Set<NotificationEvent>>) =
    NotificationRoleDefaultsResponse(
        UserRole.entries.map { role ->
            NotificationRoleDefaultEntry(
                role = role.name.lowercase(),
                events = defaults[role].orEmpty().map { it.key }.sorted(),
            )
        },
    )

private fun parseRoleOrFail(raw: String): UserRole =
    parseUserRole(raw) ?: throw DomainError.Validation("unknown role '$raw'")

private fun parseBulkSubscriptionMode(raw: String): BulkSubscriptionMode =
    runCatching { enumValueOf<BulkSubscriptionMode>(raw.trim().uppercase()) }.getOrNull()
        ?: throw DomainError.Validation("unknown mode '$raw' — expected add, remove or replace")
