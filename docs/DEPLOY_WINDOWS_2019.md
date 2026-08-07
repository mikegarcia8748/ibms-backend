# Deployment Runbook — Windows Server 2019 + existing SQL Server 2016

Step-by-step guide to make the IBMS backend run on a **Windows Server 2019** box whose only database engine today is **Microsoft SQL Server 2016**.

> **Read this first — the compatibility verdict:**
> | Component | Verdict |
> |---|---|
> | Windows Server 2019 | ✅ Works, via the **native** path (not Docker). |
> | **SQL Server 2016** | ❌ **Cannot be used by this app.** See below. |
> | **Solution** | Install **PostgreSQL 16** on the same server; leave SQL Server 2016 running as-is for whatever else uses it. |

---

## 1. Why SQL Server 2016 cannot be used (and why porting is the wrong move)

This backend is built for PostgreSQL at every layer — this is not a config switch:

- **Driver is hard-coded:** `driverClassName = "org.postgresql.Driver"` ([Database.kt](../src/adapter/db/Database.kt)); `DB_URL` defaults to `jdbc:postgresql://…` ([AppConfig.kt](../src/infrastructure/config/AppConfig.kt)).
- **Migrations are PostgreSQL SQL** and require two PostgreSQL extensions with **no SQL Server equivalent**: `pgcrypto` (for `gen_random_uuid()` primary keys) and `citext` (case-insensitive email) — [V1__init.sql:18-19](../resources/db/migration/V1__init.sql). Also `UUID` columns and partial unique indexes.
- **Code is Postgres-specific:** it handles `org.postgresql.util.PSQLException` and `PGobject` directly, and uses `flyway-database-postgresql`.

Pointing it at SQL Server 2016 would mean rewriting all 20 migrations in T-SQL, swapping the JDBC driver and Exposed dialect, replacing `gen_random_uuid()`/`citext`/`PGobject` handling, converting partial indexes to filtered indexes, and re-testing every billing-math and idempotency path — **weeks of work, permanent maintenance divergence, and zero existing test coverage for that dialect.** Don't. (An outline is in the [Appendix](#appendix--if-you-are-forced-to-use-sql-server-2016) if you have no choice.)

**PostgreSQL and SQL Server coexist happily** on one Windows host — different service, different port (PG defaults to `5432`, SQL Server to `1433`). Installing PostgreSQL does not touch your SQL Server 2016 instance.

> ⚠️ **Memory contention.** If SQL Server 2016 is *actively serving other workloads* on this box, it likely grabs most of the RAM by default (`max server memory` is unbounded out of the box). Before adding PostgreSQL + the JVM, either **cap SQL Server's `max server memory`** (leave ≥ 3 GB free for Postgres + the JVM + OS) or **add RAM**. The 8 GB Windows figure in [SERVER_SPECS.md](SERVER_SPECS.md) assumes the box is *not* also running a busy SQL Server — if it is, plan for **12–16 GB**.

---

## 2. Target topology (single WS2019 host)

```
Internet ──443──►  Caddy (reverse proxy, TLS)           :443  public
                        │
                        └─►  IBMS app  (fat jar as a Windows Service)   127.0.0.1:8080
                                 │
                                 └─►  PostgreSQL 16  (Windows service)  127.0.0.1:5432
                        attachments →  C:\ibms\storage

  (untouched)   SQL Server 2016                                        127.0.0.1:1433
```

Only `443` is exposed to the network. `8080` and `5432` stay bound to localhost.

---

## 3. Prerequisites — download on the server (or a build machine)

| Item | Where | Notes |
|---|---|---|
| **PostgreSQL 16, Windows x64** | postgresql.org → EDB installer | Officially supports Windows Server 2019. Bundles `pgcrypto` + `citext` contrib and `psql`. |
| **Eclipse Temurin JRE 25 (MSI)** | adoptium.net | Matches the [Dockerfile](../Dockerfile) runtime and `jvmToolchain(25)` in the build. The jar's class files are Java 25, so an older JRE fails with `UnsupportedClassVersionError`. Needed **on the server** to run the jar. |
| **The fat jar** `ibms-backend-all.jar` | build it (see §5) | Build on a **dev/CI machine** and copy it over — the Gradle build needs internet to provision its JDK-25 toolchain, which an app server usually shouldn't have. |
| **WinSW** (`WinSW.NET4.exe`) | github.com/winsw/winsw | Runs the jar as an auto-restarting Windows Service. WS2019 already has the required .NET Framework. |
| **Caddy for Windows** | caddyserver.com | Reverse proxy + automatic HTTPS. Gives you **TLS 1.3**, which WS2019's built-in Schannel/IIS cannot. |

---

## 4. Install & prepare PostgreSQL 16

1. Run the EDB installer. Set a strong **postgres superuser** password, keep port **5432**, accept the default locale, and keep **Command Line Tools** selected. Skip Stack Builder.
2. Confirm the service is running: open `services.msc` → **postgresql-x64-16** should be *Running / Automatic*.
3. Create the app database, a **non-superuser** role, and pre-create the extensions. Open “SQL Shell (psql)” (or `psql -U postgres`) and run:

   ```sql
   CREATE DATABASE ibms;
   CREATE USER ibms WITH PASSWORD 'CHANGE_ME_strong_db_password';
   \c ibms
   -- These two require superuser and are NOT "trusted" extensions,
   -- so the app's ibms role cannot create them itself. Do it now, as postgres:
   CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
   CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive email
   -- PG 15+ no longer grants CREATE on the public schema by default;
   -- Flyway (running as ibms) must be able to create tables:
   GRANT ALL ON SCHEMA public TO ibms;
   GRANT ALL PRIVILEGES ON DATABASE ibms TO ibms;
   ```

   > Why pre-create the extensions: Flyway runs migrations as the `ibms` role, and `V1__init.sql` calls `CREATE EXTENSION IF NOT EXISTS pgcrypto`. `pgcrypto` is not a *trusted* extension, so a non-superuser can't create it — the migration would fail. Creating it here as `postgres` makes that line a harmless no-op.

4. (Optional hardening) Ensure PostgreSQL only listens locally — in `postgresql.conf` keep `listen_addresses = 'localhost'`, and in `pg_hba.conf` use `scram-sha-256` for local connections.

---

## 5. Build the jar and lay out folders

On your dev/CI machine (has JDK + internet):

```bash
./gradlew buildFatJar    # -> build/libs/ibms-backend-all.jar
```

On the server, create the layout and copy the jar in:

```
C:\ibms\
   app\ibms-backend-all.jar
   storage\               (attachments; STORAGE_LOCAL_DIR points here)
   backups\
   service\               (WinSW lives here — see §7)
```

Install the **Temurin JRE 25** MSI, then verify in a new terminal:

```powershell
java -version    # must report 25.x
```

---

## 6. Configuration & secrets

The app reads all config from **environment variables** (the `.env` auto-load only applies to `./gradlew run` in dev — it does nothing for the packaged jar). Set these in the WinSW service config in §7 so they aren't exposed machine-wide.

With `APP_ENV=prod` the app **validates everything at startup and refuses to boot** if something is missing or weak, listing every problem at once. The ✅ rows below are enforced, not merely advised — you cannot accidentally deploy with a placeholder secret or an any-host CORS policy. [.env.example](../.env.example) is the canonical list of every key.

| Variable | Value | Must set? |
|---|---|---|
| `APP_ENV` | `prod` | ✅ **defaults to `prod` if unset, which is deliberate — but set it explicitly** |
| `APP_PORT` | `8080` | the port the process binds |
| `DB_URL` | `jdbc:postgresql://localhost:5432/ibms` | |
| `DB_USER` | `ibms` | |
| `DB_PASSWORD` | the password from §4 | ✅ (the local-dev value `ibms` is rejected) |
| `DB_POOL_SIZE` | `10` | |
| `JWT_SECRET` | 48+ random bytes, base64 | ✅ (rejected if under 32 chars or placeholder-shaped) |
| `PRESIGN_SECRET` | optional; derived from `JWT_SECRET` when blank | set it to rotate attachment links independently of session tokens |
| `BOOTSTRAP_ADMIN_USERNAME` | e.g. `mikepg` | ✅ the seeded admin login |
| `BOOTSTRAP_ADMIN_PASSWORD` | a strong one-time password | ✅ unless `BOOTSTRAP_ADMIN_AUTOGENERATE_PASSWORD=true` |
| `BOOTSTRAP_ADMIN_AUTOGENERATE_PASSWORD` | `true` to generate one and log it once instead | prefer setting the password — the generated one lands in the service log |
| `STORAGE_LOCAL_DIR` | `C:\ibms\storage` | |
| `CORS_ALLOWED_HOSTS` | your Wasm client origin(s), comma-separated — `host[:port]` (e.g. `ibms-client.your-domain.example`), or a full origin such as `https://ibms-client.your-domain.example` | ✅ (empty no longer falls back to any-host — it fails the boot) |
| `APP_URL` | `https://ibms.your-domain.example` | ✅ (must be https and not localhost) — **this API's own origin** |
| `WEB_CLIENT_URL` | `https://ibms-client.your-domain.example` | ✅ (https, not localhost, and **must differ from `APP_URL`**) — the web client's origin, which notification email buttons open |
| `EMAIL_DELIVERY` | `smtp` for real delivery, `log` to only log | ✅ no default — choose deliberately |
| `SMTP_HOST` | the org's internal mail relay | ✅ when `EMAIL_DELIVERY=smtp` |
| `SMTP_PORT` | `587` STARTTLS, `465` implicit TLS, `25` plain | |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | relay credentials; omit both if the relay takes no AUTH | |
| `SMTP_STARTTLS` / `SMTP_SSL` | `true`/`false` — mutually exclusive; `SSL` only with port 465 | a typo in either fails the boot rather than silently downgrading to plaintext |
| `MAIL_FROM_EMAIL` | the generic address the relay will send as | ✅ (defaults to `SMTP_USERNAME`) |
| `MAIL_FROM_NAME` | `IBMS Notifications` | |

> Per [SECURITY.md](../SECURITY.md), `JWT_SECRET`, `DB_PASSWORD` and the bootstrap-admin credential must all be real values before production. These are now enforced by `AppConfig.fromEnv()` rather than left to the operator, so a missing one is a startup failure with a named key.

Generate a strong `JWT_SECRET` in PowerShell:

```powershell
$b = New-Object byte[] 48
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Convert]::ToBase64String($b)
```

---

## 7. Run the jar as a Windows Service (WinSW)

In `C:\ibms\service\`, put `WinSW.NET4.exe` renamed to `ibms.exe`, alongside `ibms.xml`:

```xml
<service>
  <id>ibms-backend</id>
  <name>IBMS Backend</name>
  <description>ISP Billing Management System backend</description>
  <executable>java</executable>
  <arguments>-XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -jar "C:\ibms\app\ibms-backend-all.jar"</arguments>
  <workingdirectory>C:\ibms\app</workingdirectory>
  <startmode>Automatic</startmode>
  <onfailure action="restart" delay="10 sec"/>
  <log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>8</keepFiles></log>

  <env name="APP_ENV" value="prod"/>
  <env name="APP_PORT" value="8080"/>
  <env name="DB_URL" value="jdbc:postgresql://localhost:5432/ibms"/>
  <env name="DB_USER" value="ibms"/>
  <env name="DB_PASSWORD" value="CHANGE_ME_strong_db_password"/>
  <env name="DB_POOL_SIZE" value="10"/>
  <env name="JWT_SECRET" value="PASTE_THE_BASE64_SECRET"/>
  <env name="BOOTSTRAP_ADMIN_USERNAME" value="mikepg"/>
  <env name="BOOTSTRAP_ADMIN_PASSWORD" value="CHANGE_ME_one_time_admin_password"/>
  <env name="STORAGE_LOCAL_DIR" value="C:\ibms\storage"/>
  <!-- host[:port]. A bare host allows http and https; prefix a scheme to pin one. -->
  <env name="CORS_ALLOWED_HOSTS" value="ibms-client.your-domain.example"/>
  <env name="APP_URL" value="https://ibms.your-domain.example"/>
  <env name="WEB_CLIENT_URL" value="https://ibms-client.your-domain.example"/>

  <env name="EMAIL_DELIVERY" value="smtp"/>
  <env name="SMTP_HOST" value="mail-relay.your-domain.example"/>
  <env name="SMTP_PORT" value="587"/>
  <env name="SMTP_USERNAME" value="ibms-notifications@your-domain.example"/>
  <env name="SMTP_PASSWORD" value="CHANGE_ME_relay_password"/>
  <env name="SMTP_STARTTLS" value="true"/>
  <env name="SMTP_SSL" value="false"/>
  <env name="MAIL_FROM_EMAIL" value="ibms-notifications@your-domain.example"/>
  <env name="MAIL_FROM_NAME" value="IBMS Notifications"/>
</service>
```

Install and start (elevated PowerShell):

```powershell
cd C:\ibms\service
.\ibms.exe install
.\ibms.exe start
```

The service is now `Automatic` (starts on boot) and restarts itself on crash. Logs land in `C:\ibms\service\ibms.out.log` / `.err.log`.

---

## 8. First run — Flyway migrates, bootstrap admin is created

On first start the app **runs Flyway migrations automatically** (V1–V20) against the empty `ibms` database. Watch the log:

```powershell
Get-Content C:\ibms\service\ibms.out.log -Wait
```

Look for Flyway applying migrations and the server binding to `:8080`. On this first boot the seeded sysadmin gets `BOOTSTRAP_ADMIN_PASSWORD` installed as a **must-change temporary password**.

Smoke-test the auth flow (temporary password → challenge → set real password), per the [README](../README.md#authentication-flow):

```powershell
# 1) log in with the temporary password -> a change-password challenge
$login = Invoke-RestMethod -Uri http://localhost:8080/auth/login -Method Post -ContentType 'application/json' `
  -Body '{"username":"mikepg","password":"CHANGE_ME_one_time_admin_password"}'
$challenge = $login.data.passwordChange.challengeToken

# 2) set a real password -> this is where the session (tokens) starts
$change = Invoke-RestMethod -Uri http://localhost:8080/auth/password/change -Method Post `
  -Headers @{ Authorization = "Bearer $challenge" } -ContentType 'application/json' `
  -Body '{"newPassword":"Chosen-Passw0rd!"}'
$token = $change.data.session.accessToken

# 3) call a guarded route
Invoke-RestMethod -Uri http://localhost:8080/stores -Headers @{ Authorization = "Bearer $token" }
```

---

## 9. TLS reverse proxy (Caddy — gives you TLS 1.3 on WS2019)

WS2019's Schannel caps at TLS 1.2, so terminate TLS with **Caddy**, which uses its own (Go) TLS stack and supports **TLS 1.3**. Minimal `Caddyfile`:

```
ibms.your-domain.example {
    reverse_proxy 127.0.0.1:8080
}
```

Run Caddy as a service (`caddy run` under WinSW, or the Caddy Windows service instructions). Caddy auto-provisions a Let's Encrypt certificate for the domain — the server must be reachable on port 443 with a valid public DNS record.

> Alternative if you must use IIS: **IIS + Application Request Routing (ARR)** as reverse proxy with **win-acme** for certs — but you'll be limited to **TLS 1.2** on WS2019.

---

## 10. Firewall

```powershell
New-NetFirewallRule -DisplayName "IBMS HTTPS" -Direction Inbound -Protocol TCP -LocalPort 443 -Action Allow
New-NetFirewallRule -DisplayName "ACME HTTP"  -Direction Inbound -Protocol TCP -LocalPort 80  -Action Allow
```

Do **not** open `5432` (PostgreSQL) or `8080` (app) to the network — they are localhost-only by design.

---

## 11. Backups (Task Scheduler)

Nightly PostgreSQL dump + attachments copy. Create `C:\ibms\backup.ps1`:

```powershell
$env:PGPASSWORD = "CHANGE_ME_strong_db_password"
$stamp = Get-Date -Format "yyyyMMdd"
& "C:\Program Files\PostgreSQL\16\bin\pg_dump.exe" -U ibms -Fc -f "C:\ibms\backups\ibms_$stamp.dump" ibms
Copy-Item -Recurse -Force C:\ibms\storage "C:\ibms\backups\storage_$stamp"
# TODO: copy C:\ibms\backups off this host (network share / cloud).
```

Schedule it daily:

```powershell
$action  = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-File C:\ibms\backup.ps1"
$trigger = New-ScheduledTaskTrigger -Daily -At 1:30AM
Register-ScheduledTask -TaskName "IBMS Backup" -Action $action -Trigger $trigger -RunLevel Highest
```

Keep ~14–30 days and test a restore periodically. **The dumps and attachment copies must leave this host** — a backup on the same box doesn't survive its loss.

---

## 12. Day-2 operations

| Task | Command |
|---|---|
| Restart the app | `C:\ibms\service\ibms.exe restart` |
| Stop / start | `ibms.exe stop` / `ibms.exe start` |
| Tail logs | `Get-Content C:\ibms\service\ibms.out.log -Wait` |
| **Update the app** | `ibms.exe stop` → replace `ibms-backend-all.jar` → `ibms.exe start` (Flyway applies any new migrations automatically) |
| Restore DB | `pg_restore -U ibms -d ibms --clean C:\ibms\backups\ibms_YYYYMMDD.dump` |

---

## Checklist

- [ ] PostgreSQL 16 installed; service running; `max server memory` capped on SQL Server 2016 if it's busy
- [ ] `ibms` database + role created; `pgcrypto` + `citext` extensions pre-created; schema privileges granted
- [ ] Temurin **JRE 25** installed (`java -version` → 25)
- [ ] Fat jar built off-server and copied to `C:\ibms\app`
- [ ] `JWT_SECRET` and `BOOTSTRAP_ADMIN_PASSWORD` overridden with strong values; `CORS_ALLOWED_HOSTS` set (bare hosts, no scheme)
- [ ] `WEB_CLIENT_URL` points at the web client, not the API, and its host is in `CORS_ALLOWED_HOSTS` — notification email buttons are built from it
- [ ] WinSW service installed, `Automatic`, started
- [ ] First-run Flyway migration succeeded; auth smoke test passed
- [ ] Caddy TLS in front; only 443/80 open; 5432/8080 localhost-only
- [ ] Nightly backup scheduled and copied off-host

---

## Appendix — if you are *forced* to use SQL Server 2016

Only if installing PostgreSQL is genuinely impossible (organizational mandate). This is a **project fork, not a deployment step**, and is out of scope for the current codebase:

1. Replace the driver dependency with `com.microsoft.sqlserver:mssql-jdbc`; set `driverClassName` accordingly and switch Exposed to its SQL Server dialect.
2. Rewrite **all 14 Flyway migrations** in T-SQL: `UUID` → `uniqueidentifier`; `gen_random_uuid()` → `NEWID()`/`NEWSEQUENTIALID()`; drop `pgcrypto`; replace `citext` with a case-insensitive collation on the relevant columns; partial unique indexes → filtered indexes (`CREATE UNIQUE INDEX … WHERE …`).
3. Replace `PGobject`/`PSQLException` handling and any JSON column access with SQL Server equivalents.
4. Re-verify money math (`numeric(14,2)`), invoice sequencing, the double-bill guard, and `Idempotency-Key` replay against the new engine — none of which the existing Testcontainers (PostgreSQL) suite would cover.

Estimated effort: **weeks**, plus ongoing divergence from the tested PostgreSQL path. **Strong recommendation: install PostgreSQL 16 alongside SQL Server 2016 instead** — it's an afternoon, not a rewrite.
