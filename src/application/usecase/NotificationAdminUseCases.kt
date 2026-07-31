package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.BulkNotificationSubscriptionResult
import com.puregoldbe.ibms.domain.model.BulkSubscriptionMode
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.UserNotificationSubscriptionRow
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.model.UserStatus
import com.puregoldbe.ibms.domain.port.NotificationRoleDefaultsRepository
import com.puregoldbe.ibms.domain.port.NotificationSubscriptionRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.port.UserRepository

/**
 * Sysadmin administration of notification subscriptions: the org-wide matrix, bulk
 * writes, and the per-role defaults that seed newly provisioned users.
 *
 * The single-user read/write pair lives in [GetUserNotificationSubscriptionsUseCase] /
 * [UpdateUserNotificationSubscriptionsUseCase].
 */

/**
 * Live per-event recipient counts for the catalogue screen. An event missing from the
 * returned map has no deliverable subscriber at all, which means the notification is
 * being dropped rather than queued — the one thing the admin most needs to see.
 */
class CountDeliverableSubscribersUseCase(
    private val subscriptions: NotificationSubscriptionRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(): Map<NotificationEvent, Int> =
        tx.inTransaction { subscriptions.deliverableSubscriberCounts() }
}

/** One page of the org-wide "who receives what" matrix. */
class ListUserNotificationSubscriptionsUseCase(
    private val subscriptions: NotificationSubscriptionRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        role: UserRole?,
        status: UserStatus?,
        event: NotificationEvent?,
        deliverable: Boolean?,
        cursor: String?,
        limit: Int,
    ): CursorPage<UserNotificationSubscriptionRow> = tx.inTransaction {
        subscriptions.pageUserSubscriptions(role, status, event, deliverable, cursor, limit)
    }
}

/**
 * Apply one subscription change across many users, named explicitly and/or by role.
 *
 * All-or-nothing: an unknown user id aborts the whole transaction rather than leaving
 * a half-applied change, which is why the frontend must use this instead of looping
 * per-user PUTs.
 */
class BulkUpdateNotificationSubscriptionsUseCase(
    private val users: UserRepository,
    private val subscriptions: NotificationSubscriptionRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        mode: BulkSubscriptionMode,
        events: Set<NotificationEvent>,
        userIds: List<String>,
        roles: Set<UserRole>,
    ): BulkNotificationSubscriptionResult {
        if (userIds.isEmpty() && roles.isEmpty()) {
            throw DomainError.Validation("specify at least one userId or role")
        }
        // An empty event set is meaningful only for REPLACE, where it unsubscribes the
        // targets from everything; for ADD/REMOVE it is a silent no-op, so reject it.
        if (events.isEmpty() && mode != BulkSubscriptionMode.REPLACE) {
            throw DomainError.Validation("events is required for mode '${mode.name.lowercase()}'")
        }

        return tx.inTransaction {
            val named = userIds.distinct().map { id ->
                users.findById(id) ?: throw DomainError.NotFound("user $id not found")
            }
            // A role with no members contributes no targets, which is not an error.
            val byRole = roles.flatMap { users.list(it, null) }
            val targets = (named + byRole).distinctBy { it.id }

            val changed = subscriptions.applyForUsers(targets.map { it.id }, events, mode)
            BulkNotificationSubscriptionResult(
                mode = mode,
                events = events.map { it.key }.sorted(),
                usersMatched = targets.size,
                usersChanged = changed,
                undeliverableTargets = targets.count {
                    it.email.isNullOrBlank() || it.status != UserStatus.ACTIVE
                },
            )
        }
    }
}

/** Per-role defaults, as stored. Roles with no defaults are absent from the map. */
class GetNotificationRoleDefaultsUseCase(
    private val roleDefaults: NotificationRoleDefaultsRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(): Map<UserRole, Set<NotificationEvent>> =
        tx.inTransaction { roleDefaults.all() }
}

/**
 * Rewrite the defaults for the roles named in [updates], leaving other roles alone,
 * and return the full resulting map so the caller needs no follow-up read.
 *
 * Existing users are deliberately never touched — see the contract's "What defaults do
 * and do not do". A retrofit is an explicit [BulkUpdateNotificationSubscriptionsUseCase]
 * call with `roles`.
 */
class UpdateNotificationRoleDefaultsUseCase(
    private val roleDefaults: NotificationRoleDefaultsRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        updates: List<Pair<UserRole, Set<NotificationEvent>>>,
    ): Map<UserRole, Set<NotificationEvent>> {
        // Two entries for one role would make the result depend on apply order, so the
        // request is ambiguous rather than merely redundant.
        val seen = mutableSetOf<UserRole>()
        updates.forEach { (role, _) ->
            if (!seen.add(role)) {
                throw DomainError.Validation("duplicate role '${role.name.lowercase()}' in defaults")
            }
        }
        return tx.inTransaction {
            updates.forEach { (role, events) -> roleDefaults.setForRole(role, events) }
            roleDefaults.all()
        }
    }
}
