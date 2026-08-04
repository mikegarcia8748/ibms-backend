package com.puregoldbe.ibms.support

import com.puregoldbe.ibms.adapter.security.BcryptPasswordHasher
import com.puregoldbe.ibms.configureSerialization
import com.puregoldbe.ibms.infrastructure.config.AppConfig
import com.puregoldbe.ibms.infrastructure.config.AppEnv
import com.puregoldbe.ibms.infrastructure.config.AuthConfig
import com.puregoldbe.ibms.infrastructure.config.EmailDelivery
import com.puregoldbe.ibms.infrastructure.config.JwtConfig
import com.puregoldbe.ibms.infrastructure.moduleWith
import io.ktor.server.application.Application

/**
 * Support for HTTP-level integration specs. `testModule()` boots the real
 * composition root (Bootstrap) against the shared Testcontainers Postgres —
 * the same wiring production gets, minus Micrometer metrics. Specs obtain a
 * token through the real login endpoint; see `signIn()` in TestAuth.
 *
 * Deliberately hand-built rather than going through `AppConfig.fromEnv()`: the
 * env-reading and validation logic is covered by `AppConfigSpec` as a pure unit,
 * with no container and no process-environment mutation. Pass [mutate] to vary one
 * field for a spec that needs a different wiring.
 */
fun testAppConfig(mutate: AppConfig.() -> AppConfig = { this }): AppConfig = AppConfig(
    // Dev rules: the hardened fail-closed checks are exercised in AppConfigSpec.
    appEnv = AppEnv.DEV,
    db = PostgresTestDb.dbConfig,
    jwt = JwtConfig(secret = "test-secret", issuer = "ibms-backend", audience = "ibms-app", expiresMinutes = 720),
    // Cost 4 is the bcrypt minimum. Production cost (12) is ~100ms per hash by
    // design, which would add minutes to a suite that logs in constantly.
    auth = AuthConfig(
        bcryptCost = BcryptPasswordHasher.MIN_COST,
        temporaryPasswordTtlHours = 72,
        refreshTokenTtlDays = 30,
        passwordChallengeTtlMinutes = 10,
        maxFailedLogins = 5,
        lockoutMinutes = 15,
        bootstrapAdminUsername = "mikepg",
        bootstrapAdminPassword = "TestBootstrapPw9",
    ),
    storageLocalDir = System.getProperty("java.io.tmpdir").trimEnd('/') + "/ibms-test-storage",
    corsAllowedHosts = emptyList(),
    // No relay configured → the suite runs the enqueue -> dispatch pipeline through
    // SimulatedEmailGateway.
    emailDelivery = EmailDelivery.LOG,
    smtp = null,
    appUrl = "http://localhost:8080",
    presignSecret = "test-presign-secret",
).mutate()

fun Application.testModule() = testModule(testAppConfig())

/** For specs that need a config other than the default, e.g. a different bootstrap admin. */
fun Application.testModule(cfg: AppConfig) {
    configureSerialization()
    moduleWith(cfg)
}
