package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.model.EmailMessage
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.EmailLogRepository
import com.puregoldbe.ibms.domain.port.EmailPort
import com.puregoldbe.ibms.domain.port.TransactionRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drains the `email_log` outbox: reads a batch of `queued` rows, sends each via
 * [EmailPort] OUTSIDE any transaction (the blocking network call never holds a DB
 * connection), then records the terminal status. Invoked on a background loop.
 *
 * v1 limitation: a crash between send and [EmailLogRepository.markResult] leaves a
 * row `queued`, so it can be sent twice on the next run. Acceptable for a first cut;
 * a `sending` claim state or `FOR UPDATE SKIP LOCKED` would close the window.
 */
class DispatchQueuedEmailsUseCase(
    private val emailLog: EmailLogRepository,
    private val email: EmailPort,
    private val clock: Clock,
    private val tx: TransactionRunner,
    private val batchSize: Int = 50,
) {
    /** @return the number of rows processed this pass. */
    suspend operator fun invoke(): Int {
        val queued = tx.inTransaction { emailLog.findQueued(batchSize) }
        var processed = 0
        for (row in queued) {
            val result = withContext(Dispatchers.IO) {
                email.send(
                    EmailMessage(
                        fromEmail = row.fromEmail.orEmpty(),
                        fromName = null,
                        toEmails = row.toEmails,
                        subject = row.subject.orEmpty(),
                        bodyText = row.bodyText.orEmpty(),
                        bodyHtml = row.bodyHtml,
                        type = row.type.orEmpty(),
                    ),
                )
            }
            tx.inTransaction { emailLog.markResult(row.id, result.status, result.providerResponse, clock.now()) }
            processed++
        }
        return processed
    }
}
