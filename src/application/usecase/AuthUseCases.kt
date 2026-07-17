package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.port.TokenVerifierPort
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.port.UserRepository

/**
 * Verify a Google OIDC token and resolve the backend user. New users are created
 * as PENDING (awaiting a sysadmin role grant); an existing dev-seeded user is
 * linked to its google_sub on first login. Preserves the legacy sign-up behavior.
 */
class AuthenticateWithGoogleUseCase(
    private val tokenVerifier: TokenVerifierPort,
    private val users: UserRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(idToken: String): UserProfile = tx.inTransaction {
        val identity = tokenVerifier.verify(idToken)
            ?: throw DomainError.Unauthorized("invalid Google token")
        users.findByGoogleSub(identity.sub)
            ?: users.findByEmail(identity.email)?.also { users.updateGoogleSub(it.id, identity.sub) }
            ?: users.create(identity.email, identity.name ?: identity.email, identity.sub, UserRole.PENDING)
    }
}

/** Dev-only: resolve a seeded user by email so a JWT can be issued without Google. */
class AuthenticateDevUseCase(
    private val users: UserRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(email: String): UserProfile = tx.inTransaction {
        users.findByEmail(email) ?: throw DomainError.NotFound("no user with email $email")
    }
}

/** Resolve the current user's profile from the JWT subject. */
class GetCurrentUserUseCase(
    private val users: UserRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(userId: String): UserProfile = tx.inTransaction {
        users.findById(userId) ?: throw DomainError.NotFound("user $userId not found")
    }
}
