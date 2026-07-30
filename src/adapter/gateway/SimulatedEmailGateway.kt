package com.puregoldbe.ibms.adapter.gateway

import com.puregoldbe.ibms.domain.model.EmailDeliveryStatus
import com.puregoldbe.ibms.domain.model.EmailMessage
import com.puregoldbe.ibms.domain.model.EmailSendResult
import com.puregoldbe.ibms.domain.port.EmailPort
import org.slf4j.LoggerFactory

/**
 * No-network email sink used when no SMTP relay is configured (local dev + the
 * test suite). Logs the message and reports `simulated`, so the whole enqueue →
 * dispatch pipeline runs end-to-end and `email_log` rows land in a `simulated`
 * terminal state. Mirrors [SimulatedRfpGateway]; [SmtpEmailGateway] takes over
 * once SMTP_HOST is set.
 */
class SimulatedEmailGateway : EmailPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: EmailMessage): EmailSendResult {
        log.info(
            "[email:simulated] type={} to={} subject=\"{}\"",
            message.type,
            message.toEmails,
            message.subject,
        )
        return EmailSendResult(EmailDeliveryStatus.SIMULATED, "simulated: no SMTP_HOST configured")
    }
}
