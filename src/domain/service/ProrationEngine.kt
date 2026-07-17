package com.puregoldbe.ibms.domain.service

import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import kotlinx.datetime.*
import kotlin.math.max

/**
 * Billing / proration engine — a faithful Kotlin port of the logic currently
 * living in SecretaryDashboard.tsx (`getProratedAmount` and the
 * `accountsToProcess` eligibility filter).
 *
 * It lives in the KMP `shared` module so it can run in two places with identical
 * results:
 *   - Backend: the authoritative computation during POST /topsheets/compile.
 *   - Frontend: instant preview in the Compose "Compile TopSheet" screen.
 *
 * IMPORTANT correctness note vs. the original:
 *   The React version relied on `new Date("8/20/2026")` and `.getTime()` which is
 *   locale/timezone-sensitive and a known source of off-by-one bugs. This port
 *   parses calendar dates explicitly (no timezone), and does money math on a
 *   scaled integer to avoid float drift. The 30-day grace-period semantics are
 *   preserved exactly.
 *
 * Money is passed/returned as a 2dp decimal String (see `Money` typealias). The
 * backend should convert to BigDecimal at the boundary; this pure module keeps a
 * tiny self-contained fixed-point helper so it compiles on all KMP targets.
 */
object ProrationEngine {

    private const val GRACE_DAYS = 30
    private val GRACE = DateTimeUnit.DayBased(GRACE_DAYS)

    /**
     * Prorated MRC for [account] within [billingPeriod] ("YYYY-MM").
     *
     * Rules (unchanged from production):
     *  - Full [Account.rate] when active for the whole month.
     *  - If installed mid-[billingPeriod], bill from the install day to month end.
     *  - Installed after the period  -> 0 (not yet active).
     *  - Termination end day = terminationRequestedAt + 30 days; if that lands
     *    inside the period, bill up to that day; if before the period -> 0.
     */
    fun proratedAmount(account: Account, billingPeriod: String): String {
        val (year, month) = parsePeriod(billingPeriod)
        val daysInMonth = daysInMonth(year, month)
        var startDay = 1
        var endDay = daysInMonth

        val install = parseFlexibleDate(account.installationDate.toString())
        if (install != null) {
            val installPeriod = periodOf(install)
            when {
                installPeriod == billingPeriod -> startDay = install.dayOfMonth
                installPeriod > billingPeriod  -> return "0.00"   // not installed yet
            }
        }

        val termAt = account.terminationRequestedAt
        if (termAt != null) {
            val termDate = termAt.plus(GRACE, TimeZone.UTC).toLocalDateTime(TimeZone.UTC).date
            val termPeriod = periodOf(termDate)
            when {
                termPeriod == billingPeriod -> endDay = termDate.dayOfMonth
                termPeriod < billingPeriod  -> return "0.00"      // terminated before this period
            }
        }

        if (startDay == 1 && endDay == daysInMonth) return account.rate.normalized2dp()

        val activeDays = max(0, endDay - startDay + 1)
        // amount = rate / daysInMonth * activeDays, rounded to 2dp (half-up)
        return divideRound2(mul(account.rate.toScaled2(), activeDays.toLong()), daysInMonth.toLong())
    }

    /**
     * Whether [account] should appear in a compilation for [providerId]/[billingPeriod],
     * given the set of accountIds already billed in that period.
     * Mirrors the `accountsToProcess.filter(...)` predicate.
     */
    fun isEligible(
        account: Account,
        providerId: String,
        billingPeriod: String,
        alreadyBilledAccountIds: Set<String>,
    ): Boolean {
        if (account.status == AccountStatus.TRANSFERRED) return false
        if (account.providerId != providerId) return false

        val install = parseFlexibleDate(account.installationDate.toString())
        val installPeriod = install?.let { periodOf(it) } ?: currentPeriod()
        if (installPeriod > billingPeriod) return false            // not installed yet

        val termAt = account.terminationRequestedAt
        if (termAt != null) {
            val termDate = termAt.plus(GRACE, TimeZone.UTC).toLocalDateTime(TimeZone.UTC).date
            if (periodOf(termDate) < billingPeriod) return false   // fully terminated before period
        } else if (account.status == AccountStatus.INACTIVE || account.status == AccountStatus.TERMINATED) {
            return false
        }

        return account.id !in alreadyBilledAccountIds
    }

    /** True if the account's install month equals the billing period (prorated first bill). */
    fun isFirstBillProrated(account: Account, billingPeriod: String): Boolean {
        val install = parseFlexibleDate(account.installationDate.toString()) ?: return false
        return periodOf(install) == billingPeriod
    }

    // ----------------------------------------------------------------
    //  Date helpers
    // ----------------------------------------------------------------
    private fun parsePeriod(period: String): Pair<Int, Int> {
        val parts = period.split("-")
        return parts[0].toInt() to parts[1].toInt()
    }

    private fun periodOf(d: LocalDate): String =
        "${d.year}-${d.monthNumber.toString().padStart(2, '0')}"

    private fun currentPeriod(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return periodOf(now)
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        val start = LocalDate(year, month, 1)
        val next = start.plus(1, DateTimeUnit.MONTH)
        return start.daysUntil(next)
    }

    /** Accepts ISO "yyyy-MM-dd" and legacy "M/d/yyyy" found in the current data. */
    fun parseFlexibleDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { epochMs ->
            return Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC).date
        }
        return runCatching { LocalDate.parse(raw) }.getOrNull() ?: run {
            val m = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""").find(raw.trim()) ?: return null
            val (mm, dd, yyyy) = m.destructured
            runCatching { LocalDate(yyyy.toInt(), mm.toInt(), dd.toInt()) }.getOrNull()
        }
    }

    // ----------------------------------------------------------------
    //  Fixed-point money (2dp) helpers — avoids Double in billing math.
    //  Backend can ignore these and use BigDecimal; results are identical.
    // ----------------------------------------------------------------
    private fun String.toScaled2(): Long {
        val cleaned = trim().ifBlank { "0" }
        val neg = cleaned.startsWith("-")
        val body = cleaned.removePrefix("-")
        val dot = body.indexOf('.')
        val whole = if (dot < 0) body else body.substring(0, dot)
        val fracRaw = if (dot < 0) "" else body.substring(dot + 1)
        val frac = (fracRaw + "00").substring(0, 2)
        val value = whole.toLong() * 100 + frac.toLong()
        return if (neg) -value else value
    }

    private fun Long.from2dp(): String = "${this / 100}.${(this % 100).toString().padStart(2, '0')}"

    private fun String.normalized2dp(): String = toScaled2().from2dp()

    private fun mul(scaled: Long, factor: Long): Long = scaled * factor

    /** (scaledAmount / divisor) but keeping the 2dp scale, half-up rounded. */
    private fun divideRound2(scaledTimesDays: Long, divisor: Long): String {
        // scaledTimesDays is in centavos*days; divide by days -> centavos, round half-up
        val q = scaledTimesDays / divisor
        val r = scaledTimesDays % divisor
        val rounded = if (r * 2 >= divisor) q + 1 else q
        return rounded.from2dp()
    }
}
