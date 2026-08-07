package com.puregoldbe.ibms.domain.service

/**
 * Canonicalizes the free-text business keys that arrive from spreadsheets and forms —
 * provider names and store branch codes.
 *
 * Every write path must agree on the canonical form, or the same real-world entity ends
 * up as two rows. Bulk import used to pass provider names through raw while
 * `CreateProviderUseCase` trimmed them, so "Converge" and "CONVERGE" (and "Globe  Telecom"
 * with a double space) each became a distinct provider — and because the account-identity
 * unique index is scoped by `provider_id`, the same circuit under both spellings was not a
 * duplicate and got billed twice.
 *
 * [displayName] deliberately preserves casing: the first writer's spelling is what Finance
 * reads on a top sheet, so it is kept as typed. Case-insensitive *matching* is
 * [matchKey]'s job, backed by the `citext` column type in the DB.
 */
object NameNormalizer {

    private val WHITESPACE = Regex("\\s+")

    /** Trim, then collapse internal whitespace runs to a single space. Casing is preserved. */
    fun displayName(raw: String): String = raw.trim().replace(WHITESPACE, " ")

    /**
     * As [displayName], then uppercased. Branch codes are identifiers rather than prose, so
     * there is no display casing worth preserving and folding them removes a whole class of
     * "qi-central" vs "QI-Central" duplicates at the source.
     */
    fun branchCode(raw: String): String = displayName(raw).uppercase()

    /**
     * The case-insensitive key two names are considered the same under. Used for in-memory
     * caches during an import; the DB enforces the same rule via `citext`.
     */
    fun matchKey(raw: String): String = displayName(raw).lowercase()
}
