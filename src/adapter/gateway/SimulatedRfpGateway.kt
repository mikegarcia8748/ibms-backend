package com.puregoldbe.ibms.adapter.gateway

import com.puregoldbe.ibms.domain.port.RfpGateway
import com.puregoldbe.ibms.domain.port.RfpGenerationInput
import com.puregoldbe.ibms.domain.port.RfpGenerationResult
import com.puregoldbe.ibms.domain.port.RfpLineAssignment
import com.puregoldbe.ibms.domain.port.RfpReleaseInput
import com.puregoldbe.ibms.domain.port.RfpReleaseResult

/**
 * Deterministic RFP stub: mints a sequential RFP number and a synthetic unique key
 * per line so the whole flow (confirm -> generate-rfp -> release-to-finance) works
 * end-to-end without an external call. Replace with a real HTTP adapter behind
 * [RfpGateway] once the external contract is published — nothing else changes.
 */
class SimulatedRfpGateway : RfpGateway {
    override fun generateRfp(input: RfpGenerationInput): RfpGenerationResult {
        val assignments = input.lines.mapIndexed { i, line ->
            RfpLineAssignment(
                lineId = line.lineId,
                rfpNumber = (RFP_BASE + i).toString().padStart(RFP_WIDTH, '0'),
                uniqueKey = "SIM-RFP-${line.lineId}",
            )
        }
        return RfpGenerationResult(assignments)
    }

    override fun notifyReleaseToFinance(input: RfpReleaseInput): RfpReleaseResult =
        RfpReleaseResult(success = true, externalReference = "SIM-REL-${input.topsheetId}")

    private companion object {
        const val RFP_BASE = 100_001L
        const val RFP_WIDTH = 7
    }
}
