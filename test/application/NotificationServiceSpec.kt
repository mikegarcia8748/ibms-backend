package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.NotificationService
import com.puregoldbe.ibms.domain.model.DeepLinks
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.port.EmailLogRepository
import com.puregoldbe.ibms.domain.port.NotificationSubscriptionRepository
import com.puregoldbe.ibms.domain.port.UserRepository
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

/**
 * NotificationService resolves subscribers, renders once, and writes exactly one
 * `email_log` row — or nothing when nobody is subscribed (the outbox `to_emails`
 * column is NOT NULL, so an empty send is meaningless).
 *
 * It also owns the two things a rendered notification cannot get anywhere else: the
 * base URL its deep link resolves against, and the actor's display name.
 */
class NotificationServiceSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val webClient = "https://ibms-client.example"
    val api = "https://ibms-api.example"

    val subs = mockk<NotificationSubscriptionRepository>()
    val emailLog = mockk<EmailLogRepository>(relaxed = true)
    val users = mockk<UserRepository>()
    val service = NotificationService(
        subs,
        emailLog,
        users,
        webClientUrl = webClient,
        fromEmail = "no-reply@ibms.example",
    )

    fun user(name: String) = UserProfile(
        id = "user-1",
        username = "mgarcia",
        name = name,
        role = UserRole.SECRETARY,
        mustChangePassword = false,
    )

    val ctx = NotificationContext(
        headline = "New store added: SM North (Branch 119)",
        details = listOf("Branch code" to "119"),
        entityId = "store-1",
        linkPath = DeepLinks.store("store-1"),
    )

    /** Captures the html body of the single row the service is expected to write. */
    fun capturedHtml(): String {
        val html = slot<String>()
        verify(exactly = 1) { emailLog.enqueue(any(), any(), any(), any(), any(), capture(html)) }
        return html.captured
    }

    Given("two subscribers to the event") {
        every { subs.subscribersOf(NotificationEvent.STORE_CREATED) } returns listOf("sec@x.com", "fin@x.com")

        When("enqueuing") {
            service.enqueue(NotificationEvent.STORE_CREATED, ctx)

            Then("one queued row is written to those recipients, typed + subjected from the event") {
                verify(exactly = 1) {
                    emailLog.enqueue(
                        "store.created",
                        "no-reply@ibms.example",
                        listOf("sec@x.com", "fin@x.com"),
                        "[IBMS] New store added",
                        any(),
                        any(),
                    )
                }
            }

            // The regression this whole feature exists to prevent: the button used to be
            // built from APP_URL, which is this API's own origin, so clicking it in an
            // inbox hit a JSON route with no bearer token and answered 401.
            Then("the deep link resolves against the web client, never the API") {
                val html = capturedHtml()
                html shouldContain "href=\"$webClient/stores/store-1\""
                html shouldNotContain api
            }
        }
    }

    Given("a subscriber and a context carrying the acting user") {
        every { subs.subscribersOf(NotificationEvent.STORE_CREATED) } returns listOf("sec@x.com")

        When("the actor resolves to a named user") {
            every { users.findById("user-1") } returns user("Michael Garcia")
            service.enqueue(NotificationEvent.STORE_CREATED, ctx.copy(actorId = "user-1"))

            Then("the email says who performed it") {
                capturedHtml() shouldContain "Performed by Michael Garcia"
            }
        }

        When("the actor id resolves to nobody") {
            every { users.findById("ghost") } returns null
            service.enqueue(NotificationEvent.STORE_CREATED, ctx.copy(actorId = "ghost"))

            Then("no dangling attribution line is rendered") {
                capturedHtml() shouldNotContain "Performed by"
            }
        }

        // The seeded bootstrap admin can carry an empty name; a bare "Performed by "
        // reads as a rendering bug to the recipient.
        When("the actor resolves to a blank name") {
            every { users.findById("user-1") } returns user("   ")
            service.enqueue(NotificationEvent.STORE_CREATED, ctx.copy(actorId = "user-1"))

            Then("no attribution line is rendered") {
                capturedHtml() shouldNotContain "Performed by"
            }
        }
    }

    Given("no subscribers to the event") {
        every { subs.subscribersOf(NotificationEvent.STORE_CREATED) } returns emptyList()

        When("enqueuing") {
            service.enqueue(NotificationEvent.STORE_CREATED, ctx.copy(actorId = "user-1"))

            Then("nothing is written to the outbox") {
                verify(exactly = 0) { emailLog.enqueue(any(), any(), any(), any(), any(), any()) }
            }

            // Pins the ordering: the actor lookup sits after the early return, so an
            // event nobody subscribes to still costs exactly one query.
            Then("the actor is never looked up") {
                verify(exactly = 0) { users.findById(any()) }
            }
        }
    }

    Given("a context with no deep link at all") {
        every { subs.subscribersOf(NotificationEvent.STORE_CREATED) } returns listOf("sec@x.com")

        When("enqueuing") {
            service.enqueue(NotificationEvent.STORE_CREATED, ctx.copy(linkPath = null))

            Then("no button is rendered and the base URL appears nowhere") {
                val html = capturedHtml()
                html shouldContain "New store added"
                html.contains(webClient) shouldBe false
            }
        }
    }
})
