package com.puregoldbe.ibms.domain.service

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/**
 * The 30-day termination grace window. Same rule the ProrationEngine uses: an
 * account keeps billing until 30 days after its termination request, then expires
 * to inactive. Kept as a pure policy so the scheduled job is unit-testable.
 */
object GracePeriodPolicy {
    const val GRACE_DAYS = 30
    private val GRACE = DateTimeUnit.DayBased(GRACE_DAYS)

    fun graceEnd(terminationRequestedAt: Instant): Instant = terminationRequestedAt.plus(GRACE, TimeZone.UTC)

    fun hasExpired(terminationRequestedAt: Instant, now: Instant): Boolean =
        now >= graceEnd(terminationRequestedAt)
}
