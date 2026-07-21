package com.puregoldbe.ibms.adapter.controller

import kotlinx.serialization.Serializable

/** Small request bodies that aren't part of the shared domain model. */

@Serializable
data class CreateProviderRequest(val name: String, val paymentScheduleDay: Int)

@Serializable
data class UpdateProviderRequest(val name: String? = null, val paymentScheduleDay: Int? = null)

/** Summary of a bulk-import run: counts of entities created vs reused, plus skip reasons. */
@Serializable
data class BulkImportSummary(
    val providerName: String,
    val providerCreated: Boolean,
    val storesCreated: Int,
    val storesReused: Int,
    val accountsCreated: Int,
    val accountsReused: Int,
    val rowsSkipped: Int,
    val skipReasons: List<String>,
    val totalRows: Int,
)
