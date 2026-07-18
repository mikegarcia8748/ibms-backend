package com.puregoldbe.ibms.support

import com.puregoldbe.ibms.configureSerialization
import com.puregoldbe.ibms.infrastructure.config.AppConfig
import com.puregoldbe.ibms.infrastructure.config.JwtConfig
import com.puregoldbe.ibms.infrastructure.moduleWith
import io.ktor.server.application.Application

/**
 * Support for HTTP-level integration specs. `testModule()` boots the real
 * composition root (Bootstrap) against the shared Testcontainers Postgres, with
 * dev-login enabled so specs can mint a JWT without Google. Mirrors the
 * production module stack from application.yaml (minus Micrometer metrics).
 */
fun testAppConfig(): AppConfig = AppConfig(
    db = PostgresTestDb.dbConfig,
    jwt = JwtConfig(secret = "test-secret", issuer = "ibms-backend", audience = "ibms-app", expiresMinutes = 720),
    googleOauthClientId = null,
    devAuthEnabled = true,
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
