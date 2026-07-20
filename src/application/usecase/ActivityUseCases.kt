package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.model.Activity
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.port.ActivityRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner

/** Read the audit log, cursor-paginated, optionally scoped to one entity. */
class ListActivitiesUseCase(
    private val activities: ActivityRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(entityId: String?, cursor: String?, limit: Int): CursorPage<Activity> =
        tx.inTransaction { activities.page(entityId, cursor, limit) }
}
