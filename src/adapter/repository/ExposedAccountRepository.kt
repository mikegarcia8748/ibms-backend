package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.AccountAttachments
import com.puregoldbe.ibms.adapter.db.Accounts
import com.puregoldbe.ibms.adapter.db.Attachments
import com.puregoldbe.ibms.adapter.db.Providers
import com.puregoldbe.ibms.adapter.db.Stores
import com.puregoldbe.ibms.adapter.db.Transfers
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.keysetAfter
import com.puregoldbe.ibms.adapter.db.keysetAnchor
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toCursorPage
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountExportRow
import com.puregoldbe.ibms.domain.model.AccountListItem
import com.puregoldbe.ibms.domain.model.AccountProofLink
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.Money
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.ProviderAccountSummary
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.port.AccountAggregate
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.service.AccountIdentityPolicy
import com.puregoldbe.ibms.domain.service.GracePeriodPolicy
import com.puregoldbe.ibms.domain.valueobject.toMoney
import com.puregoldbe.ibms.domain.valueobject.toMoneyString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import java.math.BigDecimal
import java.util.UUID

class ExposedAccountRepository : AccountRepository {

    override fun findById(id: String): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        return Accounts.selectAll().where { Accounts.id eq uuid }
            .map { it.toAccount(proofIdsFor(uuid)) }
            .singleOrNull()
    }

    override fun findByIdForUpdate(id: String): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        // The lock is taken on the accounts row itself; proofIdsFor runs a second query
        // that deliberately stays unlocked (proof links are append-only).
        return Accounts.selectAll().where { Accounts.id eq uuid }
            .forUpdate()
            .map { it.toAccount(proofIdsFor(uuid)) }
            .singleOrNull()
    }

    override fun list(storeId: String?, providerId: String?, status: AccountStatus?): List<Account> =
        Accounts.selectAll()
            .apply { if (storeId != null) andWhere { Accounts.storeId eq storeId.toUuid() } }
            .apply { if (providerId != null) andWhere { Accounts.providerId eq providerId.toUuid() } }
            .apply { if (status != null) andWhere { Accounts.status eq status } }
            .orderBy(Accounts.accountNumber)
            .map { it.toAccount(proofIdsFor(it[Accounts.id].value)) }

    override fun page(
        storeId: String?,
        providerId: String?,
        status: AccountStatus?,
        cursor: String?,
        limit: Int,
    ): CursorPage<Account> {
        val anchor = Accounts.keysetAnchor(Accounts.createdAt, cursor)
        return Accounts.selectAll()
            .apply { if (storeId != null) andWhere { Accounts.storeId eq storeId.toUuid() } }
            .apply { if (providerId != null) andWhere { Accounts.providerId eq providerId.toUuid() } }
            .apply { if (status != null) andWhere { Accounts.status eq status } }
            .apply { if (anchor != null) andWhere { keysetAfter(Accounts, Accounts.createdAt, anchor) } }
            .orderBy(Accounts.createdAt to SortOrder.ASC, Accounts.id to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toAccount(proofIdsFor(it[Accounts.id].value)) }
            .toCursorPage(limit) { it.id }
    }

    override fun findLiveByIdentity(
        storeId: String,
        providerId: String,
        accountNumber: String,
        circuitId: String?,
    ): Account? {
        val number = AccountIdentityPolicy.normalizeAccountNumber(accountNumber)
        val circuit = AccountIdentityPolicy.normalizeCircuitId(circuitId)
        return Accounts.selectAll()
            .where {
                val core = (Accounts.storeId eq storeId.toUuid()) and
                    (Accounts.providerId eq providerId.toUuid()) and
                    // Case-insensitive on purpose, unlike the DB index: the guard is meant
                    // to be strictly stricter than the constraint, so "ACC-1" and "acc-1"
                    // cannot both go live for one circuit. See AccountIdentityPolicy.
                    (Accounts.accountNumber.lowerCase() eq number.lowercase()) and
                    (Accounts.status notInList listOf(AccountStatus.TRANSFERRED, AccountStatus.INACTIVE))
                // Mirror the DB's COALESCE(circuit_id,'') unique index: a null/blank
                // circuit matches rows whose circuit is null or empty. Legacy rows written
                // before normalization may hold whitespace, so trim on the DB side too.
                if (circuit == null) {
                    core and (Accounts.circuitId.isNull() or (Accounts.circuitId.trim() eq ""))
                } else {
                    core and (Accounts.circuitId.trim().lowerCase() eq circuit.lowercase())
                }
            }
            // Ordered for determinism: the identity is unique among live rows by
            // index, so at most one row can match — but a legacy pre-normalization
            // duplicate must resolve to the same row every time it is reported.
            .orderBy(Accounts.createdAt to SortOrder.ASC, Accounts.id to SortOrder.ASC)
            // firstOrNull rather than singleOrNull so such a duplicate reports the
            // conflict instead of throwing.
            .firstOrNull()
            ?.let { it.toAccount(proofIdsFor(it[Accounts.id].value)) }
    }

    override fun create(input: AccountUpsertRequest, createdBy: String?): Account {
        val newId = Accounts.insertAndGetId { row ->
            // Normalized here as well as at the use-case boundary: this is the last gate
            // before the unique index, and a caller that forgets must not be able to
            // write a whitespace circuit the identity guard can never match again.
            row[Accounts.accountNumber] = AccountIdentityPolicy.normalizeAccountNumber(input.accountNumber)
            AccountIdentityPolicy.normalizeCircuitId(input.circuitId)?.let { row[Accounts.circuitId] = it }
            row[Accounts.providerId] = EntityID(input.providerId.toUuid(), Providers)
            row[Accounts.storeId] = EntityID(input.storeId.toUuid(), Stores)
            input.planName?.let { row[Accounts.planName] = it }
            input.serviceType?.let { row[Accounts.serviceType] = it }
            input.speed?.let { row[Accounts.speed] = it }
            input.contractDurationMonths?.let { row[Accounts.contractDurationMonths] = it }
            input.contractStartDate?.let { row[Accounts.contractStartDate] = it.jt() }
            input.contractEndDate?.let { row[Accounts.contractEndDate] = it.jt() }
            input.notes?.let { row[Accounts.notes] = it }
            input.installationFee?.let { row[Accounts.installationFee] = it.toMoney() }
            row[Accounts.rate] = input.rate.toMoney()
            row[Accounts.installationDate] = input.installationDate.jt()
            input.billingPeriodLabel?.let { row[Accounts.billingPeriodLabel] = it }
            row[Accounts.isProrated] = input.isProrated
            if (createdBy != null) row[Accounts.createdBy] = EntityID(createdBy.toUuid(), Users)
        }.value

        linkProofs(
            newId.toString(),
            input.subscriptionProofIds,
            AttachmentPurpose.SUBSCRIPTION_PROOF,
            createdBy,
        )
        return findById(newId.toString())!!
    }

    override fun update(id: String, input: AccountUpsertRequest): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        val updated = Accounts.update({ Accounts.id eq uuid }) { row ->
            row[Accounts.accountNumber] = AccountIdentityPolicy.normalizeAccountNumber(input.accountNumber)
            row[Accounts.circuitId] = AccountIdentityPolicy.normalizeCircuitId(input.circuitId)
            row[Accounts.providerId] = EntityID(input.providerId.toUuid(), Providers)
            row[Accounts.storeId] = EntityID(input.storeId.toUuid(), Stores)
            row[Accounts.planName] = input.planName
            row[Accounts.serviceType] = input.serviceType
            row[Accounts.speed] = input.speed
            row[Accounts.contractDurationMonths] = input.contractDurationMonths
            row[Accounts.contractStartDate] = input.contractStartDate?.jt()
            row[Accounts.contractEndDate] = input.contractEndDate?.jt()
            row[Accounts.notes] = input.notes
            row[Accounts.installationFee] = input.installationFee?.toMoney()
            row[Accounts.rate] = input.rate.toMoney()
            row[Accounts.installationDate] = input.installationDate.jt()
            row[Accounts.billingPeriodLabel] = input.billingPeriodLabel
        }
        return if (updated == 0) null else findById(id)
    }

    override fun updateRate(id: String, rate: Money): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        // Only `rate`. See the port doc: `update` above is a full replace and would null
        // every field the caller omits.
        val updated = Accounts.update({ Accounts.id eq uuid }) { it[Accounts.rate] = rate.toMoney() }
        return if (updated == 0) null else findById(id)
    }

    override fun listActiveByStore(storeId: String): List<Account> =
        Accounts.selectAll()
            .where { (Accounts.storeId eq storeId.toUuid()) and (Accounts.status eq AccountStatus.ACTIVE) }
            .map { it.toAccount(proofIdsFor(it[Accounts.id].value)) }

    override fun listFloating(): List<Account> =
        (Accounts innerJoin Stores).selectAll()
            .where {
                (Accounts.status eq AccountStatus.ACTIVE) and
                    (Stores.status inList listOf(StoreStatus.CLOSED, StoreStatus.INACTIVE))
            }
            .map { it.toAccount(proofIdsFor(it[Accounts.id].value)) }

    override fun listForExport(providerId: String?, status: AccountStatus?): List<AccountExportRow> =
        (Accounts innerJoin Stores innerJoin Providers).selectAll()
            .apply { if (providerId != null) andWhere { Accounts.providerId eq providerId.toUuid() } }
            .apply { if (status != null) andWhere { Accounts.status eq status } }
            .orderBy(Stores.branchCode to SortOrder.ASC, Accounts.accountNumber to SortOrder.ASC)
            .map { it.toExportRow() }

    override fun countByStatus(): Map<AccountStatus, Int> {
        val cnt = Accounts.id.count()
        return Accounts.select(Accounts.status, cnt)
            .groupBy(Accounts.status)
            .associate { it[Accounts.status] to it[cnt].toInt() }
    }

    override fun aggregate(status: AccountStatus?): AccountAggregate {
        val cnt = Accounts.id.count()
        val sum = Accounts.rate.sum()
        val row = Accounts.select(cnt, sum)
            .apply { if (status != null) andWhere { Accounts.status eq status } }
            .single()
        return AccountAggregate(
            accountCount = row[cnt].toInt(),
            totalMrc = (row[sum] ?: BigDecimal.ZERO).toMoneyString(),
        )
    }

    override fun aggregateByProvider(status: AccountStatus?): List<ProviderAccountSummary> {
        val cnt = Accounts.id.count()
        val sum = Accounts.rate.sum()
        return (Accounts innerJoin Providers)
            .select(Providers.id, Providers.name, cnt, sum)
            .apply { if (status != null) andWhere { Accounts.status eq status } }
            .groupBy(Providers.id, Providers.name)
            .orderBy(Providers.name to SortOrder.ASC)
            .map {
                ProviderAccountSummary(
                    providerId = it[Providers.id].value.toString(),
                    providerName = it[Providers.name],
                    activeAccountCount = it[cnt].toInt(),
                    activeMrc = (it[sum] ?: BigDecimal.ZERO).toMoneyString(),
                )
            }
    }

    override fun pageWithDetails(
        storeId: String?,
        providerId: String?,
        status: AccountStatus?,
        cursor: String?,
        limit: Int,
    ): CursorPage<AccountListItem> {
        val anchor = Accounts.keysetAnchor(Accounts.createdAt, cursor)
        return (Accounts innerJoin Stores innerJoin Providers).selectAll()
            .apply { if (storeId != null) andWhere { Accounts.storeId eq storeId.toUuid() } }
            .apply { if (providerId != null) andWhere { Accounts.providerId eq providerId.toUuid() } }
            .apply { if (status != null) andWhere { Accounts.status eq status } }
            .apply { if (anchor != null) andWhere { keysetAfter(Accounts, Accounts.createdAt, anchor) } }
            .orderBy(Accounts.createdAt to SortOrder.ASC, Accounts.id to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toListItem() }
            .toCursorPage(limit) { it.id }
    }

    // The expected-status predicate below is what makes these writes safe under
    // concurrency: the UPDATE matches zero rows when another transaction already moved
    // the account, so the caller gets null (-> 409) instead of overwriting the winner.
    override fun updateStatus(id: String, status: AccountStatus, expected: AccountStatus): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = Accounts.update({ (Accounts.id eq uuid) and (Accounts.status eq expected) }) {
            it[Accounts.status] = status
        }
        return if (n == 0) null else findById(id)
    }

    override fun markTerminationRequested(id: String, at: kotlinx.datetime.Instant): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = Accounts.update({ (Accounts.id eq uuid) and (Accounts.status eq AccountStatus.ACTIVE) }) {
            it[Accounts.status] = AccountStatus.TERMINATION_REQUESTED
            it[Accounts.terminationRequestedAt] = at.jt()
        }
        return if (n == 0) null else findById(id)
    }

    override fun linkProofs(
        accountId: String,
        attachmentIds: List<String>,
        purpose: AttachmentPurpose,
        linkedBy: String?,
        transferId: String?,
    ) {
        if (attachmentIds.isEmpty()) return
        val account = EntityID(accountId.toUuid(), Accounts)
        // linked_at is deliberately left to the column default: Postgres resolves now()
        // to the TRANSACTION timestamp, so every row of this call shares one value and
        // the set is recoverable as a single activity.
        attachmentIds.forEachIndexed { index, attachmentId ->
            AccountAttachments.insertIgnore {
                it[AccountAttachments.accountId] = account
                it[AccountAttachments.attachmentId] = EntityID(attachmentId.toUuid(), Attachments)
                it[AccountAttachments.purpose] = purpose
                it[AccountAttachments.sortOrder] = index.toShort()
                if (linkedBy != null) it[AccountAttachments.linkedBy] = EntityID(linkedBy.toUuid(), Users)
                if (transferId != null) it[AccountAttachments.transferId] = EntityID(transferId.toUuid(), Transfers)
            }
        }
    }

    override fun listProofs(accountId: String, purpose: AttachmentPurpose?): List<AccountProofLink> {
        val uuid = accountId.toUuidOrNull() ?: return emptyList()
        return AccountAttachments.selectAll()
            .where { AccountAttachments.accountId eq uuid }
            .apply { if (purpose != null) andWhere { AccountAttachments.purpose eq purpose } }
            .orderBy(
                AccountAttachments.linkedAt to SortOrder.DESC,
                AccountAttachments.sortOrder to SortOrder.ASC,
            )
            .map { it.toProofLink() }
    }

    override fun listProofsByTransfer(transferId: String): List<AccountProofLink> {
        val uuid = transferId.toUuidOrNull() ?: return emptyList()
        return AccountAttachments.selectAll()
            .where { AccountAttachments.transferId eq uuid }
            .orderBy(AccountAttachments.sortOrder to SortOrder.ASC)
            .map { it.toProofLink() }
    }

    private fun ResultRow.toProofLink() = AccountProofLink(
        accountId = this[AccountAttachments.accountId].value.toString(),
        attachmentId = this[AccountAttachments.attachmentId].value.toString(),
        purpose = this[AccountAttachments.purpose],
        sortOrder = this[AccountAttachments.sortOrder].toInt(),
        linkedAt = this[AccountAttachments.linkedAt].kx(),
        linkedBy = this[AccountAttachments.linkedBy]?.value?.toString(),
        transferId = this[AccountAttachments.transferId]?.value?.toString(),
    )

    override fun cancelTerminationRequested(id: String): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = Accounts.update(
            { (Accounts.id eq uuid) and (Accounts.status eq AccountStatus.TERMINATION_REQUESTED) },
        ) {
            it[Accounts.status] = AccountStatus.ACTIVE
            it[Accounts.terminationRequestedAt] = null
        }
        return if (n == 0) null else findById(id)
    }

    override fun findExpiredGrace(before: kotlinx.datetime.Instant): List<Account> =
        Accounts.selectAll()
            .where {
                (Accounts.status eq AccountStatus.TERMINATION_REQUESTED) and
                    (Accounts.terminationRequestedAt lessEq before.jt())
            }
            .map { it.toAccount(proofIdsFor(it[Accounts.id].value)) }

    /**
     * `Account.subscriptionProofIds` carries ONLY subscription proofs. Before V23 this
     * read the whole link table, so a deactivation or transfer proof surfaced here and
     * TransferAccountUseCase copied it onto the account it creates at the destination.
     */
    private fun proofIdsFor(accountId: UUID): List<String> =
        AccountAttachments.selectAll()
            .where {
                (AccountAttachments.accountId eq accountId) and
                    (AccountAttachments.purpose eq AttachmentPurpose.SUBSCRIPTION_PROOF)
            }
            .orderBy(
                AccountAttachments.linkedAt to SortOrder.ASC,
                AccountAttachments.sortOrder to SortOrder.ASC,
            )
            .map { it[AccountAttachments.attachmentId].value.toString() }

    private fun ResultRow.toExportRow() = AccountExportRow(
        accountNumber = this[Accounts.accountNumber],
        circuitId = this[Accounts.circuitId],
        providerName = this[Providers.name],
        branchCode = this[Stores.branchCode],
        storeName = this[Stores.name],
        planName = this[Accounts.planName],
        serviceType = this[Accounts.serviceType],
        speed = this[Accounts.speed],
        rate = this[Accounts.rate].toMoneyString(),
        installationDate = this[Accounts.installationDate].kx(),
        contractStartDate = this[Accounts.contractStartDate]?.kx(),
        contractEndDate = this[Accounts.contractEndDate]?.kx(),
        status = this[Accounts.status],
    )

    private fun ResultRow.toListItem() = AccountListItem(
        id = this[Accounts.id].value.toString(),
        accountNumber = this[Accounts.accountNumber],
        circuitId = this[Accounts.circuitId],
        providerId = this[Accounts.providerId].value.toString(),
        providerName = this[Providers.name],
        storeId = this[Accounts.storeId].value.toString(),
        branchCode = this[Stores.branchCode],
        storeName = this[Stores.name],
        planName = this[Accounts.planName],
        serviceType = this[Accounts.serviceType],
        speed = this[Accounts.speed],
        rate = this[Accounts.rate].toMoneyString(),
        status = this[Accounts.status],
        installationDate = this[Accounts.installationDate].kx(),
        contractStartDate = this[Accounts.contractStartDate]?.kx(),
        contractEndDate = this[Accounts.contractEndDate]?.kx(),
    )

    private fun ResultRow.toAccount(proofIds: List<String>) = Account(
        id = this[Accounts.id].value.toString(),
        accountNumber = this[Accounts.accountNumber],
        circuitId = this[Accounts.circuitId],
        providerId = this[Accounts.providerId].value.toString(),
        storeId = this[Accounts.storeId].value.toString(),
        planName = this[Accounts.planName],
        serviceType = this[Accounts.serviceType],
        speed = this[Accounts.speed],
        contractDurationMonths = this[Accounts.contractDurationMonths],
        contractStartDate = this[Accounts.contractStartDate]?.kx(),
        contractEndDate = this[Accounts.contractEndDate]?.kx(),
        notes = this[Accounts.notes],
        installationFee = this[Accounts.installationFee]?.toMoneyString(),
        rate = this[Accounts.rate].toMoneyString(),
        installationDate = this[Accounts.installationDate].kx(),
        billingPeriodLabel = this[Accounts.billingPeriodLabel],
        isProrated = this[Accounts.isProrated],
        status = this[Accounts.status],
        terminationRequestedAt = this[Accounts.terminationRequestedAt]?.kx(),
        graceEndDate = this[Accounts.terminationRequestedAt]?.kx()?.let { GracePeriodPolicy.graceEnd(it) },
        subscriptionProofIds = proofIds,
        createdAt = this[Accounts.createdAt].kx(),
        updatedAt = this[Accounts.updatedAt].kx(),
    )
}
