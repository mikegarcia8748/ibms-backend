package com.puregoldbe.ibms.adapter.gateway

import com.puregoldbe.ibms.domain.model.EmailDeliveryStatus
import com.puregoldbe.ibms.domain.model.EmailMessage
import com.puregoldbe.ibms.domain.model.EmailSendResult
import com.puregoldbe.ibms.domain.port.EmailPort
import com.puregoldbe.ibms.infrastructure.config.SmtpConfig
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Properties

/** Enough of the chain to reach the root cause without letting a cycle run away. */
private const val MAX_CAUSE_DEPTH = 5

/**
 * Real delivery through the org's internal SMTP relay. Called only by the background
 * dispatcher, OUTSIDE any DB transaction and on [kotlinx.coroutines.Dispatchers.IO], so
 * the blocking SMTP round-trip never pins a Postgres connection. Any rejection or thrown
 * exception is turned into a `FAILED` result (never propagated) so one bad address can't
 * stall the queue. Mirrors the SimulatedRfpGateway/OcrGateway seam.
 *
 * [transport] exists to make this testable without an SMTP server: the spec swaps in a
 * lambda that captures the [MimeMessage] instead of putting it on the wire.
 */
class SmtpEmailGateway(
    private val cfg: SmtpConfig,
    private val transport: (MimeMessage) -> Unit = { Transport.send(it) },
) : EmailPort {
    private val log = LoggerFactory.getLogger(javaClass)

    // One Session for the process. It holds no connection — Transport.send opens and
    // closes one per message — so this is just the resolved property bag.
    private val session: Session by lazy { buildSession() }

    override fun send(message: EmailMessage): EmailSendResult {
        val from = message.fromEmail.ifBlank { cfg.fromEmail }
        if (from.isBlank()) {
            return EmailSendResult(EmailDeliveryStatus.FAILED, "no from address (set MAIL_FROM_EMAIL)")
        }
        if (message.toEmails.isEmpty()) {
            return EmailSendResult(EmailDeliveryStatus.FAILED, "no recipients")
        }
        return runCatching {
            val mime = MimeMessage(session).apply {
                setFrom(InternetAddress(from, message.fromName ?: cfg.fromName, "UTF-8"))
                setRecipients(
                    Message.RecipientType.TO,
                    message.toEmails.map { InternetAddress(it) }.toTypedArray(),
                )
                setSubject(message.subject, "UTF-8")
                if (message.bodyHtml != null) {
                    // multipart/alternative: least-capable part first, so a text-only
                    // client shows the plain body and everything else renders the HTML.
                    setContent(
                        MimeMultipart("alternative").apply {
                            addBodyPart(MimeBodyPart().apply { setText(message.bodyText, "UTF-8") })
                            addBodyPart(MimeBodyPart().apply { setContent(message.bodyHtml, "text/html; charset=UTF-8") })
                        },
                    )
                } else {
                    setText(message.bodyText, "UTF-8")
                }
                // Date and Message-ID are left to Jakarta Mail's updateHeaders(), which
                // runs on save — that keeps this gateway free of a Clock dependency.
            }
            transport(mime)
            EmailSendResult(EmailDeliveryStatus.SENT, "smtp ${cfg.host}:${cfg.port}")
        }.getOrElse { e ->
            log.error("[email:smtp] send to {} failed", message.toEmails, e)
            EmailSendResult(EmailDeliveryStatus.FAILED, describe(e))
        }
    }

    /**
     * Flattens the cause chain into the stored response. Jakarta Mail wraps the reason
     * that actually matters: a rejected relay certificate arrives as the entirely
     * uninformative "Could not convert socket to TLS", with `PKIX path building failed`
     * only on the cause. Recording the head of the chain alone sends whoever reads the
     * `email_log` row hunting for a stack trace that has long since rotated away.
     *
     * The depth bound is also what makes a cyclic chain safe to walk — the JDK forbids
     * an exception causing itself, but not two exceptions causing each other.
     */
    private fun describe(e: Throwable): String =
        generateSequence(e) { prev -> prev.cause?.takeIf { it !== prev } }
            .take(MAX_CAUSE_DEPTH)
            .joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }
            .take(500)

    private fun buildSession(): Session {
        val props = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", cfg.host)
            put("mail.smtp.port", cfg.port.toString())
            put("mail.smtp.auth", (cfg.username != null).toString())
            put("mail.smtp.starttls.enable", cfg.startTls.toString())
            // Fail rather than silently continue in the clear if the relay won't upgrade.
            put("mail.smtp.starttls.required", cfg.startTls.toString())
            put("mail.smtp.ssl.enable", cfg.sslOnConnect.toString())
            // Without these a hung relay would block a dispatcher thread indefinitely.
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
            cfg.trustedCertPath?.let { path ->
                // Jakarta Mail takes the factory as a live object here, not a class name.
                put("mail.smtp.ssl.socketFactory", SmtpTrust.socketFactory(File(path)))
                // Off only because the pin is stricter than the check it replaces: one
                // exact certificate is accepted, so reaching the right relay no longer
                // rests on the name resolving to it. Without a pin this stays on (the
                // Jakarta Mail default) — see SmtpTrust for the full argument.
                put("mail.smtp.ssl.checkserveridentity", "false")
            }
        }
        return if (cfg.username != null) {
            Session.getInstance(
                props,
                object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(cfg.username, cfg.password.orEmpty())
                },
            )
        } else {
            Session.getInstance(props)
        }
    }
}
