package com.puregoldbe.ibms.domain.port

import com.puregoldbe.ibms.domain.model.Activity
import com.puregoldbe.ibms.domain.model.CursorPage

/**
 * Narrow write side of the audit log. Mutating use cases depend on this and record a
 * row INSIDE their own transaction, so the audit entry commits iff the mutation does
 * (and an idempotent replay, which skips the mutation, records no duplicate).
 */
interface ActivityRecorder {
    fun record(
        userId: String?,
        action: String,
        entityType: String? = null,
        entityId: String? = null,
        details: String? = null,
    )
}

interface ActivityRepository : ActivityRecorder {
    fun page(entityId: String?, cursor: String?, limit: Int): CursorPage<Activity>
}
