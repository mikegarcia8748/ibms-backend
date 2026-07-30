package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.UserNotificationSubscriptions
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.UserStatus
import com.puregoldbe.ibms.domain.port.NotificationSubscriptionRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Recipient resolution + per-user subscription editing, backed by
 * `user_notification_subscriptions` (V19).
 */
class ExposedNotificationSubscriptionRepository : NotificationSubscriptionRepository {

    override fun subscribersOf(event: NotificationEvent): List<String> =
        (UserNotificationSubscriptions innerJoin Users)
            .selectAll()
            .where {
                (UserNotificationSubscriptions.eventType eq event.key) and
                    (Users.status eq UserStatus.ACTIVE) and
                    Users.email.isNotNull()
            }
            .mapNotNull { it[Users.email] }
            .distinct()

    override fun getForUser(userId: String): Set<NotificationEvent> {
        val uuid = userId.toUuidOrNull() ?: return emptySet()
        return UserNotificationSubscriptions
            .selectAll()
            .where { UserNotificationSubscriptions.userId eq uuid }
            .mapNotNull { NotificationEvent.fromKey(it[UserNotificationSubscriptions.eventType]) }
            .toSet()
    }

    override fun setForUser(userId: String, events: Set<NotificationEvent>) {
        val uuid = userId.toUuid()
        UserNotificationSubscriptions.deleteWhere { UserNotificationSubscriptions.userId eq uuid }
        events.forEach { event ->
            UserNotificationSubscriptions.insert {
                it[UserNotificationSubscriptions.userId] = EntityID(uuid, Users)
                it[UserNotificationSubscriptions.eventType] = event.key
            }
        }
    }
}
