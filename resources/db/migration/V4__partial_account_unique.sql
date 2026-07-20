-- =====================================================================
--  Account-number uniqueness must apply only to *live* accounts, so that a
--  transferred account and its active successor can share the same
--  (provider_id, account_number). Replaces the blanket UNIQUE constraint with
--  a partial unique index. (Resolves the transfer vs. uq risk from the plan.)
-- =====================================================================
ALTER TABLE accounts DROP CONSTRAINT uq_account_number_per_provider;

CREATE UNIQUE INDEX uq_account_number_per_provider_active
    ON accounts (provider_id, account_number)
    WHERE status NOT IN ('transferred', 'terminated');
