package com.puregoldbe.ibms.domain

import com.puregoldbe.ibms.domain.model.DeepLinks
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * These strings are a published contract, not an implementation detail: they are what
 * `apicontracts/NOTIFICATION_DEEP_LINK_CONTRACT.md` promises the web client, and an
 * email already in someone's inbox cannot be re-rendered when a path changes. Pinning
 * them here is what makes a rename a deliberate, two-sided decision.
 */
class DeepLinksSpec : BehaviorSpec({

    Given("the entity paths") {
        Then("each is the shape the client routes on") {
            DeepLinks.store("store-1") shouldBe "/stores/store-1"
            DeepLinks.account("acc-1") shouldBe "/accounts/acc-1"
            DeepLinks.topsheet("ts-1") shouldBe "/topsheets/ts-1"
        }

        Then("the activity variant asks the account screen for its activity tab") {
            DeepLinks.accountActivity("acc-1") shouldBe "/accounts/acc-1?tab=activity"
        }

        // Nested under the account, mirroring the API's own
        // /accounts/{id}/change-requests/{requestId}.
        Then("a change request is addressed through its account") {
            DeepLinks.changeRequest("acc-1", "req-9") shouldBe "/accounts/acc-1/change-requests/req-9"
        }
    }

    Given("a web client base URL") {
        Then("joining yields exactly one slash, however the base was written") {
            DeepLinks.absolute("https://client.example", "/accounts/acc-1") shouldBe
                "https://client.example/accounts/acc-1"
            DeepLinks.absolute("https://client.example/", "/accounts/acc-1") shouldBe
                "https://client.example/accounts/acc-1"
        }

        // A client hosted under a sub-path is a normal static-hosting layout, and the
        // path has to survive it intact.
        Then("a base carrying a sub-path keeps it") {
            DeepLinks.absolute("https://client.example/app", "/topsheets/ts-1") shouldBe
                "https://client.example/app/topsheets/ts-1"
        }
    }
})
