-- =====================================================================
--  Bootstrap sysadmin so the system is usable immediately after migrate.
--  Mirrors the legacy Firestore bootstrap admin (firestore.rules).
--  Idempotent: safe to run in any environment. Pair with DEV_AUTH_ENABLED
--  locally to obtain a JWT via POST /api/v1/auth/dev-login without Google.
-- =====================================================================
INSERT INTO users (email, name, first_name, last_name, role)
VALUES ('mike.pgmobiledev@gmail.com', 'Mike (Bootstrap Admin)', 'Mike', 'Admin', 'sysadmin')
ON CONFLICT (email) DO NOTHING;
