package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.AccountAttachments
import com.puregoldbe.ibms.adapter.db.Accounts
import com.puregoldbe.ibms.adapter.db.Attachments
import com.puregoldbe.ibms.adapter.db.Providers
import com.puregoldbe.ibms.adapter.db.Stores
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.valueobject.toMoney
import com.puregoldbe.ibms.domain.valueobject.toMoneyString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

class ExposedAccountRepository : AccountRepository {

    override fun findById(id: String): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        return Accounts.selectAll().where { Accounts.id eq uuid }
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

    override fun existsByProviderAndNumber(providerId: String, accountNumber: String): Boolean =
        Accounts.selectAll()
            .where {
                (Accounts.providerId eq providerId.toUuid()) and
                    (Accounts.accountNumber eq accountNumber) and
                    (Accounts.status notInList listOf(AccountStatus.TRANSFERRED, AccountStatus.TERMINATED))
            }
            .count() > 0

    override fun create(input: AccountUpsertRequest, createdBy: String?): Account {
        val newId = Accounts.insertAndGetId { row ->
            row[Accounts.accountNumber] = input.accountNumber
            input.circuitId?.let { row[Accounts.circuitId] = it }
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
            if (createdBy != null) row[Accounts.createdBy] = EntityID(createdBy.toUuid(), Users)
        }.value

        input.subscriptionProofIds.forEach { pid ->
            AccountAttachments.insert {
                it[AccountAttachments.accountId] = EntityID(newId, Accounts)
                it[AccountAttachments.attachmentId] = EntityID(pid.toUuid(), Attachments)
            }
        }
        return findById(newId.toString())!!
    }

    override fun update(id: String, input: AccountUpsertRequest): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        val updated = Accounts.update({ Accounts.id eq uuid }) { row ->
            row[Accounts.accountNumber] = input.accountNumber
            row[Accounts.circuitId] = input.circuitId
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

    override fun updateStatus(id: String, status: AccountStatus): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = Accounts.update({ Accounts.id eq uuid }) { it[Accounts.status] = status }
        return if (n == 0) null else findById(id)
    }

    override fun markTerminationRequested(id: String, at: kotlinx.datetime.Instant): Account? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = Accounts.update({ Accounts.id eq uuid }) {
            it[Accounts.status] = AccountStatus.TERMINATION_REQUESTED
            it[Accounts.terminationRequestedAt] = at.jt()
        }
        return if (n == 0) null else findById(id)
    }

    private fun proofIdsFor(accountId: UUID): List<String> =
        AccountAttachments.selectAll()
            .where { AccountAttachments.accountId eq accountId }
            .map { it[AccountAttachments.attachmentId].value.toString() }

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
        subscriptionProofIds = proofIds,
        createdAt = this[Accounts.createdAt].kx(),
        updatedAt = this[Accounts.updatedAt].kx(),
    )
}
