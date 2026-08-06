# IBMS Backend

Authoritative backend for the ISP Billing Management System — **Ktor + PostgreSQL**,
replacing the former React-app-writes-directly-to-Firestore model. PostgreSQL is the
single source of truth; all CRUD, billing math, invoice sequencing, the 30-day grace
logic, and Excel export run server-side behind a role-guarded API.

Built with **Gradle** (Kotlin DSL, `./gradlew`), Kotlin 2.3, Ktor 3.4, Exposed (JDBC),
Flyway, HikariCP. Tests use Kotest + MockK + Testcontainers.

## Architecture (Clean Architecture)

```
infrastructure/   Ktor wiring, config, Flyway/Hikari, composition root (Bootstrap)
      ▼ depends on
adapter/          controllers (Ktor) · Exposed repositories · gateways · JWT/RBAC security
      ▼
application/      use cases (interactors) — one per business operation
      ▼
domain/           entities · value objects · ProrationEngine/GracePeriodPolicy · PORTS (interfaces)
```

Dependencies point inward only. Domain has no framework deps, so every business rule is
unit-testable with in-memory fakes. Ports live in `domain/port`; adapters implement them;
`infrastructure/Bootstrap.kt` wires it all with manual constructor DI.

## Prerequisites
- Docker (for local Postgres and Testcontainers)
- JDK 25 (the Gradle toolchain auto-provisions it via the foojay resolver; `./gradlew` downloads Gradle itself on first run).

## Run locally
```bash
cp .env.example .env                                     # sets APP_ENV=dev; see Configuration
docker compose up -d --wait                              # local Postgres 16 on :5432
BOOTSTRAP_ADMIN_PASSWORD='Local-Devpassw0rd' ./gradlew run   # Flyway migrates, serves on :8082
```
`.env` is loaded only by `./gradlew run`; a real environment variable wins over an
entry in it, so the override above takes effect. The packaged jar and Docker read the
process environment instead.
There is no password-bypass shortcut — local dev uses the same flow as production.
The bootstrap admin starts with a temporary password, so sign in and exchange it:
```bash
# 1. log in with the temporary password -> a change-password challenge
CHALLENGE=$(curl -s -X POST localhost:8082/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"mikepg","password":"Local-Devpassw0rd"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["passwordChange"]["challengeToken"])')

# 2. set a real password -> this is where the session starts
TOKEN=$(curl -s -X POST localhost:8082/auth/password/change \
  -H "Authorization: Bearer $CHALLENGE" -H 'Content-Type: application/json' \
  -d '{"newPassword":"Chosen-Passw0rd!"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["session"]["accessToken"])')

curl localhost:8082/stores -H "Authorization: Bearer $TOKEN"
```
On later runs, `POST /auth/login` returns the tokens directly.
Run on a different port: set `APP_PORT` (in `.env` or the environment). Keep `APP_URL`
in step with it — presigned attachment links are built from `APP_URL`. Notification email
buttons are built from `WEB_CLIENT_URL` instead, which is the *web client's* origin; the
two must differ or the app refuses to boot.

## Authentication flow
Accounts are provisioned by a sysadmin — there is no self-registration, and a
temporary password on its own grants no API access.

1. **Provision.** `POST /users` (sysadmin) creates the account and returns a
   generated `temporaryPassword` — the only time it is readable. Relay it to the
   user out-of-band; it expires after `TEMP_PASSWORD_TTL_HOURS`.
2. **Redeem.** `POST /auth/login` with that password answers
   `outcome: "password_change_required"`, `session: null`, and a one-shot
   `passwordChange.challengeToken`. No session exists yet, and the challenge is
   rejected by every other route.
3. **Set a password.** `POST /auth/password/change` (bearer = the challenge token)
   stores the new password and *this* is where the session starts: the response
   carries `outcome: "authenticated"` and the access/refresh pair.

Afterwards `POST /auth/login` returns tokens directly. `POST /auth/refresh`
rotates the pair (the old refresh token is revoked as it is used),
`POST /auth/logout` ends the current session, `POST /auth/logout-all` ends every
session, and `POST /users/{id}/reset-password` (sysadmin) issues a fresh
temporary password and revokes that user's sessions.

On a brand-new database the seeded sysadmin (username `mikepg`) has no password; the
backend installs `BOOTSTRAP_ADMIN_PASSWORD` at first startup, or — when that is unset
and `BOOTSTRAP_ADMIN_AUTOGENERATE_PASSWORD=true` (the default in dev) — generates one
and logs it exactly once. It is a no-op once a password exists, so restarting with the
variable set cannot take over a live account.

## Test
```bash
./gradlew test       # Kotest specs: domain + use-case units + Testcontainers integration
```

## Package / Docker
```bash
./gradlew buildFatJar   # -> build/libs/ibms-backend-all.jar
docker build -t ibms-backend .
```
The image runs the executable fat jar; all config is via environment variables.

## Configuration
[.env.example](.env.example) is the canonical list of every variable, with a comment on
each explaining what it controls and how it behaves per environment. `ConfigKeysSpec`
fails the build if that file and `AppConfig` drift apart, so it cannot go stale.

Everything is read from the process environment at startup and validated in one pass:
a bad or missing value produces a single boot failure listing *every* problem, rather
than one per restart.

**`APP_ENV` is the switch that matters.** It defaults to `prod` when unset, so a
deployment that forgets it fails loudly instead of silently taking the permissive
local-dev path. Under `staging`/`prod`, `JWT_SECRET` (length- and
placeholder-checked), `DB_PASSWORD`, `APP_URL` and `WEB_CLIENT_URL` (https,
non-localhost, and distinct from each other),
`CORS_ALLOWED_HOSTS` (no any-host fallback), `EMAIL_DELIVERY` and the bootstrap-admin
settings must all be set explicitly. Under `dev` the local defaults apply.

See [SECURITY.md](SECURITY.md) for the production checklist.

## API
Paths served at the root (e.g. `/stores`, `/auth/login`) per the API_CONTRACT; JSON;
`Authorization: Bearer <access-token>` on everything except `/auth/login`, `/auth/refresh`,
and `/auth/password/change` (which takes the challenge token instead).
All JSON responses use the unified envelope `{result, message, status, data}`; list
endpoints are cursor-paginated. Endpoints + role matrix follow the canonical `API_CONTRACT.md`.
Flyway migrations live in `resources/db/migration` (V1 schema, V2 OCR-template seed, V3
bootstrap admin, V4 partial account-number uniqueness, V5 idempotency keys, V6 password
auth + sessions).

## Status
- **Done:** auth + RBAC, CRUD (users/providers/stores/accounts incl. PUT updates), the billing
  engine (preview/compile/approve/pay, invoice numbering, double-bill guard, POI Excel export),
  account transfer/deactivate + the transfers group, and the daily termination-grace expiry job.
- **Contract structures:** unified response envelope, cursor pagination (`?cursor=&limit=`,
  `nextCursor`), and `Idempotency-Key` replay on money-mutating POSTs (compile/pay/transfers).
- **Activities:** audit log written in-transaction by key mutations; read via `GET /activities`.
- **Attachments:** presigned upload/download over local-disk storage (`PresignPort` seam for S3/GCS).
- **OCR:** batch/extract/templates pipeline behind an `OcrGateway` seam, using a deterministic
  `SimulatedOcrExtractor` stub (swap in real Gemini later).
- **Email notifications:** event-driven emails for 8 user actions, per-user subscriptions
  (`GET/PUT /users/{id}/notification-subscriptions`), written to the `email_log` outbox inside the
  triggering transaction and drained by a background dispatcher through an `EmailPort` seam
  (`SmtpEmailGateway` over the org relay, `SimulatedEmailGateway` when `EMAIL_DELIVERY=log`).
- **Follow-ups:** see [SECURITY.md](SECURITY.md).
