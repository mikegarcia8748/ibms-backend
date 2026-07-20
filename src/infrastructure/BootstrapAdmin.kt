package com.puregoldbe.ibms.infrastructure

import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.PasswordHasher
import com.puregoldbe.ibms.domain.port.SecretGenerator
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.domain.port.UserRepository
import com.puregoldbe.ibms.domain.service.SessionPolicy
import com.puregoldbe.ibms.infrastructure.config.AuthConfig
import io.ktor.server.application.*

/**
 * Chicken-and-egg resolver for a fresh deployment.
 *
 * Every account is created by a sysadmin, but the seeded sysadmin (V3) has no
 * password — so without this, a newly migrated database has no way in. On the
 * first startup where that account still has no password hash, one is installed
 * and flagged `must_change_password`, putting the very first administrator
 * through the same temporary-password exchange as everyone else.
 *
 * Deliberately a no-op once a password exists: it cannot be used to take over a
 * live account by restarting the process with an environment variable set.
 */
fun Application.installBootstrapAdminCredentials(
    cfg: AuthConfig,
    users: UserRepository,
    hasher: PasswordHasher,
    secrets: SecretGenerator,
    policy: SessionPolicy,
    clock: Clock,
    tx: TransactionRunner,
) {
    val outcome = runCatching {
        kotlinx.coroutines.runBlocking {
            tx.inTransaction {
                val admin = users.findByEmail(cfg.bootstrapAdminEmail)
                    ?: return@inTransaction BootstrapOutcome.NoSuchUser
                if (users.credentialsById(admin.id)?.passwordHash != null) {
                    return@inTransaction BootstrapOutcome.AlreadyProvisioned
                }

                val generated = cfg.bootstrapAdminPassword == null
                val password = cfg.bootstrapAdminPassword ?: secrets.temporaryPassword()
                val now = clock.now()
                users.setPassword(
                    id = admin.id,
                    passwordHash = hasher.hash(password),
                    mustChangePassword = true,
                    tempPasswordExpiresAt = now + policy.temporaryPasswordTtl,
                    at = now,
                )
                BootstrapOutcome.Installed(admin.username, if (generated) password else null)
            }
        }
    }.getOrElse { failure ->
        log.error("[bootstrap] could not install the bootstrap admin password", failure)
        return
    }

    when (outcome) {
        is BootstrapOutcome.AlreadyProvisioned -> Unit
        is BootstrapOutcome.NoSuchUser ->
            log.warn("[bootstrap] no user with email ${cfg.bootstrapAdminEmail} — set BOOTSTRAP_ADMIN_EMAIL to an existing account.")
        is BootstrapOutcome.Installed -> if (outcome.generatedPassword == null) {
            log.info(
                "[bootstrap] installed the BOOTSTRAP_ADMIN_PASSWORD for '{}'. It must be changed at first login.",
                outcome.username,
            )
        } else {
            // Logged exactly once, and only because there is no other channel to
            // reach the first administrator. It expires and must be changed on use.
            log.warn(
                "[bootstrap] no BOOTSTRAP_ADMIN_PASSWORD set — generated a temporary password for '{}': {}\n" +
                    "[bootstrap] Sign in and change it now; it will not be shown again.",
                outcome.username,
                outcome.generatedPassword,
            )
        }
    }
}

private sealed interface BootstrapOutcome {
    data object AlreadyProvisioned : BootstrapOutcome
    data object NoSuchUser : BootstrapOutcome
    data class Installed(val username: String, val generatedPassword: String?) : BootstrapOutcome
}
