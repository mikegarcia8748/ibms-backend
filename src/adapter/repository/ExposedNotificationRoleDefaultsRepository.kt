package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.NotificationRoleDefaults
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.port.NotificationRoleDefaultsRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Per-role default subscriptions, backed by `notification_role_defaults` (V21).
 *
 * Rows carrying an `event_type` no longer present in [NotificationEvent] are skipped
 * on read rather than failing — the column is free TEXT precisely so the event set can
 * change in code, and a retired event should not break the admin screen.
 */
class ExposedNotificationRoleDefaultsRepository : NotificationRoleDefaultsRepository {

    override fun all(): Map<UserRole, Set<NotificationEvent>> =
        NotificationRoleDefaults
            .selectAll()
            .mapNotNull { row ->
                NotificationEvent.fromKey(row[NotificationRoleDefaults.eventType])
                    ?.let { row[NotificationRoleDefaults.role] to it }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, events) -> events.toSet() }

    override fun forRole(role: UserRole): Set<NotificationEvent> =
        NotificationRoleDefaults
            .selectAll()
            .where { NotificationRoleDefaults.role eq role }
            .mapNotNull { NotificationEvent.fromKey(it[NotificationRoleDefaults.eventType]) }
            .toSet()

    override fun setForRole(role: UserRole, events: Set<NotificationEvent>) {
        NotificationRoleDefaults.deleteWhere { NotificationRoleDefaults.role eq role }
        events.forEach { event ->
            NotificationRoleDefaults.insert {
                it[NotificationRoleDefaults.role] = role
                it[NotificationRoleDefaults.eventType] = event.key
            }
        }
    }
}
