package com.puregoldbe.ibms.infrastructure.config

import com.puregoldbe.ibms.adapter.security.BcryptPasswordHasher
import com.puregoldbe.ibms.domain.service.SessionPolicy
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * All runtime configuration, read from environment variables once at startup by the
 * composition root.
 *
 * Convenience defaults exist only when `APP_ENV=dev`. In a hardened environment
 * (see [AppEnv.isHardened]) anything security-relevant must be set explicitly, and
 * [fromEnv] refuses to boot — listing every problem at once — rather than falling
 * back to a value that is safe on a laptop and not on a server.
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
 * Authentication tuning. [bootstrapAdminPassword] exists so a fresh deployment has a
 * way in: the seeded sysadmin has no password until one is installed. Leave it unset
 * and the backend generates one at first startup and logs it once — which requires
 * this to be genuinely nullable, so it deliberately has no default.
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
 * The org's internal SMTP relay. Required when [AppConfig.emailDelivery] is
 * [EmailDelivery.SMTP], absent otherwise.
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
    val appEnv: AppEnv,
    val db: DbConfig,
    val jwt: JwtConfig,
    val auth: AuthConfig,
    val storageLocalDir: String,
    val corsAllowedHosts: List<String>,
    val emailDelivery: EmailDelivery,
    /** Non-null exactly when [emailDelivery] is [EmailDelivery.SMTP]. */
    val smtp: SmtpConfig?,
    val appUrl: String,
    /**
     * Signing key for presigned attachment URLs. Separate from [JwtConfig.secret] so a
     * token minted for one purpose can never verify as the other, and so rotating the
     * JWT secret doesn't invalidate in-flight uploads as an undocumented side effect.
     */
    val presignSecret: String,
) {
    /**
     * Re-checks the fail-closed invariants that [fromEnv] enforces, so a hand-built
     * config (tests, or a future non-env source) cannot smuggle a fail-open
     * combination into a hardened environment. Cheap enough to run on every boot.
     */
    fun requireCoherent() {
        val problems = buildList {
            if (appEnv.isHardened) {
                if (corsAllowedHosts.isEmpty()) {
                    add("CORS_ALLOWED_HOSTS: must list at least one origin when APP_ENV=${appEnv.name.lowercase()} (CORS will not fall back to any-host)")
                }
                SecretRules.reject(jwt.secret)?.let { add("JWT_SECRET: $it") }
            }
            if (presignSecret == jwt.secret) {
                add("PRESIGN_SECRET: must not equal JWT_SECRET — the two sign different token types")
            }
            if (emailDelivery == EmailDelivery.SMTP && smtp == null) {
                add("EMAIL_DELIVERY=smtp requires SMTP_HOST")
            }
            if (emailDelivery == EmailDelivery.LOG && smtp != null) {
                add("EMAIL_DELIVERY=log but an SMTP relay is configured — pick one")
            }
        }
        if (problems.isNotEmpty()) throw ConfigException(problems, appEnv.name.lowercase())
    }

    companion object {
        /**
         * Reads and validates the whole environment in one pass.
         *
         * [getenv] is injected for tests; production always uses the process
         * environment. Throws [ConfigException] listing every problem found.
         */
        fun fromEnv(getenv: (String) -> String? = System::getenv): AppConfig = with(ConfigReader(getenv)) {
            val appEnv = enum("APP_ENV", default = AppEnv.PROD, AppEnv.entries.toTypedArray())
            val hardened = appEnv.isHardened

            val jwtSecret =
                if (hardened) required("JWT_SECRET", "at least ${SecretRules.MIN_SECRET_LENGTH} random characters")
                else string("JWT_SECRET", "dev-secret-change-me")
            if (hardened) SecretRules.reject(jwtSecret)?.let { problem("JWT_SECRET: $it") }

            val corsAllowedHosts = csv("CORS_ALLOWED_HOSTS")
            check(
                !hardened || corsAllowedHosts.isNotEmpty(),
                "CORS_ALLOWED_HOSTS: must list at least one origin when APP_ENV=${appEnv.name.lowercase()} " +
                    "(CORS will not fall back to any-host)",
            )

            val appUrl =
                if (hardened) required("APP_URL", "the public base URL clients and presigned links resolve against")
                else string("APP_URL", "http://localhost:8082")
            if (hardened) {
                check(
                    !appUrl.contains("localhost"),
                    "APP_URL: must be the public base URL, not localhost (presigned attachment links are built from it)",
                )
                check(
                    appEnv != AppEnv.PROD || appUrl.startsWith("https://"),
                    "APP_URL: must be https:// when APP_ENV=prod, got \"$appUrl\"",
                )
            }

            // Delivery is stated, never inferred: a prod deploy that merely forgot
            // SMTP_HOST used to drop every notification silently.
            val emailDelivery = enum(
                "EMAIL_DELIVERY",
                default = if (hardened) null else EmailDelivery.LOG,
                EmailDelivery.entries.toTypedArray(),
            )
            val smtp = smtpFromEnv(emailDelivery)

            val dbPassword =
                if (hardened) required("DB_PASSWORD", "the database role's password")
                else string("DB_PASSWORD", "ibms")
            check(
                !hardened || dbPassword != "ibms",
                "DB_PASSWORD: the built-in local-dev value \"ibms\" is not permitted when APP_ENV=${appEnv.name.lowercase()}",
            )

            // Unset means "generate one at first startup and log it once" (see
            // BootstrapAdmin). In a hardened environment that has to be asked for
            // explicitly, so an operator who simply forgot the variable is told.
            val autogenerateAdminPassword = boolean("BOOTSTRAP_ADMIN_AUTOGENERATE_PASSWORD", default = !hardened)
            val bootstrapAdminPassword = raw("BOOTSTRAP_ADMIN_PASSWORD")
            check(
                !hardened || bootstrapAdminPassword != null || autogenerateAdminPassword,
                "BOOTSTRAP_ADMIN_PASSWORD: set it, or set BOOTSTRAP_ADMIN_AUTOGENERATE_PASSWORD=true to have one " +
                    "generated and logged once at first startup",
            )
            if (bootstrapAdminPassword != null && autogenerateAdminPassword) {
                problem(
                    "BOOTSTRAP_ADMIN_PASSWORD is set and BOOTSTRAP_ADMIN_AUTOGENERATE_PASSWORD=true — pick one",
                )
            }

            finish(appEnv) {
                AppConfig(
                    appEnv = appEnv,
                    db = DbConfig(
                        url = string("DB_URL", "jdbc:postgresql://localhost:5432/ibms"),
                        user = string("DB_USER", "ibms"),
                        password = dbPassword,
                        poolSize = int("DB_POOL_SIZE", default = 10, range = 1..100),
                    ),
                    jwt = JwtConfig(
                        secret = jwtSecret,
                        issuer = string("JWT_ISSUER", "ibms-backend"),
                        audience = string("JWT_AUDIENCE", "ibms-app"),
                        // Capped at a day: a long-lived access token cannot be revoked,
                        // which is what the refresh token exists to make unnecessary.
                        expiresMinutes = long("JWT_EXPIRES_MINUTES", default = 60, range = 1L..1_440L),
                    ),
                    auth = AuthConfig(
                        bcryptCost = int(
                            "BCRYPT_COST",
                            default = BcryptPasswordHasher.DEFAULT_COST,
                            range = (if (hardened) 10 else BcryptPasswordHasher.MIN_COST)..BcryptPasswordHasher.MAX_COST,
                        ),
                        temporaryPasswordTtlHours = long("TEMP_PASSWORD_TTL_HOURS", default = 72, range = 1L..168L),
                        refreshTokenTtlDays = long("REFRESH_TOKEN_TTL_DAYS", default = 30, range = 1L..90L),
                        passwordChallengeTtlMinutes = long("PASSWORD_CHALLENGE_TTL_MINUTES", default = 10, range = 1L..60L),
                        maxFailedLogins = int("MAX_FAILED_LOGINS", default = 5, range = 1..20),
                        lockoutMinutes = long("LOGIN_LOCKOUT_MINUTES", default = 15, range = 1L..1_440L),
                        bootstrapAdminUsername =
                            if (hardened) required("BOOTSTRAP_ADMIN_USERNAME", "the username of the seeded sysadmin")
                            else string("BOOTSTRAP_ADMIN_USERNAME", "mikepg"),
                        bootstrapAdminPassword = bootstrapAdminPassword,
                    ),
                    storageLocalDir = string("STORAGE_LOCAL_DIR", "./storage"),
                    corsAllowedHosts = corsAllowedHosts,
                    emailDelivery = emailDelivery,
                    smtp = smtp,
                    appUrl = appUrl,
                    presignSecret = raw("PRESIGN_SECRET") ?: derivePresignSecret(jwtSecret),
                )
            }
        }

        /**
         * Null unless [delivery] is [EmailDelivery.SMTP]. The from-address falls back to
         * SMTP_USERNAME (relays are usually happy to send as the mailbox that
         * authenticated); a queued row that could only ever fail to send is worse than
         * a boot that refuses, so its absence is a problem rather than a warning.
         */
        private fun ConfigReader.smtpFromEnv(delivery: EmailDelivery): SmtpConfig? {
            val host = raw("SMTP_HOST")
            if (delivery != EmailDelivery.SMTP) {
                check(
                    host == null,
                    "SMTP_HOST is set but EMAIL_DELIVERY is not \"smtp\" — notifications would be logged, not sent",
                )
                return null
            }
            if (host == null) {
                problem("SMTP_HOST: required when EMAIL_DELIVERY=smtp")
            }
            val username = raw("SMTP_USERNAME")
            val fromEmail = raw("MAIL_FROM_EMAIL") ?: username
            if (fromEmail == null) {
                problem("MAIL_FROM_EMAIL: required when EMAIL_DELIVERY=smtp (or set SMTP_USERNAME to send as)")
            }
            val startTls = boolean("SMTP_STARTTLS", default = true)
            val sslOnConnect = boolean("SMTP_SSL", default = false)
            check(
                !(startTls && sslOnConnect),
                "SMTP_STARTTLS and SMTP_SSL are mutually exclusive — port 587 uses STARTTLS, port 465 uses SSL",
            )
            return SmtpConfig(
                host = host ?: "",
                port = int("SMTP_PORT", default = 587, range = 1..65_535),
                username = username,
                password = raw("SMTP_PASSWORD"),
                startTls = startTls,
                sslOnConnect = sslOnConnect,
                fromEmail = fromEmail ?: "",
                fromName = raw("MAIL_FROM_NAME", "IBMS Notifications"),
            )
        }

        /**
         * Domain-separated presign key derived from the JWT secret, so PRESIGN_SECRET is
         * optional. Setting it explicitly additionally decouples the two rotations.
         */
        internal fun derivePresignSecret(jwtSecret: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(jwtSecret.toByteArray(), "HmacSHA256"))
            return mac.doFinal("ibms/presign/v1".toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
