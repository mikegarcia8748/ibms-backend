package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.Accounts
import com.puregoldbe.ibms.adapter.db.Attachments
import com.puregoldbe.ibms.adapter.db.Stores
import com.puregoldbe.ibms.adapter.db.Transfers
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.domain.model.TransferRecord
import com.puregoldbe.ibms.domain.port.TransferRepository
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*

class ExposedTransferRepository : TransferRepository {

    override fun create(
        oldStoreId: String,
        newStoreId: String,
        oldAccountId: String,
        newAccountId: String,
        proofId: String?,
        requestedById: String,
        at: Instant,
    ): TransferRecord {
        val id = Transfers.insertAndGetId { row ->
            row[Transfers.oldStoreId] = EntityID(oldStoreId.toUuid(), Stores)
            row[Transfers.newStoreId] = EntityID(newStoreId.toUuid(), Stores)
            row[Transfers.oldAccountId] = EntityID(oldAccountId.toUuid(), Accounts)
            row[Transfers.newAccountId] = EntityID(newAccountId.toUuid(), Accounts)
            if (proofId != null) row[Transfers.proofId] = EntityID(proofId.toUuid(), Attachments)
            row[Transfers.requestedById] = EntityID(requestedById.toUuid(), Users)
            row[Transfers.transferDate] = at.jt()
        }.value
        return Transfers.selectAll().where { Transfers.id eq id }.map { it.toTransfer() }.single()
    }

    private fun ResultRow.toTransfer() = TransferRecord(
        id = this[Transfers.id].value.toString(),
        oldStoreId = this[Transfers.oldStoreId].value.toString(),
        newStoreId = this[Transfers.newStoreId].value.toString(),
        oldAccountId = this[Transfers.oldAccountId].value.toString(),
        newAccountId = this[Transfers.newAccountId].value.toString(),
        proofId = this[Transfers.proofId]?.value?.toString(),
        requestedById = this[Transfers.requestedById].value.toString(),
        transferDate = this[Transfers.transferDate].kx(),
    )
}
