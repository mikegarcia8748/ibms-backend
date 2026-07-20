package com.puregoldbe.ibms.adapter.controller

import kotlinx.serialization.Serializable

/** Small request bodies that aren't part of the shared domain model. */

@Serializable
data class CreateProviderRequest(val name: String, val paymentScheduleDay: Int)

@Serializable
data class UpdateProviderRequest(val name: String? = null, val paymentScheduleDay: Int? = null)
