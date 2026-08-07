package com.puregoldbe.ibms.infrastructure.config

import com.puregoldbe.ibms.adapter.security.BcryptPasswordHasher
import com.puregoldbe.ibms.domain.service.SessionPolicy
import java.io.File
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
    /**
     * PEM file holding the relay's certificate, for an internal relay whose cert is
     * self-signed and so trusted by no public CA. Null means the JVM's default trust
     * store decides on its own. See `SmtpTrust`.
     */
    val trustedCertPath: String? = null,
)

/**
 * One entry of `CORS_ALLOWED_HOSTS`, split the way Ktor's `CORSConfig.allowHost` wants
 * it: the authority on its own, the scheme as a separate argument.
 */
data class CorsOrigin(val host: String, val schemes: List<String>) {
    companion object {
        private val BOTH_SCHEMES = listOf("http", "https")

        /**
         * Accepts either a bare authority (`client.example.com:8081`) or a full origin
         * (`https://client.example.com`). The full form is the natural reading of
         * "allowed origin" — and what the deploy doc used to show — but handing it
         * straight to `allowHost` throws *scheme should be specified as a separate
         * parameter* while the CORS plugin installs, so the app never finishes booting.
         * Normalising here keeps both spellings working; an explicit scheme narrows the
         * entry to that scheme, a bare host allows either.
         */
        fun parse(entry: String): CorsOrigin {
            val scheme = entry.substringBefore("://", missingDelimiterValue = "").trim().lowercase()
            val host = entry.substringAfter("://").substringBefore('/').trim()
            return CorsOrigin(host, if (scheme.isEmpty()) BOTH_SCHEMES else listOf(scheme))
        }
    }
}

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
     * Public base URL of the web client — the separate front end that users actually
     * browse. Distinct from [appUrl], which is this API's own origin: notification
     * emails deep-link a human into a *page*, and every backend route is a JSON
     * endpoint that would answer a browser with 401. See `DeepLinks`.
     */
    val webClientUrl: String,
    /**
     * Signing key for presigned attachment URLs. Separate from [JwtConfig.secret] so a
     * token minted for one purpose can never verify as the other, and so rotating the
     * JWT secret doesn't invalidate in-flight uploads as an undocumented side effect.
     */
    val presignSecret: String,
    /**
     * Optional lifecycle stages. Defaulted so a hand-built config (tests, a future
     * non-env source) gets the shipped behaviour without naming it. See [TopSheetFeatures].
     */
    val topsheet: TopSheetFeatures = TopSheetFeatures(),
) {
    /** [corsAllowedHosts] in the shape the CORS plugin takes. See [CorsOrigin]. */
    fun corsOrigins(): List<CorsOrigin> = corsAllowedHosts.map(CorsOrigin::parse)

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
            // Equality, deliberately — not host equality. One host serving the client at
            // /app and the API at / is a legitimate reverse-proxy layout; the same *base*
            // for both is the mistake, because it points every email button at a JSON route.
            if (webClientUrl.trimEnd('/').equals(appUrl.trimEnd('/'), ignoreCase = true)) {
                add(
                    "WEB_CLIENT_URL: must not equal APP_URL — APP_URL is this API's own origin, " +
                        "and notification links built from it open a JSON route, not a page",
                )
            }
            // Ktor's CORSConfig.allowHost rejects a scheme outright, so a scheme here
            // would otherwise surface as an IllegalArgumentException from deep inside
            // plugin installation, long after this report would have named the key.
            corsAllowedHosts.filter { "://" in it }.forEach {
                add("CORS_ALLOWED_HOSTS: \"$it\" must be a bare host[:port] — the scheme is not part of the entry")
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

    /**
     * Warns when [webClientUrl]'s host is not one CORS admits — a deploy where email
     * links land on an origin the browser then can't call the API from. A warning and
     * not a [requireCoherent] problem because the pairing is a strong convention, not
     * an invariant: a client can legitimately be proxied so its API calls are
     * same-origin and never preflight at all.
     *
     * Null when there is nothing to say, including when the CORS list is empty (dev's
     * any-host path, where every origin is already admitted).
     */
    fun webClientCorsWarning(): String? {
        if (corsAllowedHosts.isEmpty()) return null
        val client = hostPort(webClientUrl) ?: return null
        val allowed = corsAllowedHosts.map { hostPort(it) }
        if (client in allowed) return null
        return "[security] WEB_CLIENT_URL host \"$client\" is not in CORS_ALLOWED_HOSTS " +
            "(${corsAllowedHosts.joinToString(",")}) — notification links will open an origin " +
            "the browser cannot call this API from."
    }

    companion object {
        /**
         * `host[:port]` from either form the two keys are written in: CORS entries are
         * bare hosts, [webClientUrl] carries a scheme and possibly a path. The scheme's
         * default port is dropped so `https://x` and `x:443` compare equal.
         */
        private fun hostPort(value: String): String? {
            val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase()
            val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
            if (authority.isEmpty()) return null
            // A CORS entry carries no scheme, so which default port it implies is
            // unknowable — strip either. Ktor admits a bare host on both schemes anyway.
            val defaultPorts = when (scheme) {
                "https" -> listOf(":443")
                "http" -> listOf(":80")
                else -> listOf(":443", ":80")
            }
            return defaultPorts.fold(authority) { acc, port -> acc.removeSuffix(port) }
        }

        /**
         * Reads and validates the whole environment in one pass.
         *
         * [getenv] is injected for tests; production always uses the process
         * environment. Throws [ConfigException] listing every problem found.
         */
        fun fromEnv(getenv: (String) -> String? = System::getenv): AppConfig = with(ConfigReader(getenv)) {
            // An unrecognised APP_ENV falls back to the hardened default rather than the
            // permissive one; the boot fails either way, but the report reads correctly.
            val appEnv = enum("APP_ENV", default = AppEnv.PROD, AppEnv.entries.toTypedArray()) ?: AppEnv.PROD
            val hardened = appEnv.isHardened

            // Each dependent check below is guarded on the value being present, so one
            // missing variable produces one problem instead of two.
            val jwtSecret =
                if (hardened) required("JWT_SECRET", "at least ${SecretRules.MIN_SECRET_LENGTH} random characters")
                else string("JWT_SECRET", "dev-secret-change-me")
            if (hardened && jwtSecret != null) SecretRules.reject(jwtSecret)?.let { problem("JWT_SECRET: $it") }

            val corsAllowedHosts = csv("CORS_ALLOWED_HOSTS")
            check(
                !hardened || corsAllowedHosts.isNotEmpty(),
                "CORS_ALLOWED_HOSTS: must list at least one origin when APP_ENV=${appEnv.name.lowercase()} " +
                    "(CORS will not fall back to any-host)",
            )
            // An entry that normalises to nothing (`https://`, a stray comma's leftovers)
            // would install as a host that matches no request — a silent CORS outage.
            corsAllowedHosts.forEach { entry ->
                check(
                    CorsOrigin.parse(entry).host.isNotBlank(),
                    "CORS_ALLOWED_HOSTS: \"$entry\" has no host — expected host[:port], optionally with a scheme",
                )
            }

            val appUrl =
                if (hardened) required("APP_URL", "the public base URL clients and presigned links resolve against")
                else string("APP_URL", "http://localhost:8082")
            if (hardened && appUrl != null) {
                check(
                    !appUrl.contains("localhost"),
                    "APP_URL: must be the public base URL, not localhost (presigned attachment links are built from it)",
                )
                check(
                    appEnv != AppEnv.PROD || appUrl.startsWith("https://"),
                    "APP_URL: must be https:// when APP_ENV=prod, got \"$appUrl\"",
                )
            }

            // The front end's origin, not this API's. Read on both branches on purpose:
            // ConfigKeysSpec discovers the key set by running fromEnv under APP_ENV=dev,
            // so a key consulted only when hardened would look undocumented to it.
            val webClientUrl =
                if (hardened) required("WEB_CLIENT_URL", "the public base URL of the web client that notification links open")
                else string("WEB_CLIENT_URL", "http://localhost:8081")
            if (hardened && webClientUrl != null) {
                check(
                    !webClientUrl.contains("localhost"),
                    "WEB_CLIENT_URL: must be the public base URL of the web client, not localhost " +
                        "(notification email links are built from it)",
                )
                check(
                    appEnv != AppEnv.PROD || webClientUrl.startsWith("https://"),
                    "WEB_CLIENT_URL: must be https:// when APP_ENV=prod, got \"$webClientUrl\"",
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
                !hardened || dbPassword == null || dbPassword != "ibms",
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

            // PLACEHOLDER stands in for anything missing so the remaining keys still get
            // checked. It never escapes: finish() throws whenever a problem was recorded.
            finish(appEnv) {
                AppConfig(
                    appEnv = appEnv,
                    db = DbConfig(
                        url = string("DB_URL", "jdbc:postgresql://localhost:5432/ibms"),
                        user = string("DB_USER", "ibms"),
                        password = dbPassword ?: ConfigReader.PLACEHOLDER,
                        poolSize = int("DB_POOL_SIZE", default = 10, range = 1..100),
                    ),
                    jwt = JwtConfig(
                        secret = jwtSecret ?: ConfigReader.PLACEHOLDER,
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
                            (
                                if (hardened) required("BOOTSTRAP_ADMIN_USERNAME", "the username of the seeded sysadmin")
                                else string("BOOTSTRAP_ADMIN_USERNAME", "mikepg")
                                ) ?: ConfigReader.PLACEHOLDER,
                        bootstrapAdminPassword = bootstrapAdminPassword,
                    ),
                    storageLocalDir = string("STORAGE_LOCAL_DIR", "./storage"),
                    corsAllowedHosts = corsAllowedHosts,
                    emailDelivery = emailDelivery ?: EmailDelivery.LOG,
                    smtp = smtp,
                    appUrl = appUrl ?: ConfigReader.PLACEHOLDER,
                    webClientUrl = webClientUrl ?: ConfigReader.PLACEHOLDER,
                    presignSecret = raw("PRESIGN_SECRET")
                        ?: derivePresignSecret(jwtSecret ?: ConfigReader.PLACEHOLDER),
                    topsheet = TopSheetFeatures(
                        rfpFlowEnabled = boolean("TOPSHEET_RFP_FLOW_ENABLED", default = false),
                    ),
                )
            }
        }

        /**
         * Null unless [delivery] is [EmailDelivery.SMTP]. The from-address falls back to
         * SMTP_USERNAME (relays are usually happy to send as the mailbox that
         * authenticated); a queued row that could only ever fail to send is worse than
         * a boot that refuses, so its absence is a problem rather than a warning.
         */
        private fun ConfigReader.smtpFromEnv(delivery: EmailDelivery?): SmtpConfig? {
            val host = raw("SMTP_HOST")
            // Null delivery already reported itself; cascading SMTP requirements off it
            // would send the operator chasing problems that don't exist.
            if (delivery == null) return null
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
            // Checked here rather than on first send: an unreadable pin means every
            // notification fails TLS, and a boot that refuses beats an outbox that
            // silently fills with FAILED rows.
            val trustedCertPath = raw("SMTP_TRUSTED_CERT")
            if (trustedCertPath != null && !File(trustedCertPath).isFile) {
                problem("SMTP_TRUSTED_CERT: no readable file at \"$trustedCertPath\"")
            }
            return SmtpConfig(
                host = host ?: "",
                port = int("SMTP_PORT", default = 587, range = 1..65_535),
                username = username,
                password = raw("SMTP_PASSWORD"),
                startTls = startTls,
                sslOnConnect = sslOnConnect,
                fromEmail = fromEmail ?: "",
                fromName = raw("MAIL_FROM_NAME", "IBMS Notifications"),
                trustedCertPath = trustedCertPath,
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
