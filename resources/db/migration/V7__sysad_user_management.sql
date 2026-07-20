-- =====================================================================
--  V7 — System Admin User Management
--
--  Adds user status (active/inactive) for enabling/disabling accounts
--  when employees resign. Adds the 'manager' role. Updates the bootstrap
--  sysadmin seed to match the required default identity. Removes the
--  email NOT NULL + unique constraint (email is no longer required for
--  provisioning; username is the primary identifier).
-- =====================================================================

-- ---------------------------------------------------------------------
--  1. Add 'manager' to user_role enum
-- ---------------------------------------------------------------------
ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'manager';

-- ---------------------------------------------------------------------
--  2. Create user_status enum and add column to users
-- ---------------------------------------------------------------------
CREATE TYPE user_status AS ENUM ('active', 'inactive');

ALTER TABLE users
    ADD COLUMN status user_status NOT NULL DEFAULT 'active';

COMMENT ON COLUMN users.status IS
    'Whether the user can log in. Inactive users are blocked from authentication (e.g. resigned employees).';

-- ---------------------------------------------------------------------
--  3. Make email nullable and drop unique constraint
--     Email is no longer required — username is the sole login identifier.
-- ---------------------------------------------------------------------
ALTER TABLE users
    ALTER COLUMN email DROP NOT NULL;

-- Drop the unique constraint on email (it owns the index, so dropping the
-- constraint removes both the constraint and the backing index)
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;

-- ---------------------------------------------------------------------
--  4. Update bootstrap sysadmin seed row to match required defaults
-- ---------------------------------------------------------------------
UPDATE users
SET username         = 'mikepg',
    name             = 'Michael P. Garcia',
    first_name       = 'Michael',
    middle_initial   = 'P.',
    last_name        = 'Garcia',
    employee_number  = '010005529',
    status           = 'active'
WHERE email = 'mike.pgmobiledev@gmail.com'
   OR (username = 'mikepg' AND email IS NULL);
