# Running the Backend Locally

This guide lets you run the IBMS backend on your host machine with hot-reload
speed — no Docker rebuild required. Only PostgreSQL runs in a container, and it is
the **same** Postgres the full `docker-compose.yml` stack uses (one centralized dev
database), so data is shared whether you run on the host or in Docker.

---

## Prerequisites

- **JDK 25** — the Gradle build provisions it automatically via the foojay toolchain
  resolver, so you don't need to install it by hand (first build downloads it).
- **Docker** (for the Postgres container only)

---

## Quick Start

### 1. Start PostgreSQL

Start just the `db` service from the main compose file (the app service stays down —
you'll run it on the host):

```bash
docker compose up -d db
```

Wait until the healthcheck passes:

```bash
docker compose ps
# db STATUS should show "healthy"
```

### 2. Run the backend

```bash
./gradlew run
```

The app starts at **http://localhost:8082** (port 8082 avoids conflict with the
Docker-compose app container on 8080). Flyway migrations run automatically
on first connection.

### 3. Stop everything

```bash
# Stop the app: press Ctrl+C in the terminal running ./gradlew run
# Stop Postgres (data preserved):
docker compose down
```

---

## Configuration

All runtime config lives in `.env` (already `.gitignore`'d). The Gradle `run`
task loads it automatically — see `loadDotEnv()` in `build.gradle.kts`.

Key values and what they control:

| Variable                | Default (in .env)                          | Notes                          |
|-------------------------|--------------------------------------------|--------------------------------|
| `APP_ENV`              | `dev`                                      | Relaxes the fail-closed checks. **Unset means `prod`**, which refuses to boot without real secrets |
| `APP_PORT`             | `8082`                                     | Port the local JVM binds; 8080 is the compose app container |
| `APP_URL`              | `http://localhost:8082`                    | Must match `APP_PORT` — presigned attachment links are built from it |
| `DB_URL`               | `jdbc:postgresql://localhost:5432/ibms`    | Centralized dev Postgres from `docker-compose.yml` (shared with the Docker app) |
| `BCRYPT_COST`          | `4`                                        | Low for dev speed; floors at 10 outside dev |
| `JWT_SECRET`           | `local-dev-secret-not-for-production`      | Never use outside localhost; rejected outside dev |
| `BOOTSTRAP_ADMIN_PASSWORD` | *(blank = auto-generated in dev)*      | Logged once on first boot       |
| `EMAIL_DELIVERY`       | `log`                                      | Notifications logged, not sent. `smtp` requires `SMTP_HOST` |
| `SMTP_TRUSTED_CERT`    | `./certs/smtp-relay.pem`                   | Required for the internal relay — its cert is self-signed. See below |
| `STORAGE_LOCAL_DIR`    | `./storage`                                | Uploads stored here on disk    |

A variable already set in your shell wins over the `.env` entry, so
`BOOTSTRAP_ADMIN_PASSWORD='...' ./gradlew run` overrides the file. Only `./gradlew run`
reads `.env` at all — for the packaged jar, load it yourself:
`set -a; source .env; set +a; java -jar build/libs/ibms-backend-all.jar`

---

## Sending Real Email Locally

`EMAIL_DELIVERY=log` is the default and keeps the enqueue → dispatch pipeline running
without putting anything on the wire. To send for real through the company relay:

```
EMAIL_DELIVERY=smtp
SMTP_HOST=mbox2.puregold.com.ph
SMTP_PORT=587
SMTP_USERNAME=puregold\ibms
SMTP_STARTTLS=true
SMTP_SSL=false
SMTP_TRUSTED_CERT=./certs/smtp-relay.pem
```

Four things that are not obvious:

- **The relay is internal only.** `mbox2.puregold.com.ph` resolves to a LAN address, so
  this works on the office network or a VPN into it and nowhere else.
- **`SMTP_TRUSTED_CERT` is not optional here.** The relay's certificate is self-signed;
  without the pin the TLS upgrade fails and every notification lands as a `FAILED` row.
- **The certificate does not name `SMTP_HOST`, and that is fine.** It names only
  `MBOX2.puregold.local`. The pin accepts one exact certificate, which settles identity
  more tightly than a name would — see [certs/README.md](certs/README.md).
- **`SMTP_USERNAME` is `DOMAIN\user`, not the email address.** Exchange rejects the SMTP
  address here with `535 5.7.3 Authentication unsuccessful`. `MAIL_FROM_EMAIL` stays the
  mailbox address.

If sends fail, the reason is on the `email_log` row's `provider_response` — the gateway
flattens the exception's cause chain, so the failures stay distinguishable:

| `provider_response` contains | Cause |
|---|---|
| `PKIX path building failed` | `SMTP_TRUSTED_CERT` unset or unreadable |
| `does not match the pin` | Certificate reissued — re-export `certs/smtp-relay.pem` |
| `535 5.7.3 Authentication unsuccessful` | Credentials, or `SMTP_USERNAME` not in `DOMAIN\user` form |
| `550 relay access denied` | Mailbox not permitted to relay or to send as `MAIL_FROM_EMAIL` — an IT-side grant, not a config fix |

---

## Resetting the Database

To wipe all data and start fresh (migrations re-run automatically):

```bash
docker compose down -v   # -v removes the volume
docker compose up -d db
./gradlew run
```

---

## IDE Run Configuration (IntelliJ IDEA)

1. **Run → Edit Configurations → + → Gradle**
2. Set **Run** to `run`
3. Ensure **Delegate IDE run actions to Gradle** is checked (so `.env` is loaded)
4. Click **Apply**

Alternatively, create a plain **Kotlin** run configuration targeting
`com.puregoldbe.ibms.MainKt` and manually add the env vars from `.env`.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `Connection refused` on port 5432 | Postgres isn't running — `docker compose up -d db` |
| `FlywayException` on startup | DB may be in a bad state — reset with `down -v` then `up -d` |
| Port 8082 already in use | Stop the conflicting process, or change the `args("-port=...")` in `build.gradle.kts` |
| `.env` not loaded | Make sure you're running via `./gradlew run` (not `java -jar`) |
