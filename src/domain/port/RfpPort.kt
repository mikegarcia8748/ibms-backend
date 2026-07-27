package com.puregoldbe.ibms.domain.port

// ---------------------------------------------------------------------------
// External RFP system seam.
//
// IBMS no longer assigns RFP (Request for Payment) numbers manually. Instead it
// calls an external system that generates an RFP number + a unique key per line,
// and later notifies that system when the secretary releases the payment
// transaction to finance.
//
// The external system's real payload/response contract is NOT finalized yet, so
// these DTOs are deliberate placeholders — enough to drive the whole IBMS flow
// against [com.puregoldbe.ibms.adapter.gateway.SimulatedRfpGateway]. When the real
// contract lands, refine these shapes and add an HTTP adapter behind [RfpGateway];
// nothing else in the pipeline changes. Mirrors the OcrGateway/SimulatedOcrExtractor
// seam.
// ---------------------------------------------------------------------------

/** One topsheet line handed to the external system for RFP generation. */
data class RfpLineInput(
    val lineId: String,
    val accountId: String,
    val accountNumber: String?,
    val branchCode: String?,
    val storeName: String?,
    val amount: String,
)

data class RfpGenerationInput(
    val topsheetId: String,
    val billingPeriod: String,
    val providerName: String?,
    val batchNumber: String?,
    val lines: List<RfpLineInput>,
)

/** The RFP number + unique key the external system minted for a single line. */
data class RfpLineAssignment(
    val lineId: String,
    val rfpNumber: String,
    val uniqueKey: String,
)

data class RfpGenerationResult(
    val lines: List<RfpLineAssignment>,
)

/** A line's external linkage, sent back when releasing the batch to finance. */
data class RfpReleaseLine(
    val lineId: String,
    val rfpNumber: String?,
    val uniqueKey: String?,
)

data class RfpReleaseInput(
    val topsheetId: String,
    val invoiceNumber: String?,
    val lines: List<RfpReleaseLine>,
)

data class RfpReleaseResult(
    val success: Boolean,
    val externalReference: String? = null,
)

/**
 * The external RFP system seam. [SimulatedRfpGateway] returns deterministic RFP
 * numbers/keys so the whole flow works without a network call; a real HTTP adapter
 * can replace it behind this interface once the contract is published.
 */
interface RfpGateway {
    /** Generate an RFP number + unique key for each line in [input]. */
    fun generateRfp(input: RfpGenerationInput): RfpGenerationResult

    /** Tell the external system to move the (open) payment transaction to finance. */
    fun notifyReleaseToFinance(input: RfpReleaseInput): RfpReleaseResult
}
