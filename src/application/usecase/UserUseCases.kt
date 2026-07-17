package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.port.UserRepository

class ListUsersUseCase(
    private val users: UserRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(role: UserRole?): List<UserProfile> = tx.inTransaction { users.list(role) }
}

/**
 * Role delegation (sysadmin only, enforced at the controller). Business rule:
 * the last remaining sysadmin cannot be demoted, or the system would lock itself
 * out of user administration.
 */
class UpdateUserRoleUseCase(
    private val users: UserRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(userId: String, newRole: UserRole): UserProfile = tx.inTransaction {
        val target = users.findById(userId) ?: throw DomainError.NotFound("user $userId not found")
        if (target.role == UserRole.SYSADMIN && newRole != UserRole.SYSADMIN &&
            users.countByRole(UserRole.SYSADMIN) <= 1
        ) {
            throw DomainError.Conflict("cannot demote the last sysadmin", "last_sysadmin")
        }
        users.updateRole(userId, newRole) ?: throw DomainError.NotFound("user $userId not found")
    }
}
