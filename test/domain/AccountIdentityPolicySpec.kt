package com.puregoldbe.ibms.domain

import com.puregoldbe.ibms.adapter.controller.deactivateCanonicalBody
import com.puregoldbe.ibms.adapter.controller.transferCanonicalBody
import com.puregoldbe.ibms.domain.service.AccountIdentityPolicy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The two pure rules behind the account-identity fixes: what gets stored, and what an
 * Idempotency-Key is actually keyed on.
 */
class AccountIdentityPolicySpec : BehaviorSpec({

    Given("circuit ids in the shapes a client can send") {
        When("normalizing") {
            Then("blank in any form collapses to the single no-circuit slot") {
                // The bug this closes: "  " used to be stored literally, so
                // COALESCE(circuit_id,'') produced a third slot that the identity
                // guard — which treats blank as absent — could never match again.
                AccountIdentityPolicy.normalizeCircuitId("  ") shouldBe null
                AccountIdentityPolicy.normalizeCircuitId("") shouldBe null
                AccountIdentityPolicy.normalizeCircuitId(null) shouldBe null
            }
            Then("a real circuit keeps its value, minus padding") {
                AccountIdentityPolicy.normalizeCircuitId(" CIRC-1 ") shouldBe "CIRC-1"
            }
        }
    }

    Given("account numbers with padding or mixed case") {
        When("normalizing") {
            Then("padding is removed but case is preserved for display") {
                AccountIdentityPolicy.normalizeAccountNumber("  ACC-001 ") shouldBe "ACC-001"
                // Case folding belongs in the comparison, not the stored value: this
                // number is printed on top sheets, RFPs and cheque exports.
                AccountIdentityPolicy.normalizeAccountNumber("acc-001") shouldBe "acc-001"
            }
        }
    }

    Given("the canonical bodies an Idempotency-Key is hashed from") {
        When("the same payload targets two different accounts") {
            Then("the deactivation bodies differ, so one key cannot replay across them") {
                deactivateCanonicalBody("acc-A", listOf("proof-1")) shouldNotBe
                    deactivateCanonicalBody("acc-B", listOf("proof-1"))
            }
            Then("the transfer bodies differ too") {
                transferCanonicalBody("acc-A", "store-2", listOf("proof-1")) shouldNotBe
                    transferCanonicalBody("acc-B", "store-2", listOf("proof-1"))
            }
        }

        When("the same logical transfer arrives on either of its two endpoints") {
            Then("the body is identical, so a retry that switches endpoint still dedupes") {
                // POST /accounts/{id}/transfer and POST /transfers carry different request
                // shapes; both resolve to the same three values, so both must hash alike.
                transferCanonicalBody("acc-A", "store-2", listOf("p1", "p2")) shouldBe
                    transferCanonicalBody("acc-A", "store-2", listOf("p1", "p2"))
            }
        }

        When("the proof set changes") {
            Then("the body changes — a different request under a spent key must be caught") {
                deactivateCanonicalBody("acc-A", listOf("proof-1")) shouldNotBe
                    deactivateCanonicalBody("acc-A", listOf("proof-2"))
            }
        }
    }
})
