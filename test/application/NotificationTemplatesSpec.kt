package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.NotificationTemplates
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.valueobject.Money
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

/**
 * The rendered email itself — the branded shell, the two-per-row detail grid, the
 * per-event button and the escaping. Everything else in the suite treats the body as
 * `any()`, so this is the only place a broken template would surface.
 */
class NotificationTemplatesSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val appUrl = "https://ibms.example"

    Given("a compiled top sheet with the full five details") {
        val ctx = NotificationContext(
            headline = "Topsheet compiled: invoice GLOB-202604-0005",
            details = listOf(
                "Invoice" to "GLOB-202604-0005",
                "Provider" to "Globe",
                "Billing Period" to "2026-04",
                "Total Accounts" to "214",
                "Total Amount" to Money.display("555335.00"),
            ),
            entityId = "ts-1",
            linkPath = "/topsheets/ts-1",
        )

        When("rendering") {
            val rendered = NotificationTemplates.render(NotificationEvent.TOPSHEET_COMPILED, ctx, appUrl)

            Then("the subject keeps the [IBMS] + event label contract other specs pin") {
                rendered.subject shouldBe "[IBMS] Topsheet compiled"
            }

            Then("it is a complete document in the IBMS palette, not a bare fragment") {
                rendered.html shouldStartWith "<!DOCTYPE html>"
                rendered.html shouldContain "<title>Topsheet compiled</title>"
                rendered.html shouldContain "background-color:#00473E"
                rendered.html shouldContain "background-color:#E2F4EE"
                rendered.html shouldContain "ISP Billing Management System"
            }

            Then("the headline leads the body and doubles as the inbox preview line") {
                rendered.html shouldContain "display:none;max-height:0"
                rendered.html shouldContain "Topsheet compiled: invoice GLOB-202604-0005"
            }

            Then("the five details pair up two per row, the odd one spanning both columns") {
                rendered.html shouldContain "Total Amount"
                // 2 + 2 + 1: only the trailing lone cell spans.
                rendered.html.split("colspan=\"2\"").size - 1 shouldBe 1
            }

            Then("the amount is emphasised in the brand colour because it is money") {
                rendered.html shouldContain "₱555,335.00"
                rendered.html shouldContain "font-weight:700;color:#00473E"
            }

            Then("the button names the entity and points at the deep link") {
                rendered.html shouldContain "https://ibms.example/topsheets/ts-1"
                rendered.html shouldContain "View Topsheet in IBMS &rarr;"
            }

            Then("the footer sends people to an administrator rather than a dead preferences link") {
                rendered.html shouldContain "Contact your system administrator."
                rendered.html shouldNotContain "manage notification preferences"
            }

            Then("the plain-text alternative carries the same facts") {
                rendered.text shouldContain "Total Amount: ₱555,335.00"
                rendered.text shouldContain "View Topsheet in IBMS: https://ibms.example/topsheets/ts-1"
            }
        }
    }

    Given("an event carrying a single detail") {
        val ctx = NotificationContext(
            headline = "Account 0917-000123 terminated (30-day grace period elapsed)",
            details = listOf("Account number" to "0917-000123"),
            linkPath = "/accounts/acc-1",
        )

        When("rendering") {
            val rendered = NotificationTemplates.render(NotificationEvent.ACCOUNT_TERMINATED, ctx, appUrl)

            Then("the lone cell spans both columns instead of leaving a ragged gap") {
                rendered.html.split("colspan=\"2\"").size - 1 shouldBe 1
            }

            Then("the button uses the account noun") {
                rendered.html shouldContain "View Account in IBMS"
            }
        }
    }

    Given("a store event with no details and no deep link") {
        val ctx = NotificationContext(headline = "New store added: SM North (Branch 119)")

        When("rendering") {
            val rendered = NotificationTemplates.render(NotificationEvent.STORE_CREATED, ctx, appUrl)

            Then("the detail card is omitted entirely") {
                rendered.html shouldNotContain "background-color:#E2F4EE"
            }

            Then("no button is rendered") {
                rendered.html shouldNotContain "<a href="
            }

            Then("the shell still renders") {
                rendered.html shouldContain "New store added: SM North (Branch 119)"
            }
        }
    }

    Given("a store name containing markup") {
        val ctx = NotificationContext(
            headline = "New store added: <script>alert(1)</script> & co",
            details = listOf("Store name" to "<b>Bold</b>"),
        )

        When("rendering") {
            val rendered = NotificationTemplates.render(NotificationEvent.STORE_CREATED, ctx, appUrl)

            Then("it is escaped rather than injected into the document") {
                rendered.html shouldNotContain "<script>"
                rendered.html shouldContain "&lt;script&gt;alert(1)&lt;/script&gt; &amp; co"
                rendered.html shouldContain "&lt;b&gt;Bold&lt;/b&gt;"
            }
        }
    }

    Given("amounts on their way to a detail cell") {
        When("formatting for display") {
            Then("they are grouped, 2dp and peso-prefixed") {
                Money.display("555335.00") shouldBe "₱555,335.00"
                Money.display("0.00") shouldBe "₱0.00"
                Money.display("1020224.5") shouldBe "₱1,020,224.50"
            }

            Then("an unparseable amount falls back instead of throwing inside the enqueuing transaction") {
                Money.display("not-a-number") shouldBe "not-a-number"
                Money.display(null) shouldBe "₱0.00"
            }
        }
    }
})
