package com.puregoldbe.ibms.adapter.gateway

import com.puregoldbe.ibms.domain.port.NewOcrRow
import com.puregoldbe.ibms.domain.port.OcrExtractionInput
import com.puregoldbe.ibms.domain.port.OcrExtractionResult
import com.puregoldbe.ibms.domain.port.OcrGateway

/**
 * Deterministic OCR stub: produces three fixed billing rows so the whole batch flow
 * (extract -> batch -> rows) works end-to-end without an external call. Replace with a
 * real Gemini adapter behind [OcrGateway] — nothing else in the pipeline changes.
 */
class SimulatedOcrExtractor : OcrGateway {
    override fun extract(input: OcrExtractionInput): OcrExtractionResult {
        val period = input.billingMonth ?: "2026-08"
        val rows = (1..3).map { i ->
            NewOcrRow(
                accountNumber = "003030${1_000_000 + i}",
                amount = "%.2f".format(1000.0 + i * 100),
                outstandingBalance = "0.00",
                dueDate = null,
                ispName = "Converge",
                storeName = "Simulated Store $i",
                invoiceNumber = "SIM-$period-$i",
                billNumber = null,
                billingPeriod = period,
            )
        }
        return OcrExtractionResult(
            method = "simulated",
            usedTemplate = input.templateKey?.takeIf { it.isNotBlank() && it != "auto" },
            rows = rows,
        )
    }
}
