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
    @SerialName("finance")   FINANCE,
    @SerialName("manager")   MANAGER,
    @SerialName("pending")   PENDING,
}

@Serializable
enum class UserStatus {
    @SerialName("active")   ACTIVE,
    @SerialName("inactive") INACTIVE,
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
    @SerialName("draft")    DRAFT,
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
/**
 * The public view of a user. `mustChangePassword` carries no default so it is
 * always on the wire — clients branch on it to route into the change-password
 * screen, and a silently-omitted `false` would be indistinguishable from absent.
 */
@Serializable
data class UserProfile(
    val id: String,
    val username: String,
    val name: String,
    val firstName: String? = null,
    val middleInitial: String? = null,
    val lastName: String? = null,
    val employeeNumber: String? = null,
    val role: UserRole,
    val status: UserStatus = UserStatus.ACTIVE,
    val mustChangePassword: Boolean,
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
    val invoiceNumber: String? = null,
    val batchNumber: String? = null,
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
    val rfpNumber: String? = null,
    val rfpSortOrder: Int? = null,
    val arrearsAmount: Money = "0.00",
    val arrearsPeriods: List<String> = emptyList(),
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
// ---------------------------------------------------------------------
//  Authentication
//
//  Accounts are provisioned by a sysadmin, never self-registered. The holder
//  of a temporary password is NOT yet authenticated: logging in with one
//  returns a change-password challenge instead of a session, and only the
//  successful password change mints tokens. Response types deliberately carry
//  no defaults — the app's kotlinx `json()` runs with encodeDefaults=false, so
//  a defaulted field would silently drop off the wire.
// ---------------------------------------------------------------------
@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
enum class LoginOutcome {
    /** Credentials accepted and a session exists — `session` is populated. */
    @SerialName("authenticated") AUTHENTICATED,

    /** Temporary password accepted — `passwordChange` is populated, no session yet. */
    @SerialName("password_change_required") PASSWORD_CHANGE_REQUIRED,
}

@Serializable
data class LoginResponse(
    val outcome: LoginOutcome,
    val user: UserProfile,
    val session: SessionTokens?,
    val passwordChange: PasswordChangeChallenge?,
)

@Serializable
data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresInSeconds: Long,
)

/**
 * Hands back a single-purpose token that authorizes exactly one call to
 * `POST /auth/password/change`. It cannot be used as a bearer token anywhere
 * else — see the `typ` claim check in the auth adapter.
 */
@Serializable
data class PasswordChangeChallenge(
    val challengeToken: String,
    val expiresInSeconds: Long,
    val reason: String,
)

/** Redeems a change-password challenge; authorized by the challenge token. */
@Serializable
data class ChangePasswordRequest(val newPassword: String)

/** Self-service rotation by an already-authenticated user. */
@Serializable
data class ChangeOwnPasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

/** Sysadmin-only account provisioning. The temporary password is generated server-side. */
@Serializable
data class ProvisionUserRequest(
    val username: String,
    val name: String,
    val firstName: String? = null,
    val middleInitial: String? = null,
    val lastName: String? = null,
    val employeeNumber: String? = null,
    val role: UserRole = UserRole.PENDING,
    val status: UserStatus = UserStatus.ACTIVE,
)

/**
 * The only time a temporary password is ever readable. It is stored as a bcrypt
 * hash, so neither this backend nor the database can reproduce it afterwards —
 * if the admin loses it, the account must be reset.
 */
@Serializable
data class ProvisionedUser(
    val user: UserProfile,
    val temporaryPassword: String,
    val temporaryPasswordExpiresAt: Instant,
)

@Serializable
data class UpdateRoleRequest(val role: UserRole)

@Serializable
data class UpdateUserStatusRequest(val status: UserStatus)

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
    val isProrated: Boolean = false,
)

@Serializable
data class CreateISPAccountInput(
    val accountNumber: String,
    val circuitId: String? = null,
    val providerId: String,
    val storeId: String,
    val rate: Money,
    val installationDate: LocalDate,
    val subscriptionProofId: String,
)

@Serializable
data class TransferAccountRequest(val newStoreId: String, val proofId: String)

/** Body for the top-level POST /transfers (carries the account id in the payload). */
@Serializable
data class TransferCreateRequest(val accountId: String, val newStoreId: String, val proofId: String)

@Serializable
data class DeactivateAccountRequest(val proofId: String)

/** Body for both /topsheets/preview and /topsheets/compile. */
@Serializable
data class CompileRequest(val providerId: String, val billingPeriod: String)

/** Optional body for /topsheets/{id}/confirm — acknowledge recovered arrears. */
@Serializable
data class ConfirmRequest(val acknowledgeArrears: Boolean = false)

/** Preview result — the eligible line the Secretary reviews before compiling. */
@Serializable
data class CompilablePreview(
    val providerId: String,
    val billingPeriod: String,
    val lines: List<CompilableLine>,
    /** Subset of [lines] carrying recovered arrears — surfaced for explicit acknowledgement. */
    val arrears: List<CompilableLine> = emptyList(),
    /** Accounts whose subscription starts after the selected period (validation warning). */
    val notYetSubscribed: List<NotYetSubscribedLine> = emptyList(),
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
    val isArrears: Boolean = false,
    val arrearsAmount: Money = "0.00",
    val arrearsPeriods: List<String> = emptyList(),
    val storeId: String? = null,
)

/** An account the reviewer selected a period for before its subscription began. */
@Serializable
data class NotYetSubscribedLine(
    val accountId: String,
    val accountNumber: String,
    val storeName: String?,
    val subscriptionStart: String,   // "YYYY-MM-DD"
    val billingPeriod: String,       // the (too-early) period that was selected
)

// =====================================================================
//  OCR ingestion (batch-trigger model: a batch -> extracted rows -> reconcile)
// =====================================================================
@Serializable
data class OcrTemplate(
    val id: String,
    val configKey: String,
    val providerId: String? = null,
    val ispName: String? = null,
    val formatName: String,
    val aiPromptInstruction: String? = null,
    val accountNumberPattern: String? = null,
    val amountPattern: String? = null,
    val dueDatePattern: String? = null,
    val invoiceNumberPattern: String? = null,
    val billingPeriodPattern: String? = null,
    val detectorKeyword: String? = null,
    val sampleFileText: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)

@Serializable
data class OcrBatch(
    val id: String,
    val uploadedBy: String? = null,
    val providerId: String? = null,
    val billingMonth: String? = null,       // "YYYY-MM"
    val fileName: String? = null,
    val sourceId: String? = null,
    val method: String? = null,             // gemini-ocr | simulated | ...
    val usedTemplate: String? = null,
    val status: String,                     // extracted | reconciled | committed
    val createdAt: Instant,
)

@Serializable
data class OcrExtractedRow(
    val id: String,
    val batchId: String,
    val accountNumber: String? = null,
    val amount: Money? = null,
    val outstandingBalance: Money? = null,
    val dueDate: LocalDate? = null,
    val ispName: String? = null,
    val storeName: String? = null,
    val invoiceNumber: String? = null,
    val billNumber: String? = null,
    val billingPeriod: String? = null,
    val matchedAccountId: String? = null,
    val reconciled: Boolean = false,
)

/** Body for POST /ocr/extract — creates a batch and runs extraction on the source. */
@Serializable
data class TriggerOcrRequest(
    val sourceId: String? = null,
    val providerId: String? = null,
    val billingMonth: String? = null,       // "YYYY-MM"
    val fileName: String? = null,
    val templateKey: String? = null,        // config_key or "auto"
    val sampleText: String? = null,
)

/** Body for POST/PUT /ocr/templates. */
@Serializable
data class OcrTemplateUpsertRequest(
    val configKey: String,
    val formatName: String,
    val providerId: String? = null,
    val ispName: String? = null,
    val aiPromptInstruction: String? = null,
    val accountNumberPattern: String? = null,
    val amountPattern: String? = null,
    val dueDatePattern: String? = null,
    val invoiceNumberPattern: String? = null,
    val billingPeriodPattern: String? = null,
    val detectorKeyword: String? = null,
    val sampleFileText: String? = null,
)

// =====================================================================
//  Generic envelopes
// =====================================================================
/**
 * Unified success envelope wrapping every JSON response (API_CONTRACT).
 * No field carries a default, so `result`/`message`/`status` always serialize
 * under the app's bare kotlinx `json()` (encodeDefaults=false); `data` is
 * nullable and emitted as `null` when absent (explicitNulls=true). Always build
 * it through the reified `call.ok(...)` / `call.created(...)` helpers so the
 * generic `T` serializer is resolved at the call site.
 */
@Serializable
data class ApiResponse<T>(
    val result: String,
    val message: String,
    val status: String,
    val data: T?,
)

/**
 * Unified error envelope (contract shape: `data` is always null). A concrete
 * type — not `ApiResponse<Nothing>` — so the error boundary needs no generic
 * serializer. No field carries a default: the app's bare `json()` uses
 * `encodeDefaults=false`, which would drop any defaulted field from the wire.
 */
@Serializable
data class ErrorEnvelope(
    val result: String,
    val status: String,
    val message: String,
    val data: String?,
)

/** One page of a cursor-paginated list; `nextCursor` is null on the last page. */
@Serializable
data class CursorPage<T>(val items: List<T>, val nextCursor: String?)

// =====================================================================
//  Account change requests (secretary submits, manager approves)
// =====================================================================
@Serializable
enum class AccountChangeRequestStatus {
    @SerialName("pending")   PENDING,
    @SerialName("approved")  APPROVED,
    @SerialName("rejected")  REJECTED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class AccountChangeRequest(
    val id: String,
    val accountId: String,
    val submittedById: String,
    val status: AccountChangeRequestStatus,
    val accountNumberNew: String? = null,
    val installationDateNew: LocalDate? = null,
    val rateNew: Money? = null,
    val providerIdNew: String? = null,
    val circuitIdNew: String? = null,
    val planNameNew: String? = null,
    val proofAttachmentId: String? = null,
    val approvedById: String? = null,
    val approvedAt: Instant? = null,
    val rejectedReason: String? = null,
    val cancelledAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)

@Serializable
data class SubmitAccountChangeRequestInput(
    val accountNumber: String? = null,
    val installationDate: LocalDate? = null,
    val rate: Money? = null,
    val providerId: String? = null,
    val circuitId: String? = null,
    val planName: String? = null,
    val proofAttachmentId: String? = null,
)

@Serializable
data class FieldDiff(
    val field: String,
    val currentValue: String?,
    val proposedValue: String?,
)

@Serializable
data class AccountChangeRequestWithDiff(
    val request: AccountChangeRequest,
    val diff: List<FieldDiff>,
)

// =====================================================================
//  Account export (denormalized row for Excel generation)
// =====================================================================
/**
 * A denormalized account row carrying store/provider names for the Excel export.
 * Joins Accounts + Stores + Providers so the spreadsheet shows human-readable
 * labels instead of UUIDs.
 */
@Serializable
data class AccountExportRow(
    val accountNumber: String,
    val circuitId: String?,
    val providerName: String,
    val branchCode: String,
    val storeName: String,
    val planName: String?,
    val serviceType: String?,
    val speed: String?,
    val rate: Money,
    val installationDate: LocalDate,
    val contractStartDate: LocalDate?,
    val contractEndDate: LocalDate?,
    val status: AccountStatus,
)
