-- =====================================================================
--  Account identity is (provider_id, account_number, circuit_id): many
--  accounts legitimately share one account_number under distinct circuits
--  (e.g. one billing account with several ISP circuits). The old partial
--  unique index keyed only on (provider_id, account_number), so a second
--  live circuit sharing an account number was rejected at insert and the
--  bulk import silently dropped it as a "reused" account.
--
--  Rebuild the partial unique index to include circuit_id, NULL-safe via
--  COALESCE so account-number-only accounts (NULL/'' circuit) still dedupe
--  against each other. Uniqueness still applies to live accounts only, so a
--  transferred/inactive account and its live successor may coexist.
-- =====================================================================
DROP INDEX IF EXISTS uq_account_number_per_provider_active;

CREATE UNIQUE INDEX uq_account_number_per_provider_active
    ON accounts (provider_id, account_number, (COALESCE(circuit_id, '')))
    WHERE status NOT IN ('transferred', 'inactive');
