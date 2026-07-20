package com.puregoldbe.ibms.support

import com.puregoldbe.ibms.adapter.security.BcryptPasswordHasher
import com.puregoldbe.ibms.configureSerialization
import com.puregoldbe.ibms.infrastructure.config.AppConfig
import com.puregoldbe.ibms.infrastructure.config.AuthConfig
import com.puregoldbe.ibms.infrastructure.config.JwtConfig
import com.puregoldbe.ibms.infrastructure.moduleWith
import io.ktor.server.application.Application

/**
 * Support for HTTP-level integration specs. `testModule()` boots the real
 * composition root (Bootstrap) against the shared Testcontainers Postgres —
 * the same wiring production gets, minus Micrometer metrics. Specs obtain a
 * token through the real login endpoint; see `signIn()` in TestAuth.
 */
fun testAppConfig(): AppConfig = AppConfig(
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
    geminiApiKey = null,
    mailerSendApiKey = null,
    mailerSendFromEmail = null,
    appUrl = "http://localhost:8080",
)

fun Application.testModule() {
    configureSerialization()
    moduleWith(testAppConfig())
}
