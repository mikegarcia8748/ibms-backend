package com.puregoldbe.ibms.domain

import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.service.InvoiceNumberFormatter
import com.puregoldbe.ibms.domain.service.ProrationEngine
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private fun account(
    rate: String,
    installationDate: LocalDate,
    status: AccountStatus = AccountStatus.ACTIVE,
    terminationRequestedAt: Instant? = null,
    contractStartDate: LocalDate? = null,
    createdAt: Instant = Instant.fromEpochSeconds(0),
) = Account(
    id = "a1",
    accountNumber = "000",
    providerId = "p1",
    storeId = "s1",
    rate = rate,
    installationDate = installationDate,
    contractStartDate = contractStartDate,
    status = status,
    terminationRequestedAt = terminationRequestedAt,
    createdAt = createdAt,
)

/** Golden billing math — the authoritative proration, verified against production values. */
class ProrationEngineSpec : BehaviorSpec({

    Given("an account installed mid-period") {
        // rate 1000, installed 2026-08-20, billing 2026-08: 12 active days of 31.
        val acc = account(rate = "1000", installationDate = LocalDate(2026, 8, 20))
        Then("the prorated amount is 387.10") {
            ProrationEngine.proratedAmount(acc, "2026-08") shouldBe "387.10"
        }
    }

    Given("an account active for the whole month") {
        val acc = account(rate = "1000", installationDate = LocalDate(2026, 7, 1))
        Then("it is billed the full MRC") {
            ProrationEngine.proratedAmount(acc, "2026-08") shouldBe "1000.00"
        }
    }

    Given("an account installed after the billing period") {
        val acc = account(rate = "1000", installationDate = LocalDate(2026, 9, 5))
        Then("it is not billed") {
            ProrationEngine.proratedAmount(acc, "2026-08") shouldBe "0.00"
        }
    }

    Given("an account whose 30-day grace ends before the period") {
        // termination requested 2026-06-01 -> grace ends 2026-07-01, before 2026-08.
        val acc = account(
            rate = "1000",
            installationDate = LocalDate(2025, 1, 1),
            status = AccountStatus.TERMINATION_REQUESTED,
            terminationRequestedAt = Instant.parse("2026-06-01T00:00:00Z"),
        )
        Then("it is not billed") {
            ProrationEngine.proratedAmount(acc, "2026-08") shouldBe "0.00"
        }
    }

    Given("an account terminated mid-period") {
        // termination requested 2026-07-25 -> grace ends 2026-08-24: 24 active days of 31.
        val acc = account(
            rate = "1000",
            installationDate = LocalDate(2025, 1, 1),
            status = AccountStatus.TERMINATION_REQUESTED,
            terminationRequestedAt = Instant.parse("2026-07-25T00:00:00Z"),
        )
        Then("it is prorated up to the grace end day (774.19)") {
            ProrationEngine.proratedAmount(acc, "2026-08") shouldBe "774.19"
        }
    }

    Given("an account whose contractStartDate differs from its installationDate") {
        // Anchor is contractStartDate (Aug 20), not installationDate (Aug 1).
        val acc = account(
            rate = "1000",
            installationDate = LocalDate(2026, 8, 1),
            contractStartDate = LocalDate(2026, 8, 20),
        )
        Then("proration anchors on contractStartDate (12 active days of 31 = 387.10)") {
            ProrationEngine.proratedAmount(acc, "2026-08") shouldBe "387.10"
        }
    }

    Given("an account subscribed two months before an un-billed billing run") {
        // contract starts 2026-05-10, entered the system 2026-05, nothing billed yet.
        val acc = account(
            rate = "1000",
            installationDate = LocalDate(2026, 5, 10),
            createdAt = Instant.parse("2026-05-10T00:00:00Z"),
        )
        Then("missedPeriods lists May and June, arrears sums their prorations") {
            ProrationEngine.missedPeriods(acc, "2026-07", emptySet()) shouldBe listOf("2026-05", "2026-06")
            // May: 22/31 days = 709.68 ; June: full 1000.00
            ProrationEngine.arrearsAmount(acc, "2026-07", emptySet()) shouldBe "1709.68"
        }
        Then("a period already settled is excluded from arrears") {
            ProrationEngine.missedPeriods(acc, "2026-07", setOf("2026-05")) shouldBe listOf("2026-06")
        }
    }

    Given("an account migrated with an old subscription date but no in-system history") {
        // subscription/install back in 2020, but it only entered THIS system in 2026-07.
        val acc = account(
            rate = "1000",
            installationDate = LocalDate(2020, 1, 1),
            createdAt = Instant.parse("2026-07-01T00:00:00Z"),
        )
        Then("the watermark bounds arrears to system-entry, so nothing is retro-billed") {
            ProrationEngine.missedPeriods(acc, "2026-07", emptySet()).shouldBeEmpty()
            ProrationEngine.arrearsAmount(acc, "2026-07", emptySet()) shouldBe "0.00"
        }
    }

    // --- Timezone boundary: every Instant is bucketed in Asia/Manila (UTC+8), not UTC. ---

    Given("a termination timestamp late in the UTC day of a month boundary") {
        // 2026-08-15T20:00Z is 2026-08-16 04:00 in Asia/Manila, so grace ends 2026-09-15,
        // not the UTC 2026-09-14. Billing 2026-09 (30 days) -> 15 active days = exactly half.
        val acc = account(
            rate = "1000",
            installationDate = LocalDate(2025, 1, 1),
            status = AccountStatus.TERMINATION_REQUESTED,
            terminationRequestedAt = Instant.parse("2026-08-15T20:00:00Z"),
        )
        Then("grace-end is bucketed in Manila (15/30 = 500.00, not the UTC 466.67)") {
            ProrationEngine.proratedAmount(acc, "2026-09") shouldBe "500.00"
        }
    }

    Given("a createdAt watermark late in the UTC day of a month boundary") {
        // 2026-06-30T20:00Z is 2026-07-01 04:00 in Asia/Manila, so the account entered THIS
        // system in period 2026-07 (not the UTC 2026-06). Its subscription (May) predates that.
        val acc = account(
            rate = "1000",
            installationDate = LocalDate(2026, 5, 10),
            createdAt = Instant.parse("2026-06-30T20:00:00Z"),
        )
        Then("the watermark is 2026-07 in Manila, so no prior period is retro-billed") {
            ProrationEngine.missedPeriods(acc, "2026-07", emptySet()).shouldBeEmpty()
            ProrationEngine.arrearsAmount(acc, "2026-07", emptySet()) shouldBe "0.00"
        }
    }

    Given("a period before the account's subscription starts") {
        val acc = account(rate = "1000", installationDate = LocalDate(2026, 9, 5))
        Then("it is flagged not-yet-subscribed and never eligible") {
            ProrationEngine.isNotYetSubscribed(acc, "2026-08") shouldBe true
            ProrationEngine.isEligible(acc, "p1", "2026-08", emptySet()) shouldBe false
        }
    }

    // --- Calendar boundaries: leap February, single active day, year rollover, rounding tie. ---

    Given("an account installed mid-way through a leap-year February") {
        // 2028 is a leap year -> Feb has 29 days. Installed the 15th: 15 active days of 29.
        val acc = account(rate = "1000", installationDate = LocalDate(2028, 2, 15))
        Then("proration uses 29 days (15/29 = 517.24)") {
            ProrationEngine.proratedAmount(acc, "2028-02") shouldBe "517.24"
        }
    }

    Given("an account installed on the last day of a 31-day month") {
        // Installed 2026-08-31 -> exactly 1 active day of 31.
        val acc = account(rate = "1000", installationDate = LocalDate(2026, 8, 31))
        Then("a single active day is billed (1/31 = 32.26)") {
            ProrationEngine.proratedAmount(acc, "2026-08") shouldBe "32.26"
        }
    }

    Given("arrears spanning a December -> January year boundary") {
        // Subscribed and entered the system 2025-11-15; billing 2026-01, nothing settled.
        val acc = account(
            rate = "1000",
            installationDate = LocalDate(2025, 11, 15),
            createdAt = Instant.parse("2025-11-15T00:00:00Z"),
        )
        Then("missedPeriods rolls Nov -> Dec -> stops at Jan; arrears sum both") {
            ProrationEngine.missedPeriods(acc, "2026-01", emptySet()) shouldBe listOf("2025-11", "2025-12")
            // Nov: 16/30 = 533.33 ; Dec: full 1000.00
            ProrationEngine.arrearsAmount(acc, "2026-01", emptySet()) shouldBe "1533.33"
        }
    }

    Given("a proration that lands exactly on a half-centavo") {
        // rate 1000.05, installed 2026-09-16 -> 15 active days of 30 = 500.025 exactly.
        val acc = account(rate = "1000.05", installationDate = LocalDate(2026, 9, 16))
        Then("it rounds half-up (500.025 -> 500.03, not 500.02)") {
            ProrationEngine.proratedAmount(acc, "2026-09") shouldBe "500.03"
        }
    }

    Given("an eligible account whose grace ended before its own subscription start (dirty data)") {
        // contract starts the 25th, but termination grace ended the 10th of the same month:
        // 0 active days. The account is still 'eligible', so it would mint a 0.00 line unless
        // the compile classifier filters it (C2).
        val acc = account(
            rate = "1000",
            installationDate = LocalDate(2020, 1, 1),
            status = AccountStatus.TERMINATION_REQUESTED,
            terminationRequestedAt = Instant.parse("2026-07-11T00:00:00Z"), // +30d -> 2026-08-10
            contractStartDate = LocalDate(2026, 8, 25),
        )
        Then("it prorates to 0.00 yet reports eligible — the case the zero-charge filter drops") {
            ProrationEngine.proratedAmount(acc, "2026-08") shouldBe "0.00"
            ProrationEngine.isEligible(acc, "p1", "2026-08", emptySet()) shouldBe true
        }
    }

    Given("provider names") {
        Then("acronyms match the legacy getProviderAcronym") {
            InvoiceNumberFormatter.acronym("Converge") shouldBe "CONV"
            InvoiceNumberFormatter.acronym("Philippine Long Distance Telephone") shouldBe "PLDT"
            InvoiceNumberFormatter.format("CONV-", "2026-08", 7) shouldBe "CONV-202608-0007"
        }
    }
})
