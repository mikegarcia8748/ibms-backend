package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.port.NotificationSubscriptionRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.port.UserRepository

/** Read a user's current notification-event subscriptions (sysadmin config screen). */
class GetUserNotificationSubscriptionsUseCase(
    private val users: UserRepository,
    private val subscriptions: NotificationSubscriptionRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(userId: String): Set<NotificationEvent> = tx.inTransaction {
        users.findById(userId) ?: throw DomainError.NotFound("user $userId not found")
        subscriptions.getForUser(userId)
    }
}

/** Replace a user's notification-event subscriptions wholesale, returning the new set. */
class UpdateUserNotificationSubscriptionsUseCase(
    private val users: UserRepository,
    private val subscriptions: NotificationSubscriptionRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(userId: String, events: Set<NotificationEvent>): Set<NotificationEvent> =
        tx.inTransaction {
            users.findById(userId) ?: throw DomainError.NotFound("user $userId not found")
            subscriptions.setForUser(userId, events)
            subscriptions.getForUser(userId)
        }
}
