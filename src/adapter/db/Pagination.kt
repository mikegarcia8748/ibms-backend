package com.puregoldbe.ibms.adapter.db

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.CursorPage
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant
import java.util.UUID

/**
 * Keyset (cursor) pagination helpers shared by the paged repositories.
 *
 * Ordering is `(created_at, id)`: created_at gives chronological order, and the
 * primary-key id is the unique tie-break so equal timestamps never skip or repeat
 * a row. The API_CONTRACT cursor is a bare row id, so [keysetAnchor] looks up that
 * row's created_at to resume from. Repos fetch `limit + 1` and [toCursorPage]
 * derives `nextCursor` (null on the last page).
 */

const val DEFAULT_PAGE_LIMIT = 50
const val MAX_PAGE_LIMIT = 100

/** Clamp a client-supplied page size to a sane range (defaulting when absent). */
fun clampLimit(raw: Int?): Int = (raw ?: DEFAULT_PAGE_LIMIT).coerceIn(1, MAX_PAGE_LIMIT)

/**
 * Resolve the (created_at, id) anchor for [cursor]. Returns null for the first page
 * (no cursor); throws [DomainError.Validation] on a garbled or unknown cursor id.
 */
fun UUIDTable.keysetAnchor(createdAt: Column<Instant>, cursor: String?): Pair<Instant, UUID>? {
    if (cursor.isNullOrBlank()) return null
    val cUuid = cursor.toUuidOrNull() ?: throw DomainError.Validation("invalid cursor")
    val ts = selectAll().where { id eq cUuid }.map { it[createdAt] }.singleOrNull()
        ?: throw DomainError.Validation("invalid cursor")
    return ts to cUuid
}

/** Keyset predicate `(created_at, id) > anchor` for stable forward pagination. */
fun keysetAfter(table: UUIDTable, createdAt: Column<Instant>, anchor: Pair<Instant, UUID>): Op<Boolean> {
    val (ts, cid) = anchor
    return (createdAt greater ts) or ((createdAt eq ts) and (table.id greater cid))
}

/** Slice a `limit + 1` fetch into a page + nextCursor (the last kept row's id). */
inline fun <T> List<T>.toCursorPage(limit: Int, id: (T) -> String): CursorPage<T> {
    val hasMore = size > limit
    val items = if (hasMore) dropLast(1) else this
    return CursorPage(items, if (hasMore) id(items.last()) else null)
}
