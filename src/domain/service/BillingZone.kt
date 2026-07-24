package com.puregoldbe.ibms.domain.service

import kotlinx.datetime.TimeZone

/**
 * The canonical business timezone for every billing date derivation.
 *
 * All `Instant -> LocalDate` conversions on the billing path — proration grace-end
 * ([ProrationEngine]), the arrears watermark, the future-period guard, and the
 * installation-date validation — MUST go through this zone so a single timestamp buckets
 * into the same calendar day/month everywhere. Philippine Standard Time is a stable UTC+8
 * with no DST, so calendar-day arithmetic here is unambiguous.
 *
 * Note: on the JVM the named zone resolves from the bundled tz database. If this constant is
 * ever shared to a KMP JS/Native target, either bundle tz data or switch to a fixed
 * `UtcOffset(hours = 8)` — the numeric result is identical for Manila.
 */
val BILLING_ZONE: TimeZone = TimeZone.of("Asia/Manila")
