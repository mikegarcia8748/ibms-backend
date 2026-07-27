-- =====================================================================
--  Account identity is scoped by STORE: (store_id, provider_id, account_number,
--  circuit_id). One account number legitimately recurs across stores (and across
--  circuits within a store), and circuit_id is often empty — so keying only on
--  (provider_id, account_number, circuit_id) (see V15) collapsed distinct accounts
--  that share an account number + empty circuit at different stores into one.
--
--  Rebuild the partial unique index to include store_id and keep provider_id, with
--  circuit_id NULL-safe via COALESCE (NULL and '' collapse to one no-circuit slot).
--  Still live-accounts-only, so a transferred/inactive account and its live successor
--  (e.g. after a store transfer) may coexist.
-- =====================================================================
DROP INDEX IF EXISTS uq_account_number_per_provider_active;

CREATE UNIQUE INDEX uq_account_identity_active
    ON accounts (store_id, provider_id, account_number, (COALESCE(circuit_id, '')))
    WHERE status NOT IN ('transferred', 'inactive');
