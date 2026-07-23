package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.Accounts
import com.puregoldbe.ibms.adapter.db.Providers
import com.puregoldbe.ibms.adapter.db.TopSheetDetails
import com.puregoldbe.ibms.adapter.db.TopSheets
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.keysetAfter
import com.puregoldbe.ibms.adapter.db.keysetAnchor
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toCursorPage
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetLineStatus
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.NewTopSheetLine
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.domain.valueobject.toMoney
import com.puregoldbe.ibms.domain.valueobject.toMoneyString
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*

class ExposedTopSheetRepository : TopSheetRepository {

    override fun create(
        invoiceNumber: String,
        billingPeriod: String,
        providerId: String?,
        providerName: String?,
        accountCount: Int,
        totalAmount: String,
        compilerId: String,
    ): TopSheet {
        val id = TopSheets.insertAndGetId { row ->
            row[TopSheets.invoiceNumber] = invoiceNumber
            row[TopSheets.billingPeriod] = billingPeriod
            if (providerId != null) row[TopSheets.providerId] = EntityID(providerId.toUuid(), Providers)
            if (providerName != null) row[TopSheets.providerName] = providerName
            row[TopSheets.accountCount] = accountCount
            row[TopSheets.totalAmount] = totalAmount.toMoney()
            row[TopSheets.status] = TopSheetStatus.COMPILED
            row[TopSheets.compilerId] = EntityID(compilerId.toUuid(), Users)
        }.value
        return findById(id.toString())!!
    }

    override fun addLine(topsheetId: String, line: NewTopSheetLine) {
        TopSheetDetails.insert { row ->
            row[TopSheetDetails.topsheetId] = EntityID(topsheetId.toUuid(), TopSheets)
            row[TopSheetDetails.accountId] = EntityID(line.accountId.toUuid(), Accounts)
            row[TopSheetDetails.billingPeriod] = line.billingPeriod
            row[TopSheetDetails.proratedAmount] = line.proratedAmount.toMoney()
            row[TopSheetDetails.fullAmount] = line.fullAmount.toMoney()
            row[TopSheetDetails.status] = TopSheetLineStatus.BILLED
            line.branchCode?.let { row[TopSheetDetails.branchCode] = it }
            line.storeName?.let { row[TopSheetDetails.storeName] = it }
            line.circuitId?.let { row[TopSheetDetails.circuitId] = it }
            line.accountNumber?.let { row[TopSheetDetails.accountNumber] = it }
            line.accountStatus?.let { row[TopSheetDetails.accountStatus] = it }
            line.rfpNumber?.let { row[TopSheetDetails.rfpNumber] = it }
            line.rfpSortOrder?.let { row[TopSheetDetails.rfpSortOrder] = it.toShort() }
            row[TopSheetDetails.arrearsAmount] = line.arrearsAmount.toMoney()
            row[TopSheetDetails.arrearsPeriods] = line.arrearsPeriods.takeIf { it.isNotEmpty() }?.joinToString(",")
        }
    }

    override fun findById(id: String): TopSheet? {
        val uuid = id.toUuidOrNull() ?: return null
        return TopSheets.selectAll().where { TopSheets.id eq uuid }.map { it.toTopSheet() }.singleOrNull()
    }

    override fun list(providerId: String?, billingPeriod: String?, status: TopSheetStatus?): List<TopSheet> =
        TopSheets.selectAll()
            .apply { if (providerId != null) andWhere { TopSheets.providerId eq providerId.toUuid() } }
            .apply { if (billingPeriod != null) andWhere { TopSheets.billingPeriod eq billingPeriod } }
            .apply { if (status != null) andWhere { TopSheets.status eq status } }
            .orderBy(TopSheets.compilationDate to SortOrder.DESC)
            .map { it.toTopSheet() }

    override fun page(
        providerId: String?,
        billingPeriod: String?,
        status: TopSheetStatus?,
        cursor: String?,
        limit: Int,
    ): CursorPage<TopSheet> {
        val anchor = TopSheets.keysetAnchor(TopSheets.createdAt, cursor)
        return TopSheets.selectAll()
            .apply { if (providerId != null) andWhere { TopSheets.providerId eq providerId.toUuid() } }
            .apply { if (billingPeriod != null) andWhere { TopSheets.billingPeriod eq billingPeriod } }
            .apply { if (status != null) andWhere { TopSheets.status eq status } }
            .apply { if (anchor != null) andWhere { keysetAfter(TopSheets, TopSheets.createdAt, anchor) } }
            .orderBy(TopSheets.createdAt to SortOrder.ASC, TopSheets.id to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toTopSheet() }
            .toCursorPage(limit) { it.id }
    }

    override fun findLines(topsheetId: String): List<TopSheetDetail> {
        val uuid = topsheetId.toUuidOrNull() ?: return emptyList()
        // Contract order: rfpSortOrder ASC (== store branchCode DESC). Legacy one-shot
        // compile lines have a null rfpSortOrder, so they sort last.
        return TopSheetDetails.selectAll()
            .where { TopSheetDetails.topsheetId eq uuid }
            .orderBy(TopSheetDetails.rfpSortOrder to SortOrder.ASC_NULLS_LAST)
            .map { it.toDetail() }
    }

    override fun billedAccountIds(billingPeriod: String): Set<String> =
        TopSheetDetails.innerJoin(TopSheets)
            .selectAll()
            .where { (TopSheetDetails.billingPeriod eq billingPeriod) and (TopSheets.status neq TopSheetStatus.DRAFT) }
            .map { it[TopSheetDetails.accountId].value.toString() }
            .toSet()

    override fun billedPeriodsByAccount(providerId: String): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        TopSheetDetails.innerJoin(TopSheets)
            .selectAll()
            .where { (TopSheets.providerId eq providerId.toUuid()) and (TopSheets.status neq TopSheetStatus.DRAFT) }
            .forEach { row ->
                val acct = row[TopSheetDetails.accountId].value.toString()
                val periods = out.getOrPut(acct) { mutableSetOf() }
                periods.add(row[TopSheetDetails.billingPeriod])
                row[TopSheetDetails.arrearsPeriods]
                    ?.split(",")?.filter { it.isNotBlank() }
                    ?.let { periods.addAll(it) }
            }
        return out
    }

    override fun approve(id: String, approverId: String, at: Instant): TopSheet? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = TopSheets.update({ TopSheets.id eq uuid }) {
            it[TopSheets.status] = TopSheetStatus.APPROVED
            it[TopSheets.approvedByFinanceId] = EntityID(approverId.toUuid(), Users)
            it[TopSheets.approvedAt] = at.jt()
        }
        return if (n == 0) null else findById(id)
    }

    override fun pay(id: String, at: Instant): TopSheet? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = TopSheets.update({ TopSheets.id eq uuid }) {
            it[TopSheets.status] = TopSheetStatus.PAID
            it[TopSheets.paidAt] = at.jt()
        }
        if (n == 0) return null
        TopSheetDetails.update({ TopSheetDetails.topsheetId eq uuid }) {
            it[TopSheetDetails.status] = TopSheetLineStatus.PAID
        }
        return findById(id)
    }

    override fun createDraft(
        billingPeriod: String,
        providerId: String?,
        providerName: String?,
        accountCount: Int,
        totalAmount: String,
        batchNumber: String,
        compilerId: String,
    ): TopSheet {
        val id = TopSheets.insertAndGetId { row ->
            row[TopSheets.billingPeriod] = billingPeriod
            if (providerId != null) row[TopSheets.providerId] = EntityID(providerId.toUuid(), Providers)
            if (providerName != null) row[TopSheets.providerName] = providerName
            row[TopSheets.accountCount] = accountCount
            row[TopSheets.totalAmount] = totalAmount.toMoney()
            row[TopSheets.batchNumber] = batchNumber
            row[TopSheets.status] = TopSheetStatus.DRAFT
            row[TopSheets.compilerId] = EntityID(compilerId.toUuid(), Users)
        }.value
        return findById(id.toString())!!
    }

    override fun updateLine(detailId: String, rfpNumber: String?, proratedAmount: String?): TopSheetDetail? {
        val uuid = detailId.toUuidOrNull() ?: return null
        val n = TopSheetDetails.update({ TopSheetDetails.id eq uuid }) {
            if (rfpNumber != null) it[TopSheetDetails.rfpNumber] = rfpNumber
            if (proratedAmount != null) it[TopSheetDetails.proratedAmount] = proratedAmount.toMoney()
        }
        if (n == 0) return null
        return TopSheetDetails.selectAll().where { TopSheetDetails.id eq uuid }.map { it.toDetail() }.singleOrNull()
    }

    override fun removeLine(detailId: String): Boolean {
        val uuid = detailId.toUuidOrNull() ?: return false
        return TopSheetDetails.deleteWhere { TopSheetDetails.id eq uuid } > 0
    }

    override fun confirm(id: String, invoiceNumber: String, accountCount: Int, totalAmount: String): TopSheet? {
        val uuid = id.toUuidOrNull() ?: return null
        val n = TopSheets.update({ (TopSheets.id eq uuid) and (TopSheets.status eq TopSheetStatus.DRAFT) }) {
            it[TopSheets.status] = TopSheetStatus.COMPILED
            it[TopSheets.invoiceNumber] = invoiceNumber
            it[TopSheets.accountCount] = accountCount
            it[TopSheets.totalAmount] = totalAmount.toMoney()
        }
        return if (n == 0) null else findById(id)
    }

    private fun ResultRow.toTopSheet() = TopSheet(
        id = this[TopSheets.id].value.toString(),
        invoiceNumber = this[TopSheets.invoiceNumber],
        batchNumber = this[TopSheets.batchNumber],
        billingPeriod = this[TopSheets.billingPeriod],
        providerId = this[TopSheets.providerId]?.value?.toString(),
        providerName = this[TopSheets.providerName],
        accountCount = this[TopSheets.accountCount],
        totalAmount = this[TopSheets.totalAmount].toMoneyString(),
        status = this[TopSheets.status],
        compilerId = this[TopSheets.compilerId].value.toString(),
        approvedByFinanceId = this[TopSheets.approvedByFinanceId]?.value?.toString(),
        approvedAt = this[TopSheets.approvedAt]?.kx(),
        paidAt = this[TopSheets.paidAt]?.kx(),
        compilationDate = this[TopSheets.compilationDate].kx(),
    )

    private fun ResultRow.toDetail() = TopSheetDetail(
        id = this[TopSheetDetails.id].value.toString(),
        topsheetId = this[TopSheetDetails.topsheetId].value.toString(),
        accountId = this[TopSheetDetails.accountId].value.toString(),
        billingPeriod = this[TopSheetDetails.billingPeriod],
        proratedAmount = this[TopSheetDetails.proratedAmount].toMoneyString(),
        fullAmount = this[TopSheetDetails.fullAmount].toMoneyString(),
        status = this[TopSheetDetails.status],
        branchCode = this[TopSheetDetails.branchCode],
        storeName = this[TopSheetDetails.storeName],
        circuitId = this[TopSheetDetails.circuitId],
        accountNumber = this[TopSheetDetails.accountNumber],
        accountStatus = this[TopSheetDetails.accountStatus],
        rfpNumber = this[TopSheetDetails.rfpNumber],
        rfpSortOrder = this[TopSheetDetails.rfpSortOrder]?.toInt(),
        arrearsAmount = this[TopSheetDetails.arrearsAmount].toMoneyString(),
        arrearsPeriods = this[TopSheetDetails.arrearsPeriods]
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    )
}
