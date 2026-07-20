package com.puregoldbe.ibms.adapter.repository

import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.db.jt
import com.puregoldbe.ibms.adapter.db.keysetAfter
import com.puregoldbe.ibms.adapter.db.keysetAnchor
import com.puregoldbe.ibms.adapter.db.kx
import com.puregoldbe.ibms.adapter.db.toCursorPage
import com.puregoldbe.ibms.adapter.db.toUuidOrNull
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.UserCredentials
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.port.UserRepository
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

class ExposedUserRepository : UserRepository {

    override fun findById(id: String): UserProfile? {
        val uuid = id.toUuidOrNull() ?: return null
        return Users.selectAll().where { Users.id eq uuid }.map { it.toUserProfile() }.singleOrNull()
    }

    override fun findByEmail(email: String): UserProfile? =
        Users.selectAll().where { Users.email eq email }.map { it.toUserProfile() }.singleOrNull()

    override fun findByUsername(username: String): UserProfile? =
        Users.selectAll().where { Users.username eq username }.map { it.toUserProfile() }.singleOrNull()

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

    // The columns are CITEXT, so these comparisons are case-insensitive in the
    // database — 'A.Cruz' and 'a.cruz' collide, which is what the unique index means.
    override fun existsByUsername(username: String): Boolean =
        Users.selectAll().where { Users.username eq username }.limit(1).any()

    override fun existsByEmail(email: String): Boolean =
        Users.selectAll().where { Users.email eq email }.limit(1).any()

    override fun create(
        input: ProvisionUserRequest,
        passwordHash: String,
        tempPasswordExpiresAt: Instant,
        at: Instant,
    ): UserProfile {
        val newId = Users.insertAndGetId {
            it[Users.username] = input.username
            it[Users.email] = input.email
            it[Users.name] = input.name
            it[Users.firstName] = input.firstName
            it[Users.middleInitial] = input.middleInitial
            it[Users.lastName] = input.lastName
            it[Users.employeeNumber] = input.employeeNumber
            it[Users.role] = input.role
            it[Users.passwordHash] = passwordHash
            it[Users.mustChangePassword] = true
            it[Users.tempPasswordExpiresAt] = tempPasswordExpiresAt.jt()
            it[Users.passwordUpdatedAt] = at.jt()
            it[Users.failedLoginAttempts] = 0
            it[Users.createdAt] = at.jt()
            it[Users.updatedAt] = at.jt()
        }.value
        return findById(newId.toString())!!
    }

    override fun updateRole(id: String, role: UserRole): UserProfile? {
        val uuid = id.toUuidOrNull() ?: return null
        val updated = Users.update({ Users.id eq uuid }) { it[Users.role] = role }
        return if (updated == 0) null else findById(id)
    }

    override fun credentialsByUsername(username: String): UserCredentials? =
        Users.selectAll().where { Users.username eq username }.map { it.toCredentials() }.singleOrNull()

    override fun credentialsById(id: String): UserCredentials? {
        val uuid = id.toUuidOrNull() ?: return null
        return Users.selectAll().where { Users.id eq uuid }.map { it.toCredentials() }.singleOrNull()
    }

    override fun setPassword(
        id: String,
        passwordHash: String,
        mustChangePassword: Boolean,
        tempPasswordExpiresAt: Instant?,
        at: Instant,
    ): UserProfile? {
        val uuid = id.toUuidOrNull() ?: return null
        val updated = Users.update({ Users.id eq uuid }) {
            it[Users.passwordHash] = passwordHash
            it[Users.mustChangePassword] = mustChangePassword
            it[Users.tempPasswordExpiresAt] = tempPasswordExpiresAt?.jt()
            it[Users.passwordUpdatedAt] = at.jt()
            it[Users.updatedAt] = at.jt()
            // A new password clears any lockout: the reset is the remedy for being
            // locked out, so it must not leave the account still barred.
            it[Users.failedLoginAttempts] = 0
            it[Users.lockedUntil] = null
        }
        return if (updated == 0) null else findById(id)
    }

    override fun recordFailedLogin(id: String, attempts: Int, lockedUntil: Instant?) {
        val uuid = id.toUuidOrNull() ?: return
        Users.update({ Users.id eq uuid }) {
            it[Users.failedLoginAttempts] = attempts
            it[Users.lockedUntil] = lockedUntil?.jt()
        }
    }

    override fun clearLoginFailures(id: String) {
        val uuid = id.toUuidOrNull() ?: return
        Users.update({ Users.id eq uuid }) {
            it[Users.failedLoginAttempts] = 0
            it[Users.lockedUntil] = null
        }
    }

    private fun ResultRow.toUserProfile() = UserProfile(
        id = this[Users.id].value.toString(),
        username = this[Users.username],
        email = this[Users.email],
        name = this[Users.name],
        firstName = this[Users.firstName],
        middleInitial = this[Users.middleInitial],
        lastName = this[Users.lastName],
        employeeNumber = this[Users.employeeNumber],
        role = this[Users.role],
        mustChangePassword = this[Users.mustChangePassword],
    )

    private fun ResultRow.toCredentials() = UserCredentials(
        userId = this[Users.id].value.toString(),
        username = this[Users.username],
        passwordHash = this[Users.passwordHash],
        mustChangePassword = this[Users.mustChangePassword],
        tempPasswordExpiresAt = this[Users.tempPasswordExpiresAt]?.kx(),
        failedLoginAttempts = this[Users.failedLoginAttempts],
        lockedUntil = this[Users.lockedUntil]?.kx(),
    )
}
