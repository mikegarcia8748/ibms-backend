package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.Clock
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
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(): Int = tx.inTransaction {
        val now = clock.now()
        val cutoff = now.minus(GracePeriodPolicy.GRACE_DAYS, DateTimeUnit.DAY, TimeZone.UTC)
        val expired = accounts.findExpiredGrace(cutoff)
        expired.forEach { accounts.updateStatus(it.id, AccountStatus.INACTIVE) }
        expired.size
    }
}
