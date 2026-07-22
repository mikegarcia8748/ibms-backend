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

    Given("a period before the account's subscription starts") {
        val acc = account(rate = "1000", installationDate = LocalDate(2026, 9, 5))
        Then("it is flagged not-yet-subscribed and never eligible") {
            ProrationEngine.isNotYetSubscribed(acc, "2026-08") shouldBe true
            ProrationEngine.isEligible(acc, "p1", "2026-08", emptySet()) shouldBe false
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
