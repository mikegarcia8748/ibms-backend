package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.adapter.gateway.SmtpEmailGateway
import com.puregoldbe.ibms.domain.model.EmailDeliveryStatus
import com.puregoldbe.ibms.domain.model.EmailMessage
import com.puregoldbe.ibms.infrastructure.config.SmtpConfig
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import javax.net.ssl.SSLHandshakeException

/**
 * Builds the MIME message without an SMTP server: the gateway's `transport` seam is
 * swapped for a lambda that captures what would have gone on the wire.
 *
 * The capture calls `saveChanges()` first because the real `Transport.send` does — it
 * is what runs `updateHeaders()` and writes Content-Type, Date and Message-ID. Assert
 * on an unsaved message and Content-Type still reads as the `text/plain` default.
 */
private fun capture(into: MutableList<MimeMessage>): (MimeMessage) -> Unit =
    { it.saveChanges(); into += it }

class SmtpEmailGatewaySpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val cfg = SmtpConfig(
        host = "relay.internal.example",
        port = 587,
        username = "ibms@example.com",
        password = "pw",
        startTls = true,
        sslOnConnect = false,
        fromEmail = "no-reply@example.com",
        fromName = "IBMS Notifications",
    )

    val message = EmailMessage(
        fromEmail = "",
        fromName = null,
        toEmails = listOf("sec@example.com", "fin@example.com"),
        subject = "[IBMS] New store added",
        bodyText = "Store SM North was added.",
        bodyHtml = "<p>Store SM North was added.</p>",
        type = "store.created",
    )

    Given("a message carrying both a text and an HTML body") {
        val sent = mutableListOf<MimeMessage>()
        val gateway = SmtpEmailGateway(cfg, capture(sent))

        When("sending") {
            val result = gateway.send(message)

            Then("it reports SENT and names the relay") {
                result.status shouldBe EmailDeliveryStatus.SENT
                result.providerResponse shouldBe "smtp relay.internal.example:587"
            }

            Then("the From falls back to the configured relay address and display name") {
                val from = sent.single().from.single() as InternetAddress
                from.address shouldBe "no-reply@example.com"
                from.personal shouldBe "IBMS Notifications"
            }

            Then("every recipient lands in To, and the subject survives") {
                val to = sent.single().getRecipients(Message.RecipientType.TO)
                to.map { (it as InternetAddress).address } shouldBe
                    listOf("sec@example.com", "fin@example.com")
                sent.single().subject shouldBe "[IBMS] New store added"
            }

            Then("a Date and Message-ID are stamped by Jakarta Mail, not by us") {
                sent.single().sentDate shouldNotBe null
                sent.single().messageID shouldNotBe null
            }

            Then("the body is multipart/alternative with plain text before HTML") {
                val mime = sent.single()
                mime.contentType shouldStartWith "multipart/alternative"
                val parts = mime.content as MimeMultipart
                parts.count shouldBe 2
                parts.getBodyPart(0).contentType shouldStartWith "text/plain"
                parts.getBodyPart(0).content shouldBe "Store SM North was added."
                parts.getBodyPart(1).contentType shouldStartWith "text/html"
                parts.getBodyPart(1).content shouldBe "<p>Store SM North was added.</p>"
            }
        }
    }

    Given("a message with no HTML body") {
        val sent = mutableListOf<MimeMessage>()
        val gateway = SmtpEmailGateway(cfg, capture(sent))

        When("sending") {
            val result = gateway.send(message.copy(bodyHtml = null))

            Then("it is a plain single-part text mail") {
                result.status shouldBe EmailDeliveryStatus.SENT
                sent.single().contentType shouldStartWith "text/plain"
                sent.single().content shouldBe "Store SM North was added."
            }
        }
    }

    Given("a relay that rejects the handoff") {
        val gateway = SmtpEmailGateway(cfg) { error("550 relay access denied") }

        When("sending") {
            val result = gateway.send(message)

            Then("the failure is returned, not thrown — one bad send must not stall the outbox") {
                result.status shouldBe EmailDeliveryStatus.FAILED
                result.providerResponse!! shouldContain "550 relay access denied"
            }
        }
    }

    Given("a failure whose real reason sits on the cause chain, as a TLS rejection does") {
        val gateway = SmtpEmailGateway(cfg) {
            throw MessagingException(
                "Could not convert socket to TLS",
                SSLHandshakeException("PKIX path building failed: unable to find valid certification path"),
            )
        }

        When("sending") {
            val result = gateway.send(message)

            Then("the stored response names the root cause, not just the generic wrapper") {
                result.status shouldBe EmailDeliveryStatus.FAILED
                val response = result.providerResponse!!
                response shouldContain "Could not convert socket to TLS"
                response shouldContain "PKIX path building failed"
            }
        }
    }

    Given("a cause chain that loops back on itself") {
        // Plain exceptions, because MessagingException forbids initCause outright — it
        // carries its own chain via setNextException.
        val outer = RuntimeException("outer")
        val inner = RuntimeException("inner")
        outer.initCause(inner)
        inner.initCause(outer)
        val gateway = SmtpEmailGateway(cfg) { throw outer }

        When("sending") {
            val result = gateway.send(message)

            Then("the walk terminates instead of spinning") {
                result.status shouldBe EmailDeliveryStatus.FAILED
                result.providerResponse!! shouldContain "outer"
            }
        }
    }

    Given("no from address anywhere — neither on the row nor in config") {
        val sent = mutableListOf<MimeMessage>()
        val gateway = SmtpEmailGateway(cfg.copy(fromEmail = "")) { sent += it }

        When("sending") {
            val result = gateway.send(message)

            Then("it fails without touching the transport") {
                result.status shouldBe EmailDeliveryStatus.FAILED
                result.providerResponse shouldBe "no from address (set MAIL_FROM_EMAIL)"
                sent.size shouldBe 0
            }
        }
    }

    Given("a row with no recipients") {
        val sent = mutableListOf<MimeMessage>()
        val gateway = SmtpEmailGateway(cfg, capture(sent))

        When("sending") {
            val result = gateway.send(message.copy(toEmails = emptyList()))

            Then("it fails without touching the transport") {
                result.status shouldBe EmailDeliveryStatus.FAILED
                result.providerResponse shouldBe "no recipients"
                sent.size shouldBe 0
            }
        }
    }
})
