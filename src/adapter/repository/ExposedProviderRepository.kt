package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.Providers
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.keysetAfter
import com.puregoldbe.ibms.adapter.db.keysetAnchor
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toCursorPage
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.Provider
import com.puregoldbe.ibms.domain.model.ProviderStatus
import com.puregoldbe.ibms.domain.port.ProviderRepository
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

class ExposedProviderRepository : ProviderRepository {

    override fun findById(id: String): Provider? {
        val uuid = id.toUuidOrNull() ?: return null
        return Providers.selectAll().where { Providers.id eq uuid }.map { it.toProvider() }.singleOrNull()
    }

    override fun list(status: ProviderStatus?): List<Provider> =
        Providers.selectAll()
            .apply { if (status != null) andWhere { Providers.status eq status } }
            .orderBy(Providers.name)
            .map { it.toProvider() }

    override fun page(status: ProviderStatus?, cursor: String?, limit: Int): CursorPage<Provider> {
        val anchor = Providers.keysetAnchor(Providers.createdAt, cursor)
        return Providers.selectAll()
            .apply { if (status != null) andWhere { Providers.status eq status } }
            .apply { if (anchor != null) andWhere { keysetAfter(Providers, Providers.createdAt, anchor) } }
            .orderBy(Providers.createdAt to SortOrder.ASC, Providers.id to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toProvider() }
            .toCursorPage(limit) { it.id }
    }

    override fun create(name: String, paymentScheduleDay: Int): Provider {
        val id = Providers.insertAndGetId {
            it[Providers.name] = name
            it[Providers.paymentScheduleDay] = paymentScheduleDay.toShort()
        }.value
        return findById(id.toString())!!
    }

    override fun updateDetails(id: String, name: String?, paymentScheduleDay: Int?): Provider? {
        val uuid = id.toUuidOrNull() ?: return null
        if (name == null && paymentScheduleDay == null) return findById(id)
        val updated = Providers.update({ Providers.id eq uuid }) {
            if (name != null) it[Providers.name] = name
            if (paymentScheduleDay != null) it[Providers.paymentScheduleDay] = paymentScheduleDay.toShort()
        }
        return if (updated == 0) null else findById(id)
    }

    override fun deactivate(id: String, at: Instant): Provider? {
        val uuid = id.toUuidOrNull() ?: return null
        val updated = Providers.update({ Providers.id eq uuid }) {
            it[Providers.status] = ProviderStatus.INACTIVE
            it[Providers.deactivatedAt] = at.jt()
        }
        return if (updated == 0) null else findById(id)
    }

    private fun ResultRow.toProvider() = Provider(
        id = this[Providers.id].value.toString(),
        name = this[Providers.name],
        paymentScheduleDay = this[Providers.paymentScheduleDay].toInt(),
        status = this[Providers.status],
        deactivatedAt = this[Providers.deactivatedAt]?.kx(),
        createdAt = this[Providers.createdAt].kx(),
        updatedAt = this[Providers.updatedAt].kx(),
    )
}
