package com.puregoldbe.ibms.domain.service

import com.puregoldbe.ibms.domain.valueobject.BillingPeriod

/**
 * The two invoice references a TopSheet carries. They are different things and are not
 * interchangeable:
 *
 * - [format] builds the **batch** reference, `<ACRONYM>-YYYYMM-XXXX`, minted once per
 *   topsheet at confirm from the provider's sequence. It identifies the compilation and
 *   appears in the report's meta block and filename.
 * - [forAccount] builds the **per-account** reference, `<ACCT#><MON><YYYY>`, that fills
 *   the INVOICE NUMBER column — one distinct value per row, naming the account and the
 *   rental period being billed.
 *
 * Ported from getProviderAcronym / formatInvoiceNumber in SecretaryDashboard.tsx.
 * (Exact acronym slicing to be reconciled with Finance in Phase 2 golden tests;
 * single-word providers take the first 4 letters, e.g. "Converge" -> "CONV".)
 */
object InvoiceNumberFormatter {

    private val MONTH_ABBREVIATIONS = listOf(
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
    )

    /**
     * The per-account invoice reference for the report's INVOICE NUMBER column: the
     * account number followed by the rental period as `MONYYYY`, with no separator.
     * e.g. `("0821234567", "2026-07")` -> `"0821234567JUL2026"`.
     *
     * This is what the legacy client wrote per row, and it is per-account by design —
     * the batch number from [format] belongs in the meta block, not in every row.
     *
     * A blank account number yields `""` rather than a bare `"JUL2026"` (which is what
     * the TypeScript original produced): the adjacent ACCT# cell is blank in that case
     * too, so an orphan period would only read as a defect.
     */
    fun forAccount(accountNumber: String?, billingPeriod: String): String {
        val account = accountNumber?.trim().orEmpty()
        if (account.isEmpty()) return ""
        // Defensive: billing_period is CHECK-constrained to YYYY-MM in the database, so
        // this only guards against a hand-built value.
        if (!BillingPeriod.isValid(billingPeriod)) return account
        val month = MONTH_ABBREVIATIONS.getOrNull(billingPeriod.substring(5, 7).toInt() - 1) ?: return account
        return "$account$month${billingPeriod.substring(0, 4)}"
    }

    fun acronym(providerName: String): String {
        val clean = providerName.filter { it.isLetterOrDigit() || it == ' ' }.trim().uppercase()
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        // Matches getProviderAcronym in SecretaryDashboard.tsx: multi-word -> initials
        // (first 4); single word -> first 4 chars. E.g. "Converge" -> "CONV",
        // "Philippine Long Distance Telephone" -> "PLDT".
        return when {
            words.isEmpty() -> "INV"
            words.size == 1 -> words[0].take(4)
            else -> words.joinToString("") { it.take(1) }.take(4)
        }
    }

    fun prefix(providerName: String): String = "${acronym(providerName)}-"

    /** e.g. prefix="CONV-", period="2026-08", sequence=7 -> "CONV-202608-0007". */
    fun format(prefix: String, billingPeriod: String, sequence: Int): String =
        "$prefix${billingPeriod.replace("-", "")}-${sequence.toString().padStart(4, '0')}"
}
