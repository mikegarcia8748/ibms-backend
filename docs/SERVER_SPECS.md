# IBMS Backend — Server Specifications

Recommended hosting specs for the IBMS backend (**Ktor + PostgreSQL**, fat-jar in Docker).

- **Workload:** internal ISP billing management tool, **3–4 staff users/month**.
- **Scope:** sizing for the runtime, not the feature set. See [README.md](../README.md) for architecture and [SECURITY.md](../SECURITY.md) for the production hardening checklist.

---

## TL;DR — recommended spec

For 3–4 users a month, load is effectively idle. The sizing floor is set by the **JVM baseline + PostgreSQL + Apache POI Excel export spikes**, *not* by concurrency. One small VM running everything comfortably covers it.

| | Recommended (single VM, all-in-one) |
|---|---|
| **vCPU** | 2 |
| **RAM** | 4 GB |
| **Disk** | 80 GB SSD |
| **OS** | Ubuntu 24.04 LTS (or 22.04 LTS) — 64-bit x86_64 |
| **Runtime** | Docker + Docker Compose |
| **Topology** | App container + PostgreSQL 16 container + reverse proxy (TLS) on the same host |

> **Rough cost:** ~US$20–30/month on any mainstream provider (DigitalOcean/Linode/Hetzner/Vultr, GCP `e2-medium`, AWS `t3.medium`, Azure `B2s`). Hetzner CX22 (~€5) also fits if budget is the priority.

Two vCPU and 4 GB is the sweet spot: it leaves headroom for a POI export while Flyway/Postgres and the OS run, without paying for capacity 3–4 users will never touch.

---

## Why this size (workload characteristics)

The number of *human* users (3–4) barely matters. What sets the floor:

1. **JVM footprint.** Ktor (Netty) + Exposed + HikariCP + Flyway + Apache POI. A steady-state heap of ~512 MB plus metaspace/thread stacks/native means the app container realistically wants **~1–1.5 GB** allocated.
2. **Apache POI Excel export.** `poi-ooxml` builds workbooks in memory. Topsheet/billing exports spike heap transiently (hundreds of MB for large sheets). This is the single most memory-hungry operation and the reason to give the JVM real headroom rather than the bare minimum.
3. **PostgreSQL 16.** Small dataset, but wants ~512 MB–1 GB to cache working set and run comfortably. `DB_POOL_SIZE=10` (HikariCP) — trivial for Postgres.
4. **OS + Docker + reverse proxy.** Budget ~0.5–1 GB.

Add those up → 4 GB RAM is comfortable, 2 GB is tight (risk of OOM during a POI export + migration), 8 GB is overkill at this scale.

**CPU** is near-idle. 2 vCPU handles JVM JIT warmup, a concurrent export, and Postgres without contention. 1 vCPU works but makes cold starts and exports sluggish.

**Data volume** is driven by the number of ISP subscriber *accounts/stores* and monthly billing rows — **not** the 3–4 operators. Even tens of thousands of accounts with years of monthly invoices stay well under a few GB in Postgres. Disk is dominated by **attachments** (OCR receipts, proof images on local-disk storage) — see [Storage & growth](#storage--growth).

---

## Component breakdown (resource budget)

| Component | vCPU | RAM (allocate) | JVM heap | Notes |
|---|---|---|---|---|
| App (Ktor fat jar) | ~1 | 1.5 GB | `-Xmx1g` | Headroom for POI export spikes |
| PostgreSQL 16 | ~0.5 | 1 GB | — | `shared_buffers ≈ 256 MB` |
| Reverse proxy (Caddy/Nginx) | minimal | 128 MB | — | TLS termination |
| OS + Docker | ~0.5 | 0.5–1 GB | — | |
| **Total** | **2** | **~4 GB** | | |

**Suggested JVM flags** (set on the app container / `JAVA_TOOL_OPTIONS`):

```bash
-XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError
```

Using `MaxRAMPercentage` lets the heap track the container memory limit instead of hard-coding `-Xmx`. `ExitOnOutOfMemoryError` makes the container restart cleanly on an OOM rather than limping.

---

## Sizing tiers

| Tier | vCPU / RAM / Disk | When |
|---|---|---|
| **Minimum** | 1 / 2 GB / 40 GB | Demo/staging only. Risk: OOM if a large POI export overlaps a Flyway migration. |
| **Recommended** ✅ | 2 / 4 GB / 80 GB | Production for 3–4 users. Comfortable headroom. |
| **Comfortable** | 2 / 8 GB / 160 GB | If attachment volume is heavy or you want to co-locate other tooling. |

You will **not** need to scale up for more users at this range. Scale up only if (a) attachment storage outgrows the disk, or (b) the subscriber-account dataset grows into the hundreds of thousands with heavy reporting.

---

## Software prerequisites & versions

| Software | Version | Notes |
|---|---|---|
| **Java runtime** | **JRE 21** | The [Dockerfile](../Dockerfile) builds and runs on `eclipse-temurin:21`. ⚠️ `build.gradle.kts` sets `jvmToolchain(25)` for local dev — the deployed artifact is Java 21. If you deploy the jar outside Docker, use **JDK/JRE 21** to match the image, or reconcile the toolchain first. |
| **PostgreSQL** | **16** | Per `docker-compose.yml`. Flyway migrations (V1–V20) run automatically at app startup. |
| **Docker** | 24+ | |
| **Docker Compose** | v2 | |
| **Reverse proxy** | Caddy 2 / Nginx | For TLS — **the app does not terminate TLS itself** (see SECURITY.md). |

The app is a self-contained fat jar (`ibms-backend-all.jar`); no external services are required at this scale. The `SMTP_*` / `MAIL_FROM_*` relay settings are needed only when `EMAIL_DELIVERY=smtp`; set `EMAIL_DELIVERY=log` and notifications are logged rather than sent. Outbound mail goes through the org's internal SMTP relay, so there is still no external service to run.

> ⚠️ **Database engine: PostgreSQL only — not SQL Server / MySQL / Oracle.**
> The backend is hard-wired to PostgreSQL and **cannot run against Microsoft SQL Server (any version — 2016, 2019, 2022)**. It uses the PostgreSQL JDBC driver ([Database.kt:20](../src/adapter/db/Database.kt)), PostgreSQL-only migration SQL (`CREATE EXTENSION pgcrypto` + `citext`, `gen_random_uuid()`, `UUID` columns), the `flyway-database-postgresql` module, and PostgreSQL-specific error handling (`PSQLException`, `PGobject`). Pointing it at SQL Server would require a full rewrite of the driver layer and all migrations — not supported. **If your infrastructure standard is SQL Server, install a separate PostgreSQL instance for this app instead** (they coexist fine on one host). See the step-by-step [Windows Server 2019 + SQL Server 2016 runbook](DEPLOY_WINDOWS_2019.md).
>
> **Minimum PostgreSQL version:** the schema itself works on **PG 11+** (no PG-13+ features are used), but PostgreSQL **11 (EOL Nov 2023)** and **12 (EOL Nov 2024)** are past end-of-life and receive no security patches — unacceptable for billing data. The app is developed and tested against **PostgreSQL 16**; deploy on **16** (or at least a currently-supported release) to avoid both the EOL risk and untested version skew.

---

## Network & TLS

- **Public (internet-facing):** `443` (HTTPS) and `80` (HTTP → HTTPS redirect), served by the reverse proxy.
- **Internal only (do not expose):** app `8080` and PostgreSQL `5432` — bind to `127.0.0.1` / the Docker network, never `0.0.0.0` publicly.
- **TLS** is mandatory in production; terminate it at the reverse proxy (or a cloud load balancer). Caddy auto-provisions Let's Encrypt certs with near-zero config.
- **CORS:** set `CORS_ALLOWED_HOSTS` to the real Wasm-client host(s), bare `host[:port]` with no scheme. If unset, CORS falls back to `anyHost()` (dev-only) — see SECURITY.md.
- A static public IP + DNS record for the client origin.

### Production env vars that must be set (from SECURITY.md)
- `JWT_SECRET` — strong random string (not the built-in default).
- `BOOTSTRAP_ADMIN_PASSWORD` — set it explicitly rather than letting the backend generate + log one.
- `CORS_ALLOWED_HOSTS` — the real client host(s), bare and without a scheme.
- `APP_URL` / `WEB_CLIENT_URL` — this API's origin and the web client's origin. Must differ; notification email buttons are built from the latter.
- `DB_URL` / `DB_USER` / `DB_PASSWORD` — production Postgres credentials.

---

## Storage & growth

| Consumer | Location | Growth driver |
|---|---|---|
| PostgreSQL data | Docker volume (`ibms-pgdata`) | Subscriber accounts + monthly billing rows. Small — likely < a few GB. |
| Attachments | Docker volume (`ibms-storage`, `STORAGE_LOCAL_DIR`) | OCR receipts / proof images. **The main disk consumer.** |
| App logs | Container / host | Logback; rotate to bound growth. |
| DB backups | Host / off-host | See below. |

80 GB SSD is generous for this scale. Attachments currently use the **local-disk** adapter; there is a `PresignPort` seam to move to S3/GCS later, which would offload the largest growth item off the VM disk entirely (recommended if attachment volume ever becomes significant).

---

## Backup & disaster recovery

Even at 3–4 users, the data is billing records — back it up.

- **Database:** nightly `pg_dump` (or `pg_dumpall`) to off-host storage; retain ~14–30 days. A weekly restore test is cheap insurance.
- **Attachments:** rsync/snapshot the `ibms-storage` volume off-host on the same cadence.
- **Volumes:** if the provider offers block-storage snapshots, enable daily snapshots of the data disk.
- **Config:** keep the production `.env` in a secrets manager, not just on the box.
- **Recovery target:** with nightly dumps + volume snapshots, RPO ≈ 24 h, RTO ≈ under an hour (redeploy the image, restore dump, remount attachments).

---

## Alternative: managed / serverless (GCP)

The codebase already anticipates Cloud Run (referenced in SECURITY.md), and this is a Firestore-migration project, so GCP is a natural fit. This trades a flat VM cost for pay-per-use + managed Postgres.

| Component | Recommendation |
|---|---|
| **App** | **Cloud Run** — 1 vCPU, **1 GB memory (2 GB if POI exports are large)**, min instances **0 or 1**. Set min = 1 to avoid JVM cold-start latency for the handful of daily requests. |
| **Database** | **Cloud SQL for PostgreSQL 16** — smallest shared-core tier (`db-f1-micro`/`db-g1-small`) is enough; enable automated backups + PITR. |
| **Storage** | **GCS bucket** for attachments (swap the local-disk adapter via the `PresignPort` seam). |
| **TLS / ingress** | Handled by Cloud Run automatically. |
| **Secrets** | Secret Manager for `JWT_SECRET`, DB creds, `BOOTSTRAP_ADMIN_PASSWORD`. |

> ⚠️ **Cloud Run cold starts:** a JVM fat jar takes several seconds to boot, and **Flyway runs migrations on every startup**. With `min-instances=0`, the first request after idle pays that cost and could contend on migrations if multiple instances start at once. For 3–4 users, **`min-instances=1`** (one always-warm instance) is the pragmatic choice — it's cheap at this size and removes the cold-start pain.

**When to pick which:** a single VM is simpler and cheaper to reason about at this scale (**recommended default**). Go managed if you want zero server maintenance, already run on GCP, or expect attachment volume to grow (GCS offload).

---

## Alternative: Windows Server

Fully supported. The deployable is a pure-JVM fat jar and PostgreSQL 16 has a first-class Windows build, so nothing in the stack is Linux-specific (verified: storage uses `java.nio.Path`, no shell/`ProcessBuilder` calls). The trade-offs vs Linux are a **heavier OS baseline** (bump the RAM), **licensing cost**, and a choice of **two deployment paths**.

### Adjusted sizing (OS overhead is higher than Linux)

| Tier | vCPU / RAM / Disk | Notes |
|---|---|---|
| **Minimum** | 2 / 6 GB / 100 GB | Use **Server Core** (no desktop) to reclaim ~1–2 GB. |
| **Recommended** ✅ | 2 / 8 GB / 120 GB | Windows Server 2022/2025 Standard. |

Why more than the 4 GB Linux figure: Windows Server (Desktop Experience) idles around **~2 GB** before your app, and the OS + updates want **~40–60 GB** of disk. The JVM/Postgres/POI budget from the [component breakdown](#component-breakdown-resource-budget) is unchanged — it's stacked on a bigger base. CPU stays near-idle; 2 vCPU is plenty.

> ⚠️ **Licensing.** Windows Server is licensed **per physical core (16-core minimum for Standard)** and requires **CALs** for accessing users/devices. This is a real recurring cost that Linux (free) avoids — factor it in, especially since the app itself needs no Windows-only features.

### Path A — native, no Docker *(recommended on Windows)*

Simplest and lightest on Windows; skips the Docker-on-Windows virtualization layer entirely.

1. **Install [Eclipse Temurin **JRE 21**](https://adoptium.net/) (MSI)** — matches the [Dockerfile](../Dockerfile) runtime. (Same JDK-25-vs-21 caveat as above; the built jar targets 21.)
2. **Install [PostgreSQL 16](https://www.postgresql.org/download/windows/)** (EDB Windows installer) — runs as a Windows service automatically.
3. **Run the fat jar as a Windows Service** using [WinSW](https://github.com/winsw/winsw) or [NSSM](https://nssm.cc/) so it auto-starts on boot and restarts on crash:
   ```
   java -XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -jar ibms-backend-all.jar
   ```
   Set production config (`DB_URL`, `JWT_SECRET`, `STORAGE_LOCAL_DIR=C:\ibms\storage`, `CORS_ALLOWED_HOSTS`, `BOOTSTRAP_ADMIN_PASSWORD`, …) as **machine environment variables** or in the service wrapper's config — the `.env` auto-load only applies to `./gradlew run` in local dev, not the packaged jar.
4. **TLS:** put **IIS + ARR (Application Request Routing)** in front as a reverse proxy, with [win-acme](https://www.win-acme.com/) for free Let's Encrypt certs — or run **Caddy for Windows** (auto-HTTPS). Keep app `8080` and Postgres `5432` bound to `127.0.0.1`.

### Path B — Docker with Linux containers (WSL2)

Reuses [docker-compose.yml](../docker-compose.yml) unchanged, but the image (`eclipse-temurin:21`) is **Linux-based**, so it runs as a **Linux container** on Windows via the **WSL2 backend** — Windows containers cannot run it.

- Adds WSL2 VM overhead (~1–2 GB) on top of the OS → keep RAM at **8 GB**.
- **Docker Desktop is paid** for larger orgs (>250 employees or >US$10M revenue). Free alternatives: Docker CE inside a WSL2 distro, or Mirantis Container Runtime.
- Only worth it if you specifically want container parity with the Linux deployment; otherwise **Path A is cleaner on Windows**.

### Windows Server 2019 — specific caveats

Server 2019 works, but it is aging and constrains your options. Prefer **Server 2022/2025**; if you must use **2019**:

- **Support horizon.** Mainstream support ended Jan 9, 2024; **extended (security) support ends January 9, 2029.** Still patched today, but plan to migrate before then — don't stand up a new billing system on an OS with a fixed sunset if you can avoid it.
- **Docker with Linux containers is *not* a supported path on 2019.** The image (`eclipse-temurin:21`) is Linux-based and needs WSL2/Hyper-V, but **WSL2 was only officially supported on Windows Server starting with 2022.** So **Path B does not apply on 2019** — do not try to run `docker-compose.yml` here. **Use Path A (native jar as a Windows Service + native PostgreSQL).** This is simpler regardless.
- **No TLS 1.3 at the OS layer.** Server 2019's Schannel caps at **TLS 1.2** (TLS 1.3 arrived in Server 2022). TLS 1.2 is still secure, so this is not a blocker — but if you want TLS 1.3, terminate TLS with **Caddy** (bundles its own TLS stack) rather than IIS, which is bound to Schannel.
- **What still works fine on 2019:** Temurin **JRE 21**, native **PostgreSQL 16** (EDB installer), running the jar as a Windows Service, and IIS/Caddy as the reverse proxy.

### Backup on Windows
Same principles as Linux: schedule `pg_dump` via **Task Scheduler**, snapshot the `C:\ibms\storage` attachment folder off-host, and keep the production config in a secrets store — not on the box.

---

## Summary

For **3–4 users/month**, provision one small host running the app + PostgreSQL 16 + a TLS reverse proxy:

> **Linux (recommended):** 2 vCPU · 4 GB RAM · 80 GB SSD · Ubuntu 24.04 LTS, via Docker Compose.
> **Windows Server:** 2 vCPU · 8 GB RAM · 120 GB disk · Server 2022/2025 — run the jar natively as a Windows Service (Path A).

The spec is governed by the JVM + POI Excel export footprint, not user count — which is why "a few users" still warrants 4 GB (Linux) / 8 GB (Windows) rather than a 1 GB micro-instance. Everything above the Minimum tier is durability and headroom, not throughput. **Linux is cheaper and simpler here** (no licensing, lighter base, matches the Docker image); choose Windows Server only if it's your standard hosting platform.
