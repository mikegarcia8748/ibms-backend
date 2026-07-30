package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.DispatchQueuedEmailsUseCase
import com.puregoldbe.ibms.domain.model.EmailDeliveryStatus
import com.puregoldbe.ibms.domain.model.EmailSendResult
import com.puregoldbe.ibms.domain.port.EmailLogRepository
import com.puregoldbe.ibms.domain.port.EmailPort
import com.puregoldbe.ibms.domain.port.QueuedEmail
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/** The dispatcher sends each queued row and records its terminal status. */
class DispatchQueuedEmailsUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val emailLog = mockk<EmailLogRepository>(relaxed = true)
    val email = mockk<EmailPort>()
    val clock = FakeClock()
    val useCase = DispatchQueuedEmailsUseCase(emailLog, email, clock, ImmediateTransactionRunner())

    val queued = QueuedEmail(
        id = "e1",
        type = "store.created",
        fromEmail = "no-reply@x.com",
        toEmails = listOf("sec@x.com"),
        subject = "[IBMS] New store added",
        bodyText = "body",
        bodyHtml = "<p>body</p>",
    )

    Given("one queued email the provider accepts") {
        every { emailLog.findQueued(any()) } returns listOf(queued)
        every { email.send(any()) } returns EmailSendResult(EmailDeliveryStatus.SENT, "HTTP 202")

        When("dispatching") {
            val processed = useCase()

            Then("it sends the rendered message and marks the row sent") {
                processed shouldBe 1
                verify(exactly = 1) {
                    email.send(match { it.type == "store.created" && it.toEmails == listOf("sec@x.com") })
                }
                verify(exactly = 1) { emailLog.markResult("e1", EmailDeliveryStatus.SENT, "HTTP 202", clock.now()) }
            }
        }
    }

    Given("nothing queued") {
        every { emailLog.findQueued(any()) } returns emptyList()

        When("dispatching") {
            val processed = useCase()

            Then("no send is attempted") {
                processed shouldBe 0
                verify(exactly = 0) { email.send(any()) }
            }
        }
    }
})
