package com.puregoldbe.ibms.domain.port

import com.puregoldbe.ibms.domain.model.EmailMessage
import com.puregoldbe.ibms.domain.model.EmailSendResult

/**
 * Outbound email delivery seam. [com.puregoldbe.ibms.adapter.gateway.SimulatedEmailGateway]
 * just logs (used when no SMTP relay is configured, so local/tests work end-to-end);
 * [com.puregoldbe.ibms.adapter.gateway.SmtpEmailGateway] sends through the org relay.
 * Non-suspend and called OUTSIDE any DB transaction by the background dispatcher, so the
 * blocking network round-trip never holds a Postgres connection. Mirrors the
 * OcrGateway/RfpGateway seam.
 */
interface EmailPort {
    fun send(message: EmailMessage): EmailSendResult
}
