package com.puregoldbe.ibms.infrastructure.config

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
    val expiresMinutes: Long,
)

data class AppConfig(
    val db: DbConfig,
    val jwt: JwtConfig,
    val googleOauthClientId: String?,
    val devAuthEnabled: Boolean,
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
                expiresMinutes = env("JWT_EXPIRES_MINUTES", "720")!!.toLong(),
            ),
            googleOauthClientId = env("GOOGLE_OAUTH_CLIENT_ID"),
            devAuthEnabled = env("DEV_AUTH_ENABLED", "false")!!.toBoolean(),
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
