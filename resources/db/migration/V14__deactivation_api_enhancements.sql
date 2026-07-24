-- =====================================================================
--  Account Deactivation API Enhancements
--  - Remove unused TERMINATED enum value (type recreation)
--  - Rebuild partial unique index (was referencing 'terminated')
--  - Add partial index for grace-period queries
-- =====================================================================

-- Safety check: ensure no accounts use TERMINATED
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM accounts WHERE status = 'terminated') THEN
    RAISE EXCEPTION 'Cannot remove terminated: rows exist with that status';
  END IF;
END $$;

-- Drop indexes that reference account_status enum BEFORE type change
DROP INDEX IF EXISTS uq_account_number_per_provider_active;
DROP INDEX IF EXISTS idx_accounts_status;

-- Recreate enum without TERMINATED
ALTER TYPE account_status RENAME TO account_status_old;
CREATE TYPE account_status AS ENUM ('active', 'termination_requested', 'transferred', 'inactive');

-- Migrate column to new type
ALTER TABLE accounts
  ALTER COLUMN status DROP DEFAULT,
  ALTER COLUMN status TYPE account_status USING status::text::account_status,
  ALTER COLUMN status SET DEFAULT 'active';

DROP TYPE account_status_old;

-- Rebuild indexes with new enum
CREATE INDEX idx_accounts_status ON accounts (status);

CREATE UNIQUE INDEX uq_account_number_per_provider_active
    ON accounts (provider_id, account_number)
    WHERE status NOT IN ('transferred', 'inactive');

-- Partial index for grace-period expiry queries
CREATE INDEX idx_accounts_termination_grace
    ON accounts (termination_requested_at)
    WHERE status = 'termination_requested';
