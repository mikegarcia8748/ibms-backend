-- =====================================================================
--  V8 — Remove 'payables' role
--
--  The payables function is absorbed into the finance role.
--  PostgreSQL does not support ALTER TYPE … DROP VALUE, so we
--  recreate the enum after migrating existing rows.
-- =====================================================================

-- ---------------------------------------------------------------------
--  1. Migrate any existing payables users to finance
-- ---------------------------------------------------------------------
UPDATE users SET role = 'finance' WHERE role::text = 'payables';

-- ---------------------------------------------------------------------
--  2. Recreate the user_role enum without 'payables'
--     Must drop the column default first — it references the old type.
-- ---------------------------------------------------------------------
ALTER TABLE users ALTER COLUMN role DROP DEFAULT;

ALTER TABLE users ALTER COLUMN role TYPE text USING role::text;

DROP TYPE user_role;

CREATE TYPE user_role AS ENUM ('sysadmin', 'secretary', 'finance', 'manager', 'pending');

ALTER TABLE users ALTER COLUMN role TYPE user_role USING role::user_role;

ALTER TABLE users ALTER COLUMN role SET DEFAULT 'pending';
