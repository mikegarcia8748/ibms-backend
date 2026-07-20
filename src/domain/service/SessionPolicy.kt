package com.puregoldbe.ibms.domain.service

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The tunable numbers behind login and session lifetime, as a domain value rather
 * than a config type — this is what the use cases depend on, so they stay
 * unaware of environment variables. The composition root builds it from AppConfig.
 *
 * Defaults are the production posture: a temporary password is short-lived
 * because it travels out-of-band through a chat message or a phone call, while a
 * refresh token is long-lived but revocable at any time from the sessions table.
 */
data class SessionPolicy(
    val refreshTtl: Duration = 30.days,
    val temporaryPasswordTtl: Duration = 72.hours,
    val maxFailedLogins: Int = 5,
    val lockoutDuration: Duration = 15.minutes,
)
