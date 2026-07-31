-- =====================================================================
--  Invoice numbers are unique PER PROVIDER, not globally.
--
--  `invoice_number` is '<ACRONYM>-YYYYMM-XXXX', where the acronym is derived by
--  InvoiceNumberFormatter from the provider name and truncated to 4 characters, and
--  XXXX comes from invoice_sequences — a sequence kept per provider, each starting
--  at 1. Two distinct providers whose names collapse to the same 4-character acronym
--  ("Converge" / "Convergys" -> CONV) therefore both mint CONV-YYYYMM-0001, and the
--  global UNIQUE from V1 made the second one fail at confirm with 23505 -> 409,
--  permanently: that provider could never confirm a top sheet in that period.
--
--  The acronym is intentionally short and human-readable (Finance reads these), so
--  scope the constraint to the provider rather than lengthening the number.
--
--  invoice_number is nullable (V9 — drafts have none) and provider_id is nullable,
--  and Postgres treats NULLs as distinct in a unique index, so drafts stay
--  unconstrained exactly as before.
-- =====================================================================
ALTER TABLE topsheets DROP CONSTRAINT IF EXISTS topsheets_invoice_number_key;

CREATE UNIQUE INDEX uq_topsheet_invoice_per_provider
    ON topsheets (provider_id, invoice_number);
