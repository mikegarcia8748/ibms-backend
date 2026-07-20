package com.puregoldbe.ibms.infrastructure.config

import com.puregoldbe.ibms.domain.service.SessionPolicy
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * All runtime configuration, read from environment variables with local-dev
 * defaults that line up with docker-compose.yml and .env.example. Loaded once at
 * startup by the composition root.
 */
data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
    val poolSize: Int,
)

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    /** Access-token lifetime. Short by design — a refresh token covers long sessions. */
    val expiresMinutes: Long,
)

/**
 * Authentication tuning. [bootstrapAdminPassword] exists so a fresh deployment
 * has a way in: the seeded sysadmin has no password until one is installed. Leave
 * it unset and the backend generates one at first startup and logs it once.
 */
data class AuthConfig(
    val bcryptCost: Int,
    val temporaryPasswordTtlHours: Long,
    val refreshTokenTtlDays: Long,
    val passwordChallengeTtlMinutes: Long,
    val maxFailedLogins: Int,
    val lockoutMinutes: Long,
    val bootstrapAdminEmail: String,
    val bootstrapAdminPassword: String?,
) {
    fun sessionPolicy(): SessionPolicy = SessionPolicy(
        refreshTtl = refreshTokenTtlDays.days,
        temporaryPasswordTtl = temporaryPasswordTtlHours.hours,
        maxFailedLogins = maxFailedLogins,
        lockoutDuration = lockoutMinutes.minutes,
    )
}

data class AppConfig(
    val db: DbConfig,
    val jwt: JwtConfig,
    val auth: AuthConfig,
    val storageLocalDir: String,
    val corsAllowedHosts: List<String>,
    val geminiApiKey: String?,
    val mailerSendApiKey: String?,
    val mailerSendFromEmail: String?,
    val appUrl: String,
) {
    companion object {
        private fun env(name: String, default: String? = null): String? =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

        fun fromEnv(): AppConfig = AppConfig(
            db = DbConfig(
                url = env("DB_URL", "jdbc:postgresql://localhost:5432/ibms")!!,
                user = env("DB_USER", "ibms")!!,
                password = env("DB_PASSWORD", "ibms")!!,
                poolSize = env("DB_POOL_SIZE", "10")!!.toInt(),
            ),
            jwt = JwtConfig(
                secret = env("JWT_SECRET", "dev-secret-change-me")!!,
                issuer = env("JWT_ISSUER", "ibms-backend")!!,
                audience = env("JWT_AUDIENCE", "ibms-app")!!,
                expiresMinutes = env("JWT_EXPIRES_MINUTES", "60")!!.toLong(),
            ),
            auth = AuthConfig(
                bcryptCost = env("BCRYPT_COST", "12")!!.toInt(),
                temporaryPasswordTtlHours = env("TEMP_PASSWORD_TTL_HOURS", "72")!!.toLong(),
                refreshTokenTtlDays = env("REFRESH_TOKEN_TTL_DAYS", "30")!!.toLong(),
                passwordChallengeTtlMinutes = env("PASSWORD_CHALLENGE_TTL_MINUTES", "10")!!.toLong(),
                maxFailedLogins = env("MAX_FAILED_LOGINS", "5")!!.toInt(),
                lockoutMinutes = env("LOGIN_LOCKOUT_MINUTES", "15")!!.toLong(),
                bootstrapAdminEmail = env("BOOTSTRAP_ADMIN_EMAIL", "mike.pgmobiledev@gmail.com")!!,
                bootstrapAdminPassword = env("BOOTSTRAP_ADMIN_PASSWORD"),
            ),
            storageLocalDir = env("STORAGE_LOCAL_DIR", "./storage")!!,
            corsAllowedHosts = env("CORS_ALLOWED_HOSTS", "")!!
                .split(",").map { it.trim() }.filter { it.isNotEmpty() },
            geminiApiKey = env("GEMINI_API_KEY"),
            mailerSendApiKey = env("MAILERSEND_API_KEY"),
            mailerSendFromEmail = env("MAILERSEND_FROM_EMAIL"),
            appUrl = env("APP_URL", "http://localhost:8080")!!,
        )
    }
}
