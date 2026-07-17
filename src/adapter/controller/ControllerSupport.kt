package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.ProviderStatus
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.application.*

/** Shared controller helpers: path/enum query-param parsing. */

internal fun ApplicationCall.pathId(): String =
    parameters["id"] ?: throw DomainError.Validation("missing path id")

private inline fun <reified T : Enum<T>> parseEnum(raw: String?): T? =
    raw?.takeIf { it.isNotBlank() }?.let { value ->
        runCatching { enumValueOf<T>(value.uppercase()) }.getOrNull()
    }

internal fun parseUserRole(raw: String?): UserRole? = parseEnum<UserRole>(raw)
internal fun parseProviderStatus(raw: String?): ProviderStatus? = parseEnum<ProviderStatus>(raw)
internal fun parseStoreStatus(raw: String?): StoreStatus? = parseEnum<StoreStatus>(raw)
internal fun parseAccountStatus(raw: String?): AccountStatus? = parseEnum<AccountStatus>(raw)
internal fun parseAttachmentPurpose(raw: String?): AttachmentPurpose? = parseEnum<AttachmentPurpose>(raw)
internal fun parseTopSheetStatus(raw: String?): TopSheetStatus? = parseEnum<TopSheetStatus>(raw)
