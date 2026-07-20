-- =====================================================================
--  V6 — Provisioned username/password authentication + server-side sessions
--
--  Replaces Google Workspace SSO. A sysadmin provisions the account and hands
--  over a system-generated temporary password; the holder must exchange it for
--  a real password before any session exists (see must_change_password).
--
--  Credentials are never stored in the clear: password_hash is bcrypt, and
--  sessions store only a SHA-256 hash of the refresh token, so a database leak
--  yields nothing that can be replayed against the API.
-- =====================================================================

-- ---------------------------------------------------------------------
--  1. Credential columns on users
-- ---------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN username                 CITEXT,
    ADD COLUMN password_hash            TEXT,
    ADD COLUMN must_change_password     BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN temp_password_expires_at TIMESTAMPTZ,
    ADD COLUMN password_updated_at      TIMESTAMPTZ,
    ADD COLUMN failed_login_attempts    INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until             TIMESTAMPTZ;

COMMENT ON COLUMN users.password_hash IS
    'bcrypt hash of either the temporary or the real password; NULL = no password issued yet, cannot log in.';
COMMENT ON COLUMN users.must_change_password IS
    'true while password_hash holds a temporary password. Login yields a change-password challenge, never a session.';
COMMENT ON COLUMN users.temp_password_expires_at IS
    'Deadline for redeeming the temporary password. Set only while must_change_password is true.';

-- Rows that predate password auth get a username derived from the email
-- local-part: stripped to the allowed alphabet, and suffixed on collision so
-- two different domains sharing a local-part cannot violate the unique index.
WITH candidate AS (
    SELECT id,
           CASE
               WHEN length(regexp_replace(split_part(email::text, '@', 1), '[^a-zA-Z0-9._-]', '', 'g')) >= 3
                   THEN left(regexp_replace(split_part(email::text, '@', 1), '[^a-zA-Z0-9._-]', '', 'g'), 28)
               ELSE 'user' || left(id::text, 8)
           END AS base
    FROM users
),
numbered AS (
    SELECT id, base,
           row_number() OVER (PARTITION BY lower(base) ORDER BY id) AS rn
    FROM candidate
)
UPDATE users u
SET username = CASE WHEN n.rn = 1 THEN n.base ELSE n.base || n.rn::text END
FROM numbered n
WHERE u.id = n.id;

ALTER TABLE users
    ALTER COLUMN username SET NOT NULL,
    ADD CONSTRAINT users_username_key UNIQUE (username),
    ADD CONSTRAINT users_username_format CHECK (username ~ '^[a-zA-Z0-9._-]{3,32}$');

-- Google SSO is gone, and with it the OIDC subject.
ALTER TABLE users DROP COLUMN google_sub;

-- ---------------------------------------------------------------------
--  2. Sessions — one row per issued refresh token
--
--  Rotation inserts a new row and stamps revoked_at on the old one, so the
--  table doubles as an audit trail of a login's lifetime. Reuse of an already
--  rotated token is therefore detectable rather than silent.
-- ---------------------------------------------------------------------
CREATE TABLE sessions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    refresh_token_hash TEXT        NOT NULL UNIQUE,   -- SHA-256 of the opaque token
    issued_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    last_used_at       TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    user_agent         TEXT,
    ip_address         TEXT
);

CREATE INDEX idx_sessions_user ON sessions (user_id);
CREATE INDEX idx_sessions_live ON sessions (user_id) WHERE revoked_at IS NULL;
