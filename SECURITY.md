# Security posture — IBMS backend

Server-side enforcement replaces the old Firestore-rules trust boundary. Every
business rule and authorization check runs in the API, not the client.

## In place
- **Authentication.** Google OIDC ID token verified server-side (`GoogleTokenVerifierAdapter`), exchanged for a backend HMAC-256 JWT carrying `sub`, `email`, and a `role` claim. `authenticate("auth-jwt")` guards all `/api/v1` routes except `/auth/google` and `/auth/dev-login`.
- **Authorization (RBAC).** `call.authorize(vararg roles)` mirrors the API_CONTRACT role matrix; violations return 403. Domain invariants (e.g. cannot demote the last sysadmin, mandatory proof, unique branch/account) live in use cases and cannot be bypassed by the client.
- **SQL injection.** All data access goes through Exposed with bound parameters; no string-built SQL.
- **Money integrity.** `numeric(14,2)` ↔ BigDecimal end to end; never `Double` in billing math.
- **Error hygiene.** `StatusPages` maps domain errors to `ApiError{error,code}`; unexpected errors are logged server-side and returned as a generic 500 (no stack traces leaked).
- **Transport.** CORS restricted to `CORS_ALLOWED_HOSTS` when set.
- **Startup guards.** The app warns loudly if `JWT_SECRET` is the built-in default or `DEV_AUTH_ENABLED` is on.

## Must-do before production
- Set a strong random **`JWT_SECRET`**; set **`GOOGLE_OAUTH_CLIENT_ID`**; set **`DEV_AUTH_ENABLED=false`** (disables `/auth/dev-login`).
- Set **`CORS_ALLOWED_HOSTS`** to the real client origin(s) — otherwise CORS falls back to `anyHost()` for local dev.
- Terminate TLS in front of the service (Cloud Run / load balancer).

## Known gaps / follow-ups (Phase 7 hardening)
- **Attachment access scoping.** `GET /attachments/{id}` currently allows any authenticated user to download any attachment by id (UUIDs are unguessable, all users are internal staff). Scope downloads to the owning entity's access when the presigned GCS/S3 flow lands.
- **Activity audit log & email outbox** are deferred with Phase 3 (OCR/email). Mutations are not yet written to `activities`.
- **OCR prompt-injection** guard is N/A until the Gemini OCR ingestion (Phase 3) is built; when it is, keep extraction read-only and whitelist parsed account numbers before any write.
- **Rate limiting** and request-size limits are not yet configured.
