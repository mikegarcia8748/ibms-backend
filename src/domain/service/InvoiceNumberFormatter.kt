package com.puregoldbe.ibms.domain.service

/**
 * Derives the per-provider invoice prefix/acronym and formats invoice numbers as
 * `<ACRONYM>-YYYYMM-XXXX`. Ported from getProviderAcronym / formatInvoiceNumber in
 * SecretaryDashboard.tsx. (Exact acronym slicing to be reconciled with Finance in
 * Phase 2 golden tests; single-word providers take the first 4 letters, e.g.
 * "Converge" -> "CONV".)
 */
object InvoiceNumberFormatter {

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
