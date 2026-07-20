@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.Activities
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.keysetAfter
import com.puregoldbe.ibms.adapter.db.keysetAnchor
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toCursorPage
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.domain.model.Activity
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.port.ActivityRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

class ExposedActivityRepository : ActivityRepository {

    override fun record(userId: String?, action: String, entityType: String?, entityId: String?, details: String?) {
        Activities.insert {
            if (userId != null) it[Activities.userId] = EntityID(userId.toUuid(), Users)
            it[Activities.action] = action
            if (entityType != null) it[Activities.entityType] = entityType
            if (entityId != null) it[Activities.entityId] = Uuid.parse(entityId)
            if (details != null) it[Activities.details] = details
        }
    }

    override fun page(entityId: String?, cursor: String?, limit: Int): CursorPage<Activity> {
        val anchor = Activities.keysetAnchor(Activities.createdAt, cursor)
        return Activities.selectAll()
            .apply { if (entityId != null) andWhere { Activities.entityId eq Uuid.parse(entityId) } }
            .apply { if (anchor != null) andWhere { keysetAfter(Activities, Activities.createdAt, anchor) } }
            .orderBy(Activities.createdAt to SortOrder.ASC, Activities.id to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toActivity() }
            .toCursorPage(limit) { it.id }
    }

    private fun ResultRow.toActivity() = Activity(
        id = this[Activities.id].value.toString(),
        userId = this[Activities.userId]?.value?.toString(),
        userEmail = this[Activities.userEmail],
        userName = this[Activities.userName],
        action = this[Activities.action],
        details = this[Activities.details],
        createdAt = this[Activities.createdAt].kx(),
    )
}
