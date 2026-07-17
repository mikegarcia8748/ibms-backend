package com.puregoldbe.ibms.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared domain models & DTOs for the IBMS migration.
 *
 * This file lives in the KMP `shared` module and is compiled into BOTH the Ktor
 * backend and the Compose Multiplatform apps (Android / iOS / Wasm). That single
 * source of truth is the main payoff of going all-Kotlin: request/response shapes
 * can never drift between client and server.
 *
 * Design notes:
 *  - Enum `@SerialName`s use the lowercase wire values already in the data, so the
 *    same JSON works against legacy exports during migration.
 *  - Money is carried as a decimal STRING on the wire (e.g. "1499.00") and parsed
 *    to BigDecimal on the backend / numeric(14,2) in Postgres. Never Double — this
 *    is billing. A `Money` typealias documents intent.
 *  - Dates: LocalDate for calendar fields (installation/contract), Instant for
 *    event timestamps. kotlinx-datetime serializes both as ISO-8601 strings.
 */

typealias Money = String   // decimal string, 2 dp; parse with BigDecimal(value) server-side

// =====================================================================
//  Enums
// =====================================================================
@Serializable
enum class UserRole {
    @SerialName("sysadmin")  SYSADMIN,
    @SerialName("secretary") SECRETARY,
    @SerialName("payables")  PAYABLES,
    @SerialName("finance")   FINANCE,
    @SerialName("pending")   PENDING,
}

@Serializable
enum class StoreType {
    @SerialName("puregold") PUREGOLD,
    @SerialName("puremart") PUREMART,
}

@Serializable
enum class StoreStatus {
    @SerialName("active")   ACTIVE,
    @SerialName("closed")   CLOSED,
    @SerialName("inactive") INACTIVE,
}

@Serializable
enum class ProviderStatus {
    @SerialName("active")   ACTIVE,
    @SerialName("inactive") INACTIVE,
}

@Serializable
enum class AccountStatus {
    @SerialName("active")                ACTIVE,
    @SerialName("termination_requested") TERMINATION_REQUESTED,
    @SerialName("terminated")            TERMINATED,
    @SerialName("transferred")           TRANSFERRED,
    @SerialName("inactive")              INACTIVE,
}

@Serializable
enum class TopSheetStatus {
    @SerialName("compiled") COMPILED,
    @SerialName("approved") APPROVED,
    @SerialName("paid")     PAID,
}

@Serializable
enum class TopSheetLineStatus {
    @SerialName("billed") BILLED,
    @SerialName("paid")   PAID,
}

@Serializable
enum class AttachmentPurpose {
    @SerialName("installation_proof") INSTALLATION_PROOF,
    @SerialName("closure_proof")      CLOSURE_PROOF,
    @SerialName("subscription_proof") SUBSCRIPTION_PROOF,
    @SerialName("deactivation_proof") DEACTIVATION_PROOF,
    @SerialName("transfer_proof")     TRANSFER_PROOF,
    @SerialName("ocr_source")         OCR_SOURCE,
}

// =====================================================================
//  Core entities (API representations)
// =====================================================================
@Serializable
data class UserProfile(
    val id: String,
    val email: String,
    val name: String,
    val firstName: String? = null,
    val middleInitial: String? = null,
    val lastName: String? = null,
    val employeeNumber: String? = null,
    val role: UserRole,
)

@Serializable
data class Store(
    val id: String,
    val storeType: StoreType,
    val branchCode: String,
    val name: String,
    val region: String? = null,
    val province: String? = null,
    val city: String? = null,
    val barangay: String? = null,
    val postal: String? = null,
    val status: StoreStatus = StoreStatus.ACTIVE,
    val closedReason: String? = null,
    val proofOfInstallationId: String,
    val proofOfClosureId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)

@Serializable
data class Provider(
    val id: String,
    val name: String,
    val paymentScheduleDay: Int,          // 1..31
    val status: ProviderStatus = ProviderStatus.ACTIVE,
    val deactivatedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)

@Serializable
data class Account(
    val id: String,
    val accountNumber: String,
    val circuitId: String? = null,
    val providerId: String,
    val storeId: String,
    val planName: String? = null,
    val serviceType: String? = null,
    val speed: String? = null,
    val contractDurationMonths: Int? = null,
    val contractStartDate: LocalDate? = null,
    val contractEndDate: LocalDate? = null,
    val notes: String? = null,
    val installationFee: Money? = null,
    val rate: Money,                       // MRC
    val installationDate: LocalDate,
    val billingPeriodLabel: String? = null,
    val isProrated: Boolean = false,
    val status: AccountStatus = AccountStatus.ACTIVE,
    val terminationRequestedAt: Instant? = null,
    val subscriptionProofIds: List<String> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)

@Serializable
data class TopSheet(
    val id: String,
    val invoiceNumber: String,
    val billingPeriod: String,             // "YYYY-MM"
    val providerId: String? = null,
    val providerName: String? = null,
    val accountCount: Int,
    val totalAmount: Money,
    val status: TopSheetStatus = TopSheetStatus.COMPILED,
    val compilerId: String,
    val approvedByFinanceId: String? = null,
    val approvedAt: Instant? = null,
    val paidAt: Instant? = null,
    val compilationDate: Instant,
)

@Serializable
data class TopSheetDetail(
    val id: String,
    val topsheetId: String,
    val accountId: String,
    val billingPeriod: String,
    val proratedAmount: Money,
    val fullAmount: Money,
    val status: TopSheetLineStatus = TopSheetLineStatus.BILLED,
    val branchCode: String? = null,
    val storeName: String? = null,
    val circuitId: String? = null,
    val accountNumber: String? = null,
    val accountStatus: String? = null,
)

@Serializable
data class TransferRecord(
    val id: String,
    val oldStoreId: String,
    val newStoreId: String,
    val oldAccountId: String,
    val newAccountId: String,
    val proofId: String? = null,
    val requestedById: String,
    val transferDate: Instant,
)

@Serializable
data class Activity(
    val id: String,
    val userId: String? = null,
    val userEmail: String? = null,
    val userName: String? = null,
    val action: String,
    val details: String? = null,
    val createdAt: Instant,
)

// =====================================================================
//  Request / command DTOs  (the write side of the API)
// =====================================================================
@Serializable
data class GoogleAuthRequest(val idToken: String)

@Serializable
data class AuthResponse(val token: String, val user: UserProfile)

@Serializable
data class UpdateRoleRequest(val role: UserRole)

@Serializable
data class StoreUpsertRequest(
    val storeType: StoreType,
    val branchCode: String,
    val name: String,
    val region: String? = null,
    val province: String? = null,
    val city: String? = null,
    val barangay: String? = null,
    val postal: String? = null,
    val proofOfInstallationId: String,
)

@Serializable
data class CloseStoreRequest(val reason: String, val proofOfClosureId: String)

@Serializable
data class AccountUpsertRequest(
    val accountNumber: String,
    val circuitId: String? = null,
    val providerId: String,
    val storeId: String,
    val planName: String? = null,
    val serviceType: String? = null,
    val speed: String? = null,
    val contractDurationMonths: Int? = null,
    val contractStartDate: LocalDate? = null,
    val contractEndDate: LocalDate? = null,
    val notes: String? = null,
    val installationFee: Money? = null,
    val rate: Money,
    val installationDate: LocalDate,
    val billingPeriodLabel: String? = null,
    val subscriptionProofIds: List<String> = emptyList(),
)

@Serializable
data class TransferAccountRequest(val newStoreId: String, val proofId: String)

@Serializable
data class DeactivateAccountRequest(val proofId: String)

/** Body for both /topsheets/preview and /topsheets/compile. */
@Serializable
data class CompileRequest(val providerId: String, val billingPeriod: String)

/** Preview result — the eligible line the Secretary reviews before compiling. */
@Serializable
data class CompilablePreview(
    val providerId: String,
    val billingPeriod: String,
    val lines: List<CompilableLine>,
    val totalAmount: Money,
)

@Serializable
data class CompilableLine(
    val accountId: String,
    val accountNumber: String,
    val branchCode: String?,
    val storeName: String?,
    val circuitId: String?,
    val fullAmount: Money,          // MRC
    val proratedAmount: Money,
    val isProrated: Boolean,
)

// =====================================================================
//  OCR ingestion DTOs (mirror the existing /api/ocr contract)
// =====================================================================
@Serializable
data class OcrExtractRequest(
    val fileName: String? = null,
    val fileContent: String? = null,
    val providerId: String? = null,
    val telcoProvider: String? = null,
    val billingMonth: String? = null,       // "YYYY-MM"
    val primingFormat: String? = null,      // template config_key or "auto"
)

@Serializable
data class OcrExtractResponse(
    val success: Boolean,
    val method: String,                     // gemini-ocr | booster-ocr-converge | simulated
    val usedTemplate: String? = null,
    val data: List<OcrRow>,
)

@Serializable
data class OcrRow(
    val accountNumber: String,
    val amount: Money,
    val dueDate: String? = null,
    val ispName: String? = null,
    val storeName: String? = null,
    val invoiceNumber: String? = null,
    val billNumber: String? = null,
    val billingPeriod: String? = null,
    val outstandingBalance: Money? = null,
)

// =====================================================================
//  Generic envelopes
// =====================================================================
@Serializable
data class ApiError(val error: String, val code: String? = null)

@Serializable
data class Page<T>(val items: List<T>, val total: Int, val page: Int = 0, val pageSize: Int = 50)
