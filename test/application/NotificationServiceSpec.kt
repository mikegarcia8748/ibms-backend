package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.NotificationService
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.port.EmailLogRepository
import com.puregoldbe.ibms.domain.port.NotificationSubscriptionRepository
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/**
 * NotificationService resolves subscribers, renders once, and writes exactly one
 * `email_log` row — or nothing when nobody is subscribed (the outbox `to_emails`
 * column is NOT NULL, so an empty send is meaningless).
 */
class NotificationServiceSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val subs = mockk<NotificationSubscriptionRepository>()
    val emailLog = mockk<EmailLogRepository>(relaxed = true)
    val service = NotificationService(subs, emailLog, appUrl = "https://ibms.example", fromEmail = "no-reply@ibms.example")

    val ctx = NotificationContext(
        headline = "New store added: SM North (Branch 119)",
        details = listOf("Branch code" to "119"),
        entityId = "store-1",
        linkPath = "/stores/store-1",
    )

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
        }
    }

    Given("no subscribers to the event") {
        every { subs.subscribersOf(NotificationEvent.STORE_CREATED) } returns emptyList()

        When("enqueuing") {
            service.enqueue(NotificationEvent.STORE_CREATED, ctx)

            Then("nothing is written to the outbox") {
                verify(exactly = 0) { emailLog.enqueue(any(), any(), any(), any(), any(), any()) }
            }
        }
    }
})
