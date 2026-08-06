package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.DeepLinks
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.service.GracePeriodPolicy
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

/**
 * Flips accounts whose 30-day termination grace has elapsed from
 * `termination_requested` to `inactive`. Runs on a daily schedule (and can be
 * triggered manually by a sysadmin), replacing the lazy client-side update that
 * used to happen in the React `loadData`.
 *
 * @return the number of accounts expired.
 */
class ExpireGracePeriodAccountsUseCase(
    private val accounts: AccountRepository,
    private val notifications: NotificationEnqueuer,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(): Int = tx.inTransaction {
        val now = clock.now()
        val cutoff = now.minus(GracePeriodPolicy.GRACE_DAYS, DateTimeUnit.DAY, TimeZone.UTC)
        val expired = accounts.findExpiredGrace(cutoff)
        expired.forEach { account ->
            accounts.updateStatus(account.id, AccountStatus.INACTIVE)
            notifications.enqueue(
                NotificationEvent.ACCOUNT_TERMINATED,
                NotificationContext(
                    headline = "Account ${account.accountNumber} terminated (30-day grace period elapsed)",
                    details = listOf("Account number" to account.accountNumber),
                    entityId = account.id,
                    // No actorId: the grace-expiry job has no acting user, so the email
                    // renders no "Performed by" line, which is the honest answer.
                    linkPath = DeepLinks.account(account.id),
                ),
            )
        }
        expired.size
    }
}
