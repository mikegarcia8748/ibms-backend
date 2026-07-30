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
    val bootstrapAdminUsername: String,
    val bootstrapAdminPassword: String?,
) {
    fun sessionPolicy(): SessionPolicy = SessionPolicy(
        refreshTtl = refreshTokenTtlDays.days,
        temporaryPasswordTtl = temporaryPasswordTtlHours.hours,
        maxFailedLogins = maxFailedLogins,
        lockoutDuration = lockoutMinutes.minutes,
    )
}

/**
 * The org's internal SMTP relay, used for every outbound notification. Absent
 * (`AppConfig.smtp == null`) when `SMTP_HOST` is unset, which drops delivery to the
 * logging [com.puregoldbe.ibms.adapter.gateway.SimulatedEmailGateway] — the same
 * "degrade to simulated" contract as [geminiApiKey] and [rfpApiBaseUrl].
 *
 * [username] null means the relay takes no AUTH, which is normal for a relay that
 * only listens on the internal network. [fromEmail] is required whenever a host is
 * set: relays routinely refuse a From they don't recognise, so it must be the
 * generic address the relay is willing to send as.
 */
data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    /** Upgrade a plaintext connection via STARTTLS (the port-587 submission flow). */
    val startTls: Boolean,
    /** TLS from the first byte instead (the port-465 flow). Mutually exclusive with [startTls]. */
    val sslOnConnect: Boolean,
    val fromEmail: String,
    val fromName: String?,
)

data class AppConfig(
    val db: DbConfig,
    val jwt: JwtConfig,
    val auth: AuthConfig,
    val storageLocalDir: String,
    val corsAllowedHosts: List<String>,
    val geminiApiKey: String?,
    /** Null → notifications are logged, not sent. See [SmtpConfig]. */
    val smtp: SmtpConfig?,
    val appUrl: String,
    /** External RFP-generating system. Null → the simulated gateway is used. */
    val rfpApiBaseUrl: String?,
    val rfpApiKey: String?,
) {
    companion object {
        private fun env(name: String, default: String? = null): String? =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

        /**
         * Null unless SMTP_HOST is set. The from-address falls back to SMTP_USERNAME
         * (relays are usually happy to send as the mailbox that authenticated) and is
         * a hard startup failure otherwise — a queued row that could only ever fail to
         * send is worse than a boot that refuses.
         */
        private fun smtpFromEnv(): SmtpConfig? {
            val host = env("SMTP_HOST") ?: return null
            val username = env("SMTP_USERNAME")
            val fromEmail = env("MAIL_FROM_EMAIL") ?: username
                ?: error("SMTP_HOST is set but neither MAIL_FROM_EMAIL nor SMTP_USERNAME is — no from address to send as")
            return SmtpConfig(
                host = host,
                port = env("SMTP_PORT", "587")!!.toInt(),
                username = username,
                password = env("SMTP_PASSWORD"),
                // Strict (but case-insensitive): a typo must not silently read as false
                // and quietly downgrade the connection to plaintext.
                startTls = env("SMTP_STARTTLS", "true")!!.lowercase().toBooleanStrict(),
                sslOnConnect = env("SMTP_SSL", "false")!!.lowercase().toBooleanStrict(),
                fromEmail = fromEmail,
                fromName = env("MAIL_FROM_NAME", "IBMS Notifications"),
            )
        }

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
                bootstrapAdminUsername = env("BOOTSTRAP_ADMIN_USERNAME", "mikepg")!!,
                bootstrapAdminPassword = env("BOOTSTRAP_ADMIN_PASSWORD", "Password@123"),
            ),
            storageLocalDir = env("STORAGE_LOCAL_DIR", "./storage")!!,
            corsAllowedHosts = env("CORS_ALLOWED_HOSTS", "")!!
                .split(",").map { it.trim() }.filter { it.isNotEmpty() },
            geminiApiKey = env("GEMINI_API_KEY"),
            smtp = smtpFromEnv(),
            appUrl = env("APP_URL", "http://localhost:8080")!!,
            rfpApiBaseUrl = env("RFP_API_BASE_URL"),
            rfpApiKey = env("RFP_API_KEY"),
        )
    }
}
