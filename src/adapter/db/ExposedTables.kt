@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.puregoldbe.ibms.adapter.db

import com.puregoldbe.ibms.domain.model.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp
import org.postgresql.util.PGobject

/**
 * Exposed table mappings, matching resources/db/migration/V1__init.sql 1:1.
 * Flyway owns the DDL; these objects are for queries only (never SchemaUtils.create
 * in production) so the database stays the single source of truth.
 *
 * Timestamps/dates use the java-time module (java.time.Instant / LocalDate); repos
 * convert to kotlinx types at the mapping boundary to match the wire DTOs.
 */

// ---- Native Postgres enum support --------------------------------------------
// A plain String won't implicitly cast to a PG enum column, so writes are wrapped
// in a PGobject tagged with the enum type name; reads come back as the text label.
private class PGEnumValue<T : Enum<T>>(pgTypeName: String, enumValue: T?) : PGobject() {
    init {
        value = enumValue?.name?.lowercase()
        type = pgTypeName
    }
}

private inline fun <reified T : Enum<T>> Table.pgEnum(columnName: String, pgTypeName: String) =
    customEnumeration(
        columnName, pgTypeName,
        fromDb = { enumValueOf<T>(it.toString().uppercase()) },
        toDb = { PGEnumValue(pgTypeName, it) },
    )

object Users : UUIDTable("users") {
    val googleSub      = text("google_sub").uniqueIndex().nullable()
    val email          = text("email").uniqueIndex()
    val name           = text("name")
    val firstName      = text("first_name").nullable()
    val middleInitial  = text("middle_initial").nullable()
    val lastName       = text("last_name").nullable()
    val employeeNumber = text("employee_number").nullable()
    val role           = pgEnum<UserRole>("role", "user_role")
    val legacyId       = text("legacy_id").uniqueIndex().nullable()
    val createdAt      = timestamp("created_at")
    val updatedAt      = timestamp("updated_at")
}

object Providers : UUIDTable("providers") {
    val name               = text("name").uniqueIndex()
    val paymentScheduleDay = short("payment_schedule_day")
    val status             = pgEnum<ProviderStatus>("status", "provider_status")
    val deactivatedAt      = timestamp("deactivated_at").nullable()
    val legacyId           = text("legacy_id").uniqueIndex().nullable()
    val createdAt          = timestamp("created_at")
    val updatedAt          = timestamp("updated_at")
}

object Attachments : UUIDTable("attachments") {
    val purpose     = pgEnum<AttachmentPurpose>("purpose", "attachment_purpose")
    val entityType  = text("entity_type").nullable()
    val entityId    = uuid("entity_id").nullable()
    val storageKey  = text("storage_key")
    val contentType = text("content_type").nullable()
    val sizeBytes   = long("size_bytes").nullable()
    val uploadedBy  = reference("uploaded_by", Users).nullable()
    val legacyUrl   = text("legacy_url").nullable()
    val createdAt   = timestamp("created_at")
}

object Stores : UUIDTable("stores") {
    val storeType             = pgEnum<StoreType>("store_type", "store_type")
    val branchCode            = text("branch_code").uniqueIndex()
    val name                  = text("name")
    val region                = text("region").nullable()
    val province              = text("province").nullable()
    val city                  = text("city").nullable()
    val barangay              = text("barangay").nullable()
    val postal                = text("postal").nullable()
    val status                = pgEnum<StoreStatus>("status", "store_status")
    val closedReason          = text("closed_reason").nullable()
    val proofOfInstallationId = reference("proof_of_installation_id", Attachments)
    val proofOfClosureId      = reference("proof_of_closure_id", Attachments).nullable()
    val createdBy             = reference("created_by", Users).nullable()
    val legacyId              = text("legacy_id").uniqueIndex().nullable()
    val createdAt             = timestamp("created_at")
    val updatedAt             = timestamp("updated_at")
}

object Accounts : UUIDTable("accounts") {
    val accountNumber           = text("account_number")
    val circuitId               = text("circuit_id").nullable()
    val providerId              = reference("provider_id", Providers)
    val storeId                 = reference("store_id", Stores)
    val planName                = text("plan_name").nullable()
    val serviceType             = text("service_type").nullable()
    val speed                   = text("speed").nullable()
    val contractDurationMonths  = integer("contract_duration_months").nullable()
    val contractStartDate       = date("contract_start_date").nullable()
    val contractEndDate         = date("contract_end_date").nullable()
    val notes                   = text("notes").nullable()
    val installationFee         = decimal("installation_fee", 14, 2).nullable()
    val rate                    = decimal("rate", 14, 2)
    val installationDate        = date("installation_date")
    val billingPeriodLabel      = text("billing_period_label").nullable()
    val isProrated              = bool("is_prorated")
    val status                  = pgEnum<AccountStatus>("status", "account_status")
    val terminationRequestedAt  = timestamp("termination_requested_at").nullable()
    val createdBy               = reference("created_by", Users).nullable()
    val legacyId                = text("legacy_id").uniqueIndex().nullable()
    val createdAt               = timestamp("created_at")
    val updatedAt               = timestamp("updated_at")
    // Uniqueness of (provider_id, account_number) is enforced in the DB by a
    // PARTIAL unique index (active accounts only) — see V4 migration. Flyway owns
    // DDL, so it is not declared here.
}

object AccountAttachments : Table("account_attachments") {
    val accountId    = reference("account_id", Accounts)
    val attachmentId = reference("attachment_id", Attachments)
    override val primaryKey = PrimaryKey(accountId, attachmentId)
}

object InvoiceSequences : Table("invoice_sequences") {
    val providerId   = reference("provider_id", Providers)
    val prefix       = text("prefix")
    val currentValue = integer("current_value")
    override val primaryKey = PrimaryKey(providerId)
}

object TopSheets : UUIDTable("topsheets") {
    val invoiceNumber        = text("invoice_number").uniqueIndex()
    val billingPeriod        = text("billing_period")
    val providerId           = reference("provider_id", Providers).nullable()
    val providerName         = text("provider_name").nullable()
    val accountCount         = integer("account_count")
    val totalAmount          = decimal("total_amount", 14, 2)
    val status               = pgEnum<TopSheetStatus>("status", "topsheet_status")
    val compilerId           = reference("compiler_id", Users)
    val approvedByFinanceId  = reference("approved_by_finance_id", Users).nullable()
    val approvedAt           = timestamp("approved_at").nullable()
    val paidAt               = timestamp("paid_at").nullable()
    val legacyId             = text("legacy_id").uniqueIndex().nullable()
    val compilationDate      = timestamp("compilation_date")
    val createdAt            = timestamp("created_at")
    val updatedAt            = timestamp("updated_at")
}

object TopSheetDetails : UUIDTable("topsheet_details") {
    val topsheetId     = reference("topsheet_id", TopSheets)
    val accountId      = reference("account_id", Accounts)
    val billingPeriod  = text("billing_period")
    val proratedAmount = decimal("prorated_amount", 14, 2)
    val fullAmount     = decimal("full_amount", 14, 2)
    val status         = pgEnum<TopSheetLineStatus>("status", "topsheet_line_status")
    val branchCode     = text("branch_code").nullable()
    val storeName      = text("store_name").nullable()
    val circuitId      = text("circuit_id").nullable()
    val accountNumber  = text("account_number").nullable()
    val accountStatus  = text("account_status").nullable()
    val createdAt      = timestamp("created_at")
}

object Transfers : UUIDTable("transfers") {
    val oldStoreId    = reference("old_store_id", Stores)
    val newStoreId    = reference("new_store_id", Stores)
    val oldAccountId  = reference("old_account_id", Accounts)
    val newAccountId  = reference("new_account_id", Accounts)
    val proofId       = reference("proof_id", Attachments).nullable()
    val requestedById = reference("requested_by_id", Users)
    val transferDate  = timestamp("transfer_date")
    val legacyId      = text("legacy_id").uniqueIndex().nullable()
    val createdAt     = timestamp("created_at")
}

object Activities : UUIDTable("activities") {
    val userId     = reference("user_id", Users).nullable()
    val userEmail  = text("user_email").nullable()
    val userName   = text("user_name").nullable()
    val action     = text("action")
    val details    = text("details").nullable()
    val entityType = text("entity_type").nullable()
    val entityId   = uuid("entity_id").nullable()
    val createdAt  = timestamp("created_at")
}

object OcrTemplates : UUIDTable("ocr_templates") {
    val configKey            = text("config_key").uniqueIndex()
    val providerId           = reference("provider_id", Providers).nullable()
    val ispName              = text("isp_name").nullable()
    val formatName           = text("format_name")
    val aiPromptInstruction  = text("ai_prompt_instruction").nullable()
    val accountNumberPattern = text("account_number_pattern").nullable()
    val amountPattern        = text("amount_pattern").nullable()
    val dueDatePattern       = text("due_date_pattern").nullable()
    val invoiceNumberPattern = text("invoice_number_pattern").nullable()
    val billingPeriodPattern = text("billing_period_pattern").nullable()
    val detectorKeyword      = text("detector_keyword").nullable()
    val sampleFileText       = text("sample_file_text").nullable()
    val createdAt            = timestamp("created_at")
    val updatedAt            = timestamp("updated_at")
}
