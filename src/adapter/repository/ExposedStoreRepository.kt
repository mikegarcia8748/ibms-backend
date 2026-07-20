package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.Attachments
import com.puregoldbe.ibms.adapter.db.Stores
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.keysetAfter
import com.puregoldbe.ibms.adapter.db.keysetAnchor
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toCursorPage
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.model.StoreUpsertRequest
import com.puregoldbe.ibms.domain.port.StoreRepository
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*

class ExposedStoreRepository : StoreRepository {

    override fun findById(id: String): Store? {
        val uuid = id.toUuidOrNull() ?: return null
        return Stores.selectAll().where { Stores.id eq uuid }.map { it.toStore() }.singleOrNull()
    }

    override fun list(status: StoreStatus?, query: String?): List<Store> =
        Stores.selectAll()
            .apply { if (status != null) andWhere { Stores.status eq status } }
            .apply {
                if (!query.isNullOrBlank()) {
                    andWhere { (Stores.name like "%$query%") or (Stores.branchCode like "%$query%") }
                }
            }
            .orderBy(Stores.name)
            .map { it.toStore() }

    override fun page(status: StoreStatus?, query: String?, cursor: String?, limit: Int): CursorPage<Store> {
        val anchor = Stores.keysetAnchor(Stores.createdAt, cursor)
        return Stores.selectAll()
            .apply { if (status != null) andWhere { Stores.status eq status } }
            .apply {
                if (!query.isNullOrBlank()) {
                    andWhere { (Stores.name like "%$query%") or (Stores.branchCode like "%$query%") }
                }
            }
            .apply { if (anchor != null) andWhere { keysetAfter(Stores, Stores.createdAt, anchor) } }
            .orderBy(Stores.createdAt to SortOrder.ASC, Stores.id to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toStore() }
            .toCursorPage(limit) { it.id }
    }

    override fun existsByBranchCode(branchCode: String): Boolean =
        Stores.selectAll().where { Stores.branchCode eq branchCode }.count() > 0

    override fun create(input: StoreUpsertRequest, createdBy: String?): Store {
        val newId = Stores.insertAndGetId {
            it[Stores.storeType] = input.storeType
            it[Stores.branchCode] = input.branchCode
            it[Stores.name] = input.name
            it[Stores.region] = input.region
            it[Stores.province] = input.province
            it[Stores.city] = input.city
            it[Stores.barangay] = input.barangay
            it[Stores.postal] = input.postal
            it[Stores.proofOfInstallationId] = EntityID(input.proofOfInstallationId.toUuid(), Attachments)
            if (createdBy != null) it[Stores.createdBy] = EntityID(createdBy.toUuid(), Users)
        }.value
        return findById(newId.toString())!!
    }

    override fun update(id: String, input: StoreUpsertRequest): Store? {
        val uuid = id.toUuidOrNull() ?: return null
        val updated = Stores.update({ Stores.id eq uuid }) {
            it[Stores.storeType] = input.storeType
            it[Stores.branchCode] = input.branchCode
            it[Stores.name] = input.name
            it[Stores.region] = input.region
            it[Stores.province] = input.province
            it[Stores.city] = input.city
            it[Stores.barangay] = input.barangay
            it[Stores.postal] = input.postal
            it[Stores.proofOfInstallationId] = EntityID(input.proofOfInstallationId.toUuid(), Attachments)
        }
        return if (updated == 0) null else findById(id)
    }

    override fun close(id: String, reason: String, proofOfClosureId: String, at: Instant): Store? {
        val uuid = id.toUuidOrNull() ?: return null
        val updated = Stores.update({ Stores.id eq uuid }) {
            it[Stores.status] = StoreStatus.CLOSED
            it[Stores.closedReason] = reason
            it[Stores.proofOfClosureId] = EntityID(proofOfClosureId.toUuid(), Attachments)
        }
        return if (updated == 0) null else findById(id)
    }

    private fun ResultRow.toStore() = Store(
        id = this[Stores.id].value.toString(),
        storeType = this[Stores.storeType],
        branchCode = this[Stores.branchCode],
        name = this[Stores.name],
        region = this[Stores.region],
        province = this[Stores.province],
        city = this[Stores.city],
        barangay = this[Stores.barangay],
        postal = this[Stores.postal],
        status = this[Stores.status],
        closedReason = this[Stores.closedReason],
        proofOfInstallationId = this[Stores.proofOfInstallationId].value.toString(),
        proofOfClosureId = this[Stores.proofOfClosureId]?.value?.toString(),
        createdAt = this[Stores.createdAt].kx(),
        updatedAt = this[Stores.updatedAt].kx(),
    )
}
