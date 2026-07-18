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
TOKEN=$(curl -s -X POST localhost:8080/auth/dev-login \
  -H 'Content-Type: application/json' -d '{"email":"mike.pgmobiledev@gmail.com"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
curl localhost:8080/stores -H "Authorization: Bearer $TOKEN"
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
Paths served at the root (e.g. `/stores`, `/auth/google`) per the API_CONTRACT; JSON;
`Authorization: Bearer <jwt>` on everything except `/auth/google` and `/auth/dev-login`.
All JSON responses use the unified envelope `{result, message, status, data}`; list
endpoints are cursor-paginated. Endpoints + role matrix follow the canonical `API_CONTRACT.md`.
Flyway migrations live in `resources/db/migration` (V1 schema, V2 OCR-template seed, V3
bootstrap admin, V4 partial account-number uniqueness, V5 idempotency keys).

## Status
- **Done:** auth + RBAC, CRUD (users/providers/stores/accounts incl. PUT updates), the billing
  engine (preview/compile/approve/pay, invoice numbering, double-bill guard, POI Excel export),
  account transfer/deactivate + the transfers group, and the daily termination-grace expiry job.
- **Contract structures:** unified response envelope, cursor pagination (`?cursor=&limit=`,
  `nextCursor`), and `Idempotency-Key` replay on money-mutating POSTs (compile/pay/transfers).
- **Activities:** audit log written in-transaction by key mutations; read via `GET /activities`.
- **Attachments:** presigned upload/download over local-disk storage (`PresignPort` seam for S3/GCS).
- **OCR:** batch/extract/templates pipeline behind an `OcrGateway` seam, using a deterministic
  `SimulatedOcrExtractor` stub (swap in real Gemini later); MailerSend email still deferred.
- **Follow-ups:** see [SECURITY.md](SECURITY.md).
