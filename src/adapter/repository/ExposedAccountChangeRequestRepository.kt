package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.AccountChangeRequests
import com.puregoldbe.ibms.adapter.db.Accounts
import com.puregoldbe.ibms.adapter.db.Attachments
import com.puregoldbe.ibms.adapter.db.Providers
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.keysetAfter
import com.puregoldbe.ibms.adapter.db.keysetAnchor
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toCursorPage
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AccountChangeRequest
import com.puregoldbe.ibms.domain.model.AccountChangeRequestStatus
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.SubmitAccountChangeRequestInput
import com.puregoldbe.ibms.domain.port.AccountChangeRequestRepository
import com.puregoldbe.ibms.domain.valueobject.toMoney
import com.puregoldbe.ibms.domain.valueobject.toMoneyString
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*

class ExposedAccountChangeRequestRepository : AccountChangeRequestRepository {

    override fun findById(id: String): AccountChangeRequest? {
        val uuid = id.toUuidOrNull() ?: return null
        return AccountChangeRequests.selectAll().where { AccountChangeRequests.id eq uuid }
            .map { it.toAccountChangeRequest() }
            .singleOrNull()
    }

    override fun findPendingByAccountId(accountId: String): AccountChangeRequest? {
        val uuid = accountId.toUuidOrNull() ?: return null
        return AccountChangeRequests.selectAll()
            .where {
                (AccountChangeRequests.accountId eq uuid) and
                    (AccountChangeRequests.status eq AccountChangeRequestStatus.PENDING)
            }
            .map { it.toAccountChangeRequest() }
            .singleOrNull()
    }

    override fun page(
        accountId: String?,
        submittedById: String?,
        status: AccountChangeRequestStatus?,
        cursor: String?,
        limit: Int,
    ): CursorPage<AccountChangeRequest> {
        val anchor = AccountChangeRequests.keysetAnchor(AccountChangeRequests.createdAt, cursor)
        return AccountChangeRequests.selectAll()
            .apply { if (accountId != null) andWhere { AccountChangeRequests.accountId eq accountId.toUuid() } }
            .apply { if (submittedById != null) andWhere { AccountChangeRequests.submittedById eq submittedById.toUuid() } }
            .apply { if (status != null) andWhere { AccountChangeRequests.status eq status } }
            .apply { if (anchor != null) andWhere { keysetAfter(AccountChangeRequests, AccountChangeRequests.createdAt, anchor) } }
            .orderBy(AccountChangeRequests.createdAt to SortOrder.ASC, AccountChangeRequests.id to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toAccountChangeRequest() }
            .toCursorPage(limit) { it.id }
    }

    override fun create(
        accountId: String,
        submittedById: String,
        input: SubmitAccountChangeRequestInput,
    ): AccountChangeRequest {
        val newId = try {
            AccountChangeRequests.insertAndGetId { row ->
                row[AccountChangeRequests.accountId] = EntityID(accountId.toUuid(), Accounts)
                row[AccountChangeRequests.submittedById] = EntityID(submittedById.toUuid(), Users)
                row[AccountChangeRequests.status] = AccountChangeRequestStatus.PENDING
                input.accountNumber?.let { row[AccountChangeRequests.accountNumberNew] = it }
                input.installationDate?.let { row[AccountChangeRequests.installationDateNew] = it.jt() }
                input.rate?.let { row[AccountChangeRequests.rateNew] = it.toMoney() }
                input.providerId?.let { row[AccountChangeRequests.providerIdNew] = EntityID(it.toUuid(), Providers) }
                input.circuitId?.let { row[AccountChangeRequests.circuitIdNew] = it }
                input.planName?.let { row[AccountChangeRequests.planNameNew] = it }
                input.proofAttachmentId?.let { row[AccountChangeRequests.proofAttachmentId] = EntityID(it.toUuid(), Attachments) }
            }.value
        } catch (e: org.jetbrains.exposed.v1.exceptions.ExposedSQLException) {
            if (e.cause is org.postgresql.util.PSQLException &&
                (e.cause as org.postgresql.util.PSQLException).sqlState == "23505"
            ) {
                throw DomainError.Conflict("another change request is already pending for this account")
            }
            throw e
        }
        return findById(newId.toString())!!
    }

    override fun approve(id: String, approverId: String, at: Instant): AccountChangeRequest? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = AccountChangeRequests.update({ AccountChangeRequests.id eq uuid }) {
            it[AccountChangeRequests.status] = AccountChangeRequestStatus.APPROVED
            it[AccountChangeRequests.approvedById] = EntityID(approverId.toUuid(), Users)
            it[AccountChangeRequests.approvedAt] = at.jt()
        }
        return if (n == 0) null else findById(id)
    }

    override fun reject(id: String, reason: String, at: Instant): AccountChangeRequest? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = AccountChangeRequests.update({ AccountChangeRequests.id eq uuid }) {
            it[AccountChangeRequests.status] = AccountChangeRequestStatus.REJECTED
            it[AccountChangeRequests.rejectedReason] = reason
        }
        return if (n == 0) null else findById(id)
    }

    override fun cancel(id: String, at: Instant): AccountChangeRequest? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = AccountChangeRequests.update({ AccountChangeRequests.id eq uuid }) {
            it[AccountChangeRequests.status] = AccountChangeRequestStatus.CANCELLED
            it[AccountChangeRequests.cancelledAt] = at.jt()
        }
        return if (n == 0) null else findById(id)
    }

    private fun ResultRow.toAccountChangeRequest() = AccountChangeRequest(
        id = this[AccountChangeRequests.id].value.toString(),
        accountId = this[AccountChangeRequests.accountId].value.toString(),
        submittedById = this[AccountChangeRequests.submittedById].value.toString(),
        status = this[AccountChangeRequests.status],
        accountNumberNew = this[AccountChangeRequests.accountNumberNew],
        installationDateNew = this[AccountChangeRequests.installationDateNew]?.kx(),
        rateNew = this[AccountChangeRequests.rateNew]?.toMoneyString(),
        providerIdNew = this[AccountChangeRequests.providerIdNew]?.value?.toString(),
        circuitIdNew = this[AccountChangeRequests.circuitIdNew],
        planNameNew = this[AccountChangeRequests.planNameNew],
        proofAttachmentId = this[AccountChangeRequests.proofAttachmentId]?.value?.toString(),
        approvedById = this[AccountChangeRequests.approvedById]?.value?.toString(),
        approvedAt = this[AccountChangeRequests.approvedAt]?.kx(),
        rejectedReason = this[AccountChangeRequests.rejectedReason],
        cancelledAt = this[AccountChangeRequests.cancelledAt]?.kx(),
        createdAt = this[AccountChangeRequests.createdAt].kx(),
        updatedAt = this[AccountChangeRequests.updatedAt].kx(),
    )
}
