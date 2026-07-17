# IBMS Backend

Authoritative backend for the ISP Billing Management System — **Ktor + PostgreSQL**,
replacing the former React-app-writes-directly-to-Firestore model. PostgreSQL is the
single source of truth; all CRUD, billing math, invoice sequencing, the 30-day grace
logic, and Excel export run server-side behind a role-guarded API.

Built with **Amper** (the `./kotlin` wrapper), Kotlin 2.4, Ktor 3.1, Exposed (JDBC),
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
- The `./kotlin` wrapper downloads its own toolchain/JDK on first run — no local JDK needed.

## Run locally
```bash
docker compose up -d --wait                 # local Postgres 16 on :5432
DEV_AUTH_ENABLED=true ./kotlin run          # Flyway migrates, serves on :8080
```
Then obtain a dev JWT (no Google needed) and call the API:
```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/dev-login \
  -H 'Content-Type: application/json' -d '{"email":"mike.pgmobiledev@gmail.com"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl localhost:8080/api/v1/stores -H "Authorization: Bearer $TOKEN"
```
Run on a different port: `./kotlin run -- -port=8081`.

## Test
```bash
./kotlin test        # Kotest specs: domain + use-case units + Testcontainers integration
```

## Package / Docker
```bash
./kotlin package     # -> build/tasks/_ibms-backend_executableJarJvm/ibms-backend-jvm-executable.jar
docker build -t ibms-backend .
```
The image runs the executable fat jar; all config is via environment variables.

## Configuration
Environment variables (see [.env.example](.env.example)): `DB_URL`/`DB_USER`/`DB_PASSWORD`,
`JWT_SECRET`/`JWT_ISSUER`/`JWT_AUDIENCE`, `GOOGLE_OAUTH_CLIENT_ID`, `DEV_AUTH_ENABLED`,
`STORAGE_LOCAL_DIR`, `CORS_ALLOWED_HOSTS`, and the (currently optional) `GEMINI_API_KEY` /
`MAILERSEND_*`. See [SECURITY.md](SECURITY.md) for the production checklist.

## API
Base path `/api/v1`; JSON; `Authorization: Bearer <jwt>` on everything except `/auth/google`
and `/auth/dev-login`. Endpoints + role matrix follow the canonical `API_CONTRACT.md`.
Flyway migrations live in `resources/db/migration` (V1 schema, V2 OCR-template seed, V3
bootstrap admin, V4 partial account-number uniqueness).

## Status
- **Done:** auth + RBAC, CRUD (users/providers/stores/accounts/attachments), the billing
  engine (preview/compile/approve/pay, invoice numbering, double-bill guard, POI Excel export),
  account transfer/deactivate, and the daily termination-grace expiry job.
- **Deferred:** OCR ingestion (Gemini) and email (MailerSend) with activity logging + reports.
- **Follow-ups:** see [SECURITY.md](SECURITY.md).
