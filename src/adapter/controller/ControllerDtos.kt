package com.puregoldbe.ibms.adapter.controller

import kotlinx.serialization.Serializable

/** Small request bodies that aren't part of the shared domain model. */

@Serializable
data class CreateProviderRequest(val name: String, val paymentScheduleDay: Int)

@Serializable
data class UpdateProviderRequest(val name: String? = null, val paymentScheduleDay: Int? = null)

@Serializable
data class RejectChangeRequestBody(val reason: String)

/** Summary of a bulk-import run: counts of entities created vs reused, plus skip reasons. */
@Serializable
data class BulkImportSummary(
    val providers: List<ProviderImportSummary>,
    val storesCreated: Int,
    val storesReused: Int,
    val accountsCreated: Int,
    val accountsReused: Int,
    val rowsSkipped: Int,
    val skipReasons: List<String>,
    val totalRows: Int,
)

@Serializable
data class ProviderImportSummary(
    val name: String,
    val created: Boolean,
    val accountsCreated: Int,
    val accountsReused: Int,
)

@Serializable
data class UpdateLineRequest(
    val rfpNumber: String? = null,
    val proratedAmount: String? = null,
)

/**
 * Body for POST /topsheets/{id}/assign-rfp — bulk-assign a contiguous RFP number
 * range across a draft's lines, sorted by store code. [endRfpNumber] is a safety
 * check: the range must cover exactly as many numbers as there are store codes.
 */
@Serializable
data class AssignRfpNumbersRequest(
    val startRfpNumber: String,
    val endRfpNumber: String,
)
