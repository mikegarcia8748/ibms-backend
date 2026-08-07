package com.puregoldbe.ibms.domain

import com.puregoldbe.ibms.domain.service.InvoiceNumberFormatter
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The two invoice references a TopSheet carries are different things, and conflating
 * them is what put the same batch number on every row of the report's INVOICE NUMBER
 * column. [InvoiceNumberFormatter.format] mints the per-topsheet batch reference;
 * [InvoiceNumberFormatter.forAccount] mints the per-row one that Finance reconciles
 * against, naming the account and the rental period it covers.
 */
class InvoiceNumberFormatterSpec : BehaviorSpec({

    Given("an account number and the rental period it is being billed for") {
        When("building the per-account invoice reference") {
            Then("it is the account number followed by the period as MONYYYY, no separator") {
                InvoiceNumberFormatter.forAccount("0821234567", "2026-07") shouldBe "0821234567JUL2026"
            }
        }

        When("the period is January or December — the month-index boundaries") {
            Then("both abbreviate correctly, so no off-by-one creeps into the column") {
                InvoiceNumberFormatter.forAccount("ACC-1", "2026-01") shouldBe "ACC-1JAN2026"
                InvoiceNumberFormatter.forAccount("ACC-1", "2026-12") shouldBe "ACC-1DEC2026"
            }
        }

        When("two accounts appear on the same topsheet") {
            Then("each row gets its own value — the defect was one shared value on every row") {
                val first = InvoiceNumberFormatter.forAccount("ACC-1", "2026-07")
                val second = InvoiceNumberFormatter.forAccount("ACC-2", "2026-07")
                (first == second) shouldBe false
            }
        }

        When("the account number carries surrounding whitespace") {
            Then("it is trimmed, so the reference never contains a stray space") {
                InvoiceNumberFormatter.forAccount("  ACC-1  ", "2026-07") shouldBe "ACC-1JUL2026"
            }
        }
    }

    Given("a line with no account number") {
        When("building the per-account invoice reference") {
            Then("the cell is left empty rather than showing a bare period") {
                InvoiceNumberFormatter.forAccount(null, "2026-07") shouldBe ""
                InvoiceNumberFormatter.forAccount("   ", "2026-07") shouldBe ""
            }
        }
    }

    Given("a billing period that is not YYYY-MM") {
        When("building the per-account invoice reference") {
            Then("it degrades to the account number alone instead of emitting a malformed reference") {
                InvoiceNumberFormatter.forAccount("ACC-1", "") shouldBe "ACC-1"
                InvoiceNumberFormatter.forAccount("ACC-1", "July 2026") shouldBe "ACC-1"
                InvoiceNumberFormatter.forAccount("ACC-1", "2026-13") shouldBe "ACC-1"
            }
        }
    }

    Given("the per-topsheet batch reference") {
        When("comparing it with the per-account one for the same topsheet") {
            Then("they are distinct — the batch number belongs in the meta block, not the column") {
                val batch = InvoiceNumberFormatter.format(InvoiceNumberFormatter.prefix("Converge"), "2026-07", 1)
                batch shouldBe "CONV-202607-0001"
                InvoiceNumberFormatter.forAccount("0821234567", "2026-07") shouldBe "0821234567JUL2026"
            }
        }
    }
})
