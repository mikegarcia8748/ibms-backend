package com.puregoldbe.ibms.domain.service

import com.puregoldbe.ibms.domain.model.AccountUpsertRequest

/**
 * Normalization for the two free-text halves of an account's identity.
 *
 * Identity is `(store_id, provider_id, account_number, COALESCE(circuit_id, ''))`,
 * enforced over live rows by the partial unique index `uq_account_identity_active`
 * (V16) and mirrored by `AccountRepository.existsByIdentity`. The index compares raw
 * column bytes, so anything that reaches it un-normalized becomes a distinct identity:
 *
 *  - **A blank circuit that is not empty.** `existsByIdentity` treats `"  "` as absent
 *    and looks in the no-circuit slot, but the insert wrote the literal spaces and the
 *    index computes `COALESCE('  ','') = '  '` — a third slot neither NULL nor `''`.
 *    A later request with no circuit then passes both the pre-check and the index, and
 *    the same line ends up billed twice. Collapsing blank to null closes that hole.
 *  - **Padding and case on the account number.** The column is `TEXT`, not `CITEXT`
 *    (contrast `users.email`), so `"ACC-1 "`, `"ACC-1"` and `"acc-1"` are three live
 *    identities for one circuit.
 *
 * Trimming is applied to the stored value; case is deliberately NOT folded here, because
 * the account number is printed on top sheets, RFPs and cheque exports and must render as
 * the operator entered it. Case is handled where it belongs — in the identity comparison
 * itself, which is case-insensitive. That makes the application guard strictly stricter
 * than the DB index, which is the safe direction: it rejects a would-be duplicate rather
 * than admitting one the index cannot see.
 */
object AccountIdentityPolicy {

    /** Stored form of an account number: padding removed, case preserved. */
    fun normalizeAccountNumber(raw: String): String = raw.trim()

    /** Stored form of a circuit id: padding removed, blank collapsed to null. */
    fun normalizeCircuitId(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() }

    /** Both of the above applied to an upsert payload, leaving every other field alone. */
    fun normalize(input: AccountUpsertRequest): AccountUpsertRequest = input.copy(
        accountNumber = normalizeAccountNumber(input.accountNumber),
        circuitId = normalizeCircuitId(input.circuitId),
    )
}
