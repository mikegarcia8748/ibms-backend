# Security posture — IBMS backend

Server-side enforcement replaces the old Firestore-rules trust boundary. Every
business rule and authorization check runs in the API, not the client.

## In place
- **Authentication.** Sysadmin-provisioned username + password; there is no self-registration. A new account is created with a system-generated temporary password (returned to the admin once, stored only as a bcrypt hash). Logging in with a temporary password does **not** create a session — it returns a single-purpose change-password challenge, and tokens are minted only once the user sets their own password. Passwords are bcrypt (`BCRYPT_COST`, default 12); the policy is enforced in `PasswordPolicy`. Repeated failures lock the account (`MAX_FAILED_LOGINS` / `LOGIN_LOCKOUT_MINUTES`), and a failed login costs the same time whether or not the username exists, so the endpoint is not a user-enumeration oracle.
- **Sessions and tokens.** A session row backs every login. The access token is a short-lived HMAC-256 JWT carrying `sub`, `username`, `email`, `role`, a `sid` (session) claim and a `typ` claim; the refresh token is an opaque 256-bit random value stored only as a SHA-256 fingerprint. Refresh **rotates** — the presented token is revoked as it is exchanged, so a leaked token works at most once. `authenticate(AUTH_SESSION)` guards all routes except `/auth/login`, `/auth/refresh` and `/auth/password/change`. The `typ` claim is pinned per auth provider, so a change-password challenge is rejected everywhere else.
- **No password-bypass route exists.** There is no dev-login or impersonation endpoint in any build. Integration specs seed a user and authenticate over the real `POST /auth/login`, so there is nothing to accidentally ship enabled.
- **Credential change revokes sessions.** Setting or resetting a password revokes every existing session for that user, and the caller is immediately issued a fresh one. A sysadmin password reset therefore cuts off a compromised account in one call.
- **Authorization (RBAC).** `call.authorize(vararg roles)` mirrors the API_CONTRACT role matrix; violations return 403. Domain invariants (e.g. cannot demote the last sysadmin, mandatory proof, unique branch/account) live in use cases and cannot be bypassed by the client.
- **SQL injection.** All data access goes through Exposed with bound parameters; no string-built SQL.
- **Money integrity.** `numeric(14,2)` ↔ BigDecimal end to end; never `Double` in billing math.
- **Error hygiene.** `StatusPages` maps domain errors to `ApiError{error,code}`; unexpected errors are logged server-side and returned as a generic 500 (no stack traces leaked).
- **Transport.** CORS restricted to `CORS_ALLOWED_HOSTS` when set.
- **Startup guards.** The app warns loudly if `JWT_SECRET` is the built-in default.

## Must-do before production
- Set a strong random **`JWT_SECRET`**.
- Set **`BOOTSTRAP_ADMIN_PASSWORD`** rather than letting the backend generate one — the generated value is written to the application log, which is usually shipped somewhere less private than a password belongs. Either way, change it at first login; it must be changed before the account can do anything.
- Set **`CORS_ALLOWED_HOSTS`** to the real client origin(s) — otherwise CORS falls back to `anyHost()` for local dev.
- Terminate TLS in front of the service (Cloud Run / load balancer).

## Known gaps / follow-ups (Phase 7 hardening)
- **Attachment access scoping.** `GET /attachments/{id}` currently allows any authenticated user to download any attachment by id (UUIDs are unguessable, all users are internal staff). Scope downloads to the owning entity's access when the presigned GCS/S3 flow lands.
- **Activity audit log & email outbox** are deferred with Phase 3 (OCR/email). Mutations are not yet written to `activities`.
- **OCR prompt-injection** guard is N/A until the Gemini OCR ingestion (Phase 3) is built; when it is, keep extraction read-only and whitelist parsed account numbers before any write.
- **Rate limiting** and request-size limits are not yet configured.
