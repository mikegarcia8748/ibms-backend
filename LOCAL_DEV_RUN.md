# Running the Backend Locally

This guide lets you run the IBMS backend on your host machine with hot-reload
speed — no Docker rebuild required. Only PostgreSQL runs in a container.

---

## Prerequisites

- **JDK 21** (Eclipse Temurin recommended)
- **Docker** (for the Postgres container only)

---

## Quick Start

### 1. Start PostgreSQL

```bash
docker compose -f docker-compose.db.yml up -d
```

Wait until the healthcheck passes:

```bash
docker compose -f docker-compose.db.yml ps
# STATUS should show "healthy"
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
docker compose -f docker-compose.db.yml down
```

---

## Configuration

All runtime config lives in `.env` (already `.gitignore`'d). The Gradle `run`
task loads it automatically — see `loadDotEnv()` in `build.gradle.kts`.

Key values and what they control:

| Variable                | Default (in .env)                          | Notes                          |
|-------------------------|--------------------------------------------|--------------------------------|
| `DB_URL`               | `jdbc:postgresql://localhost:5433/ibms`    | Port 5433 avoids conflict with the main project's Postgres |
| `BCRYPT_COST`          | `4`                                        | Low for dev speed; 12 in prod  |
| `JWT_SECRET`           | `local-dev-secret-not-for-production`      | Never use outside localhost    |
| `BOOTSTRAP_ADMIN_PASSWORD` | *(blank = auto-generated)*             | Logged once on first boot      |
| `STORAGE_LOCAL_DIR`    | `./storage`                                | Uploads stored here on disk    |

---

## Resetting the Database

To wipe all data and start fresh (migrations re-run automatically):

```bash
docker compose -f docker-compose.db.yml down -v   # -v removes the volume
docker compose -f docker-compose.db.yml up -d
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
| `Connection refused` on port 5433 | Postgres isn't running — `docker compose -f docker-compose.db.yml up -d` |
| `FlywayException` on startup | DB may be in a bad state — reset with `down -v` then `up -d` |
| Port 8082 already in use | Stop the conflicting process, or change the `args("-port=...")` in `build.gradle.kts` |
| `.env` not loaded | Make sure you're running via `./gradlew run` (not `java -jar`) |
