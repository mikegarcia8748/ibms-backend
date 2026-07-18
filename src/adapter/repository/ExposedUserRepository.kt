package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.keysetAfter
import com.puregoldbe.ibms.adapter.db.keysetAnchor
import com.puregoldbe.ibms.adapter.db.toCursorPage
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.port.UserRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

class ExposedUserRepository : UserRepository {

    override fun findById(id: String): UserProfile? {
        val uuid = id.toUuidOrNull() ?: return null
        return Users.selectAll().where { Users.id eq uuid }.map { it.toUserProfile() }.singleOrNull()
    }

    override fun findByEmail(email: String): UserProfile? =
        Users.selectAll().where { Users.email eq email }.map { it.toUserProfile() }.singleOrNull()

    override fun findByGoogleSub(googleSub: String): UserProfile? =
        Users.selectAll().where { Users.googleSub eq googleSub }.map { it.toUserProfile() }.singleOrNull()

    override fun list(role: UserRole?): List<UserProfile> =
        Users.selectAll()
            .apply { if (role != null) andWhere { Users.role eq role } }
            .orderBy(Users.name)
            .map { it.toUserProfile() }

    override fun page(role: UserRole?, cursor: String?, limit: Int): CursorPage<UserProfile> {
        val anchor = Users.keysetAnchor(Users.createdAt, cursor)
        return Users.selectAll()
            .apply { if (role != null) andWhere { Users.role eq role } }
            .apply { if (anchor != null) andWhere { keysetAfter(Users, Users.createdAt, anchor) } }
            .orderBy(Users.createdAt to SortOrder.ASC, Users.id to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toUserProfile() }
            .toCursorPage(limit) { it.id }
    }

    override fun countByRole(role: UserRole): Int =
        Users.selectAll().where { Users.role eq role }.count().toInt()

    override fun create(email: String, name: String, googleSub: String?, role: UserRole): UserProfile {
        val newId = Users.insertAndGetId {
            it[Users.email] = email
            it[Users.name] = name
            if (googleSub != null) it[Users.googleSub] = googleSub
            it[Users.role] = role
        }.value
        return findById(newId.toString())!!
    }

    override fun updateRole(id: String, role: UserRole): UserProfile? {
        val uuid = id.toUuidOrNull() ?: return null
        val updated = Users.update({ Users.id eq uuid }) { it[Users.role] = role }
        return if (updated == 0) null else findById(id)
    }

    override fun updateGoogleSub(id: String, googleSub: String): UserProfile? {
        val uuid = id.toUuidOrNull() ?: return null
        val updated = Users.update({ Users.id eq uuid }) { it[Users.googleSub] = googleSub }
        return if (updated == 0) null else findById(id)
    }

    private fun ResultRow.toUserProfile() = UserProfile(
        id = this[Users.id].value.toString(),
        email = this[Users.email],
        name = this[Users.name],
        firstName = this[Users.firstName],
        middleInitial = this[Users.middleInitial],
        lastName = this[Users.lastName],
        employeeNumber = this[Users.employeeNumber],
        role = this[Users.role],
    )
}
