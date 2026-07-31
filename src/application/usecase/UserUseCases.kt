package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.ProvisionedUser
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.model.UserStatus
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.NotificationRoleDefaultsRepository
import com.puregoldbe.ibms.domain.port.NotificationSubscriptionRepository
import com.puregoldbe.ibms.domain.port.PasswordHasher
import com.puregoldbe.ibms.domain.port.SecretGenerator
import com.puregoldbe.ibms.domain.port.SessionRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.port.UserRepository
import com.puregoldbe.ibms.domain.service.EmailAddressPolicy
import com.puregoldbe.ibms.domain.service.SessionPolicy
import com.puregoldbe.ibms.domain.service.UsernamePolicy

class ListUsersUseCase(
    private val users: UserRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        role: UserRole?,
        status: UserStatus?,
        cursor: String?,
        limit: Int,
    ): CursorPage<UserProfile> =
        tx.inTransaction { users.page(role, status, cursor, limit) }
}

/**
 * Account provisioning (sysadmin only, enforced at the controller). There is no
 * self-registration: this is the only way a user comes into existence.
 *
 * The temporary password is generated here and returned to the admin exactly
 * once — only its bcrypt hash is persisted, so a lost temporary password can be
 * replaced (see [ResetUserPasswordUseCase]) but never recovered.
 *
 * The new account's notification subscriptions are seeded from the per-role
 * defaults in the same transaction, so a user is either created with their
 * defaults or not created at all.
 */
class ProvisionUserUseCase(
    private val users: UserRepository,
    private val subscriptions: NotificationSubscriptionRepository,
    private val roleDefaults: NotificationRoleDefaultsRepository,
    private val hasher: PasswordHasher,
    private val secrets: SecretGenerator,
    private val policy: SessionPolicy,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(request: ProvisionUserRequest): ProvisionedUser = tx.inTransaction {
        val username = UsernamePolicy.normalize(request.username)
        if (request.name.isBlank()) {
            throw DomainError.Validation("name is required", code = "invalid_name")
        }
        if (users.existsByUsername(username)) {
            throw DomainError.Conflict("username '$username' is already taken", code = "username_taken")
        }
        val email = EmailAddressPolicy.normalizeOrNull(request.email)

        val now = clock.now()
        val temporaryPassword = secrets.temporaryPassword()
        val expiresAt = now + policy.temporaryPasswordTtl
        val user = users.create(
            input = request.copy(username = username, email = email),
            passwordHash = hasher.hash(temporaryPassword),
            tempPasswordExpiresAt = expiresAt,
            at = now,
        )
        roleDefaults.forRole(user.role)
            .takeIf { it.isNotEmpty() }
            ?.let { subscriptions.setForUser(user.id, it) }
        ProvisionedUser(user, temporaryPassword, expiresAt)
    }
}

/**
 * Set or clear a user's notification delivery address (sysadmin only, enforced at
 * the controller).
 *
 * This is the only way an address ever gets onto a user record, and without one a
 * user receives no notification email whatever they are subscribed to — recipient
 * resolution requires a non-null address.
 */
class UpdateUserEmailUseCase(
    private val users: UserRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(userId: String, email: String?): UserProfile = tx.inTransaction {
        users.findById(userId) ?: throw DomainError.NotFound("user $userId not found")
        val normalized = EmailAddressPolicy.normalizeOrNull(email)
        users.updateEmail(userId, normalized) ?: throw DomainError.NotFound("user $userId not found")
    }
}

/**
 * Issue a fresh temporary password — the recovery path for a forgotten or expired
 * one. Every existing session is revoked, so this doubles as the way to cut off
 * an account whose credentials may have leaked.
 */
class ResetUserPasswordUseCase(
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val hasher: PasswordHasher,
    private val secrets: SecretGenerator,
    private val policy: SessionPolicy,
    private val clock: Clock,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(userId: String): ProvisionedUser = tx.inTransaction {
        val now = clock.now()
        val temporaryPassword = secrets.temporaryPassword()
        val expiresAt = now + policy.temporaryPasswordTtl
        val user = users.setPassword(
            id = userId,
            passwordHash = hasher.hash(temporaryPassword),
            mustChangePassword = true,
            tempPasswordExpiresAt = expiresAt,
            at = now,
        ) ?: throw DomainError.NotFound("user $userId not found")
        sessions.revokeAllForUser(userId, now)
        ProvisionedUser(user, temporaryPassword, expiresAt)
    }
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

/**
 * Toggle user status between active and inactive (sysadmin only, enforced at the
 * controller). Used to disable accounts when an employee resigns without deleting
 * the user record. An inactive user is blocked from logging in.
 */
class UpdateUserStatusUseCase(
    private val users: UserRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(userId: String, newStatus: UserStatus): UserProfile = tx.inTransaction {
        users.findById(userId) ?: throw DomainError.NotFound("user $userId not found")
        users.updateStatus(userId, newStatus) ?: throw DomainError.NotFound("user $userId not found")
    }
}
