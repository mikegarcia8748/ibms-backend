package com.puregoldbe.ibms.domain

import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.service.InvoiceNumberFormatter
import com.puregoldbe.ibms.domain.service.ProrationEngine
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private fun account(
    rate: String,
    installationDate: LocalDate,
    status: AccountStatus = AccountStatus.ACTIVE,
    terminationRequestedAt: Instant? = null,
) = Account(
    id = "a1",
    accountNumber = "000",
    providerId = "p1",
    storeId = "s1",
    rate = rate,
    installationDate = installationDate,
    status = status,
    terminationRequestedAt = terminationRequestedAt,
    createdAt = Instant.fromEpochSeconds(0),
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

    Given("provider names") {
        Then("acronyms match the legacy getProviderAcronym") {
            InvoiceNumberFormatter.acronym("Converge") shouldBe "CONV"
            InvoiceNumberFormatter.acronym("Philippine Long Distance Telephone") shouldBe "PLDT"
            InvoiceNumberFormatter.format("CONV-", "2026-08", 7) shouldBe "CONV-202608-0007"
        }
    }
})
