package com.puregoldbe.ibms.infrastructure.config

/**
 * Which environment this process is running as.
 *
 * Defaults to [PROD] when `APP_ENV` is unset, which is the opposite of the usual
 * convention and deliberate: every configuration defect this codebase has had was
 * the same shape — a deployment forgot a variable and silently got the convenient
 * local-dev behaviour. Defaulting to dev reproduces that class of bug at the top
 * level; defaulting to prod turns a forgotten variable into a loud, itemised boot
 * failure. Local dev is unaffected because every dev entry point (.env,
 * docker-compose.yml, the Gradle `run` task, testAppConfig) names `dev` explicitly.
 */
enum class AppEnv {
    DEV,
    STAGING,
    PROD,
    ;

    /**
     * True where the fail-closed rules apply. Staging shares them with prod so that
     * a staging deploy actually rehearses the prod configuration; only the
     * `https://`-scheme check on `APP_URL` is prod-only.
     */
    val isHardened: Boolean get() = this != DEV
}

/**
 * How outbound notifications leave the process. An explicit choice rather than an
 * inference from "is SMTP_HOST set?", because the inference makes a prod deploy that
 * forgets the relay silently send nothing at all. [LOG] has no default in a hardened
 * environment — dropping every notification has to be something you asked for.
 */
enum class EmailDelivery {
    /** Real delivery through the configured relay. Requires SMTP_HOST. */
    SMTP,

    /** Notifications are logged, not sent. Runs the enqueue -> dispatch pipeline end to end. */
    LOG,
}

/**
 * Every configuration problem found during one [AppConfig.fromEnv] pass, reported
 * together. Fixing config one boot at a time is miserable when six variables are
 * missing, so [ConfigReader] accumulates rather than throwing on the first failure.
 */
class ConfigException(val problems: List<String>, appEnv: String) :
    RuntimeException(render(problems, appEnv)) {

    private companion object {
        fun render(problems: List<String>, appEnv: String): String = buildString {
            append("Invalid configuration (APP_ENV=$appEnv) — ${problems.size} problem(s):\n")
            problems.forEach { append("  - $it\n") }
            append("See .env.example and docs/DEPLOY_WINDOWS_2019.md §6.")
        }
    }
}

/**
 * Accumulating environment reader.
 *
 * A failed read records a problem and returns a usable placeholder so construction
 * reaches the end and every remaining key is still checked; [finish] throws before
 * the half-built object can escape. Blank-coalescing matches the original `env()`
 * helper this replaces: a variable set to the empty string is indistinguishable from
 * unset, which is what `FOO=` in a .env file or `${FOO:-}` in compose produces.
 *
 * [getenv] is injected so the whole layer is unit-testable. Kotest has no
 * `withEnvironment` here (no kotest-extensions-jvm dependency) and specs share one
 * JVM, so mutating the real process environment would be flaky.
 */
class ConfigReader(private val getenv: (String) -> String?) {

    private val problems = mutableListOf<String>()

    /**
     * Always consults [getenv], even when a default exists — ConfigKeysSpec observes
     * the key set by recording what a passthrough getenv is asked for.
     */
    fun raw(name: String, default: String? = null): String? =
        getenv(name)?.takeIf { it.isNotBlank() } ?: default

    fun string(name: String, default: String): String = raw(name, default)!!

    /**
     * For a value with no safe default: absent is a problem, not a fallback.
     *
     * Returns null rather than a placeholder so the caller can skip checks that depend
     * on it — otherwise one missing variable reports twice ("must be set" plus
     * "…is not a valid value") and the operator goes hunting for a second defect that
     * does not exist.
     */
    fun required(name: String, hint: String): String? =
        raw(name) ?: run {
            problem("$name: must be set explicitly ($hint)")
            null
        }

    /** Always keyed: a bare NumberFormatException doesn't say which variable was wrong. */
    fun int(name: String, default: Int?, range: IntRange): Int {
        val raw = raw(name, default?.toString())
            ?: return range.first.also { problem("$name: must be set explicitly (an integer in $range)") }
        val parsed = raw.toIntOrNull()
            ?: return range.first.also { problem("$name: expected an integer in $range, got \"$raw\"") }
        if (parsed !in range) problem("$name: expected an integer in $range, got $parsed")
        return parsed
    }

    fun long(name: String, default: Long?, range: LongRange): Long {
        val raw = raw(name, default?.toString())
            ?: return range.first.also { problem("$name: must be set explicitly (an integer in $range)") }
        val parsed = raw.toLongOrNull()
            ?: return range.first.also { problem("$name: expected an integer in $range, got \"$raw\"") }
        if (parsed !in range) problem("$name: expected an integer in $range, got $parsed")
        return parsed
    }

    /**
     * Strict (but case-insensitive): a typo must not silently read as false and, in
     * SMTP_STARTTLS's case, quietly downgrade the connection to plaintext.
     */
    fun boolean(name: String, default: Boolean): Boolean {
        val raw = string(name, default.toString())
        return when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default.also { problem("$name: expected \"true\" or \"false\", got \"$raw\"") }
        }
    }

    fun csv(name: String, default: String = ""): List<String> =
        string(name, default).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Null when absent or unrecognised, so dependent checks can be skipped. */
    fun <T : Enum<T>> enum(name: String, default: T?, values: Array<T>): T? {
        val legal = values.joinToString(", ") { it.name.lowercase() }
        val raw = raw(name, default?.name)
            ?: return null.also { problem("$name: must be set explicitly — one of $legal") }
        return values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: default.also { problem("$name: expected one of $legal, got \"$raw\"") }
    }

    fun problem(message: String) {
        problems += message
    }

    fun check(ok: Boolean, message: String) {
        if (!ok) problem(message)
    }

    /**
     * Builds the config, then throws if anything went wrong. Building first is what
     * lets a single pass report every problem instead of only the earliest.
     */
    fun <T> finish(appEnv: AppEnv, build: () -> T): T {
        val built = build()
        if (problems.isNotEmpty()) throw ConfigException(problems.toList(), appEnv.name.lowercase())
        return built
    }

    companion object {
        /**
         * Stands in for a missing required value so the object can be constructed and
         * every remaining key still checked. Never escapes: [finish] throws first.
         */
        const val PLACEHOLDER = "<unset>"
    }
}

/**
 * Shapes a secret must not have in a hardened environment.
 *
 * This replaces an exact-string match against one built-in default, which every
 * other weak value in this repo evaded — including the placeholders shipped in
 * .env.example and docker-compose.yml, both of which invited being copied verbatim.
 */
internal object SecretRules {
    const val MIN_SECRET_LENGTH = 32

    private val WEAK = Regex(
        "change.?me|secret-not-for|not-for-any-shared|dev-secret|example|localhost|^password|test-secret",
        RegexOption.IGNORE_CASE,
    )

    /** Null when acceptable, otherwise the reason it is not. */
    fun reject(value: String): String? = when {
        value.length < MIN_SECRET_LENGTH ->
            "must be at least $MIN_SECRET_LENGTH characters (was ${value.length}); generate one with `openssl rand -base64 48`"
        WEAK.containsMatchIn(value) ->
            "looks like a placeholder or built-in default — generate a real one with `openssl rand -base64 48`"
        else -> null
    }
}
