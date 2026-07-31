package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.UserNotificationSubscriptions
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.keysetAfter
import com.puregoldbe.ibms.adapter.db.keysetAnchor
import com.puregoldbe.ibms.adapter.db.toCursorPage
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.BulkSubscriptionMode
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.UserNotificationSubscriptionRow
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.model.UserStatus
import com.puregoldbe.ibms.domain.port.NotificationSubscriptionRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

/**
 * Recipient resolution + subscription editing, backed by
 * `user_notification_subscriptions` (V20).
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
        insertRows(uuid, events)
    }

    override fun pageUserSubscriptions(
        role: UserRole?,
        status: UserStatus?,
        event: NotificationEvent?,
        deliverable: Boolean?,
        cursor: String?,
        limit: Int,
    ): CursorPage<UserNotificationSubscriptionRow> {
        // The event and "misconfigured" filters restrict the page to users that have
        // subscription rows, so they must narrow the *user* query — otherwise the keyset
        // window would be computed over the wrong set and pages would skip rows. The
        // candidate id lists are small (subscribers, not accounts), so resolving them up
        // front is cheaper than a correlated subquery and keeps the query plain Exposed.
        val restrictTo: List<UUID>? = when {
            event != null -> userIdsSubscribedTo(event)
            deliverable == false -> userIdsWithAnySubscription()
            else -> null
        }
        // An empty restriction means nothing can match; `inList emptyList()` is a trap.
        if (restrictTo != null && restrictTo.isEmpty()) return CursorPage(emptyList(), null)

        val anchor = Users.keysetAnchor(Users.createdAt, cursor)
        val rows = Users.selectAll()
            .apply { if (role != null) andWhere { Users.role eq role } }
            .apply { if (status != null) andWhere { Users.status eq status } }
            .apply { if (restrictTo != null) andWhere { Users.id inList restrictTo.map { EntityID(it, Users) } } }
            .apply {
                when (deliverable) {
                    true -> andWhere { (Users.status eq UserStatus.ACTIVE) and Users.email.isNotNull() }
                    false -> andWhere { (Users.status neq UserStatus.ACTIVE) or Users.email.isNull() }
                    null -> Unit
                }
            }
            .apply { if (anchor != null) andWhere { keysetAfter(Users, Users.createdAt, anchor) } }
            .orderBy(Users.createdAt to SortOrder.ASC, Users.id to SortOrder.ASC)
            .limit(limit + 1)
            .map {
                UserRow(
                    id = it[Users.id].value,
                    username = it[Users.username],
                    name = it[Users.name],
                    email = it[Users.email],
                    role = it[Users.role],
                    status = it[Users.status],
                )
            }

        // One grouped read for the whole page rather than a query per user.
        val subsByUser = subscriptionsFor(rows.map { it.id })
        return rows
            .map { u ->
                UserNotificationSubscriptionRow.of(
                    userId = u.id.toString(),
                    username = u.username,
                    name = u.name,
                    email = u.email,
                    role = u.role,
                    status = u.status,
                    subscribed = subsByUser[u.id].orEmpty(),
                )
            }
            .toCursorPage(limit) { it.userId }
    }

    override fun deliverableSubscriberCounts(): Map<NotificationEvent, Int> =
        // Counted the same way subscribersOf resolves recipients — distinct *emails* of
        // active users — so a shared mailbox counts once and the number the admin screen
        // shows is the number of messages that would actually be addressed.
        (UserNotificationSubscriptions innerJoin Users)
            .selectAll()
            .where { (Users.status eq UserStatus.ACTIVE) and Users.email.isNotNull() }
            .mapNotNull { row ->
                val event = NotificationEvent.fromKey(row[UserNotificationSubscriptions.eventType])
                val email = row[Users.email]
                if (event != null && email != null) event to email else null
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, emails) -> emails.distinct().size }

    override fun applyForUsers(
        userIds: List<String>,
        events: Set<NotificationEvent>,
        mode: BulkSubscriptionMode,
    ): Int {
        val uuids = userIds.map { it.toUuid() }.distinct()
        if (uuids.isEmpty()) return 0

        val current = subscriptionsFor(uuids)
        var changed = 0
        uuids.forEach { uuid ->
            val existing = current[uuid].orEmpty()
            val target = when (mode) {
                BulkSubscriptionMode.ADD -> existing + events
                BulkSubscriptionMode.REMOVE -> existing - events
                BulkSubscriptionMode.REPLACE -> events
            }
            if (target == existing) return@forEach
            changed++
            // Diff rather than delete-then-reinsert, so created_at survives on rows the
            // write leaves alone and the audit trail stays honest.
            val removed = existing - target
            if (removed.isNotEmpty()) {
                UserNotificationSubscriptions.deleteWhere {
                    (UserNotificationSubscriptions.userId eq uuid) and
                        (UserNotificationSubscriptions.eventType inList removed.map { it.key })
                }
            }
            insertRows(uuid, target - existing)
        }
        return changed
    }

    private fun insertRows(uuid: UUID, events: Set<NotificationEvent>) {
        events.forEach { event ->
            UserNotificationSubscriptions.insert {
                it[UserNotificationSubscriptions.userId] = EntityID(uuid, Users)
                it[UserNotificationSubscriptions.eventType] = event.key
            }
        }
    }

    private fun userIdsSubscribedTo(event: NotificationEvent): List<UUID> =
        UserNotificationSubscriptions
            .selectAll()
            .where { UserNotificationSubscriptions.eventType eq event.key }
            .map { it[UserNotificationSubscriptions.userId].value }
            .distinct()

    private fun userIdsWithAnySubscription(): List<UUID> =
        UserNotificationSubscriptions
            .selectAll()
            .map { it[UserNotificationSubscriptions.userId].value }
            .distinct()

    private fun subscriptionsFor(uuids: List<UUID>): Map<UUID, Set<NotificationEvent>> {
        if (uuids.isEmpty()) return emptyMap()
        return UserNotificationSubscriptions
            .selectAll()
            .where { UserNotificationSubscriptions.userId inList uuids.map { EntityID(it, Users) } }
            .mapNotNull { row ->
                NotificationEvent.fromKey(row[UserNotificationSubscriptions.eventType])
                    ?.let { row[UserNotificationSubscriptions.userId].value to it }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, events) -> events.toSet() }
    }

    /** The user columns the matrix needs, read before subscriptions are joined in. */
    private data class UserRow(
        val id: UUID,
        val username: String,
        val name: String,
        val email: String?,
        val role: UserRole,
        val status: UserStatus,
    )
}
