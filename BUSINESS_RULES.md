# IBMS Backend — Business Rules Documentation

## Purpose

This document is the single source of truth for the business rules enforced by the
IBMS (ISP Billing Management System) backend. It is organized by module and, for each
rule, states **what** the rule is, **where** it is enforced in the codebase, and
**why** it exists where the rationale is apparent from the code.

Rules are documented factually based on what the code actually does. Where a rule is
**not** currently enforced (a known gap), it is explicitly marked `NOT ENFORCED`.

> **Scope note:** This document covers the `account-change-requests` worktree, which
> adds the Account Change Requests module on top of the base IBMS backend. The Bulk
> Import module is documented from the main repository for completeness, as that use
> case is not present in this worktree.

---

## Module: Authentication & Authorization

### Role Definitions and Permissions

| Role | Wire value | Description |
|------|-----------|-------------|
| `SYSADMIN` | `sysadmin` | Global superuser — admitted to every endpoint regardless of the per-route allow-list. |
| `SECRETARY` | `secretary` | Store/account CRUD, transfers, deactivation, topsheet compile/preview, OCR extraction, change-request submission/cancellation. |
| `PAYABLES` | `payables` | Account create/update (shares the account write path with Secretary). |
| `FINANCE` | `finance` | TopSheet approval, payment, and Excel export. |
| `MANAGER` | `manager` | Approve/reject account change requests. |
| `PENDING` | `pending` | Default role for newly provisioned users. No specific write permissions beyond authenticated access. |

- **Rule:** `SYSADMIN` is a global superuser and bypasses all per-route role checks.
  - **Enforcement point:** `ApplicationCall.authorize()` in `AuthSupport.kt` — if `user.role == SYSADMIN`, the allow-list is skipped.
  - **Rationale:** Per-route allow-lists carry only the non-sysadmin roles; sysadmin is always admitted so the list stays concise and the superuser is never accidentally locked out.

- **Rule:** Endpoints specify their allowed roles; an authenticated user whose role is not in the allow-list (and is not `SYSADMIN`) receives `403 Forbidden`.
  - **Enforcement point:** `ApplicationCall.authorize(vararg allowed: UserRole)` in `AuthSupport.kt`; controllers call it per route.
  - **Rationale:** Defense-in-depth — the JWT carries the role claim, and the server re-checks it on every request rather than trusting the client.

### Per-Route RBAC Matrix

| Endpoint | Allowed Roles |
|----------|--------------|
| `POST /auth/login` | Public (no auth) |
| `POST /auth/refresh` | Public (no auth) |
| `POST /auth/password/change` | Password-change challenge token (`typ=pwd_change`) |
| `GET /auth/me` | Any authenticated |
| `POST /auth/password` | Any authenticated |
| `POST /auth/logout` | Any authenticated |
| `POST /auth/logout-all` | Any authenticated |
| `GET /users` | `SYSADMIN` |
| `POST /users` | `SYSADMIN` |
| `POST /users/{id}/reset-password` | `SYSADMIN` |
| `PATCH /users/{id}/role` | `SYSADMIN` |
| `PATCH /users/{id}/status` | `SYSADMIN` |
| `GET /providers` | Any authenticated |
| `POST /providers` | `SYSADMIN` |
| `PUT /providers/{id}` | `SYSADMIN` |
| `POST /providers/{id}/deactivate` | `SYSADMIN` |
| `GET /stores` | Any authenticated |
| `GET /stores/floating-accounts` | Any authenticated |
| `GET /stores/{id}` | Any authenticated |
| `POST /stores` | `SECRETARY` |
| `PUT /stores/{id}` | `SECRETARY` |
| `POST /stores/{id}/deactivate` | `SECRETARY` |
| `GET /accounts` | Any authenticated |
| `GET /accounts/{id}` | Any authenticated |
| `POST /accounts` | `SECRETARY`, `PAYABLES` |
| `PUT /accounts/{id}` | `SECRETARY`, `PAYABLES` |
| `POST /accounts/{id}/transfer` | `SECRETARY` |
| `POST /accounts/{id}/deactivate` | `SECRETARY` |
| `POST /accounts/{id}/change-requests` | `SECRETARY` |
| `GET /accounts/{id}/change-requests` | Any authenticated |
| `GET /accounts/{id}/change-requests/{requestId}` | Any authenticated |
| `POST /accounts/{id}/change-requests/{requestId}/approve` | `MANAGER` |
| `POST /accounts/{id}/change-requests/{requestId}/reject` | `MANAGER` |
| `POST /accounts/{id}/change-requests/{requestId}/cancel` | `SECRETARY` |
| `GET /transfers` | Any authenticated |
| `POST /transfers` | `SECRETARY` |
| `GET /topsheets` | Any authenticated |
| `POST /topsheets/preview` | `SECRETARY` |
| `POST /topsheets/compile` | `SECRETARY` |
| `GET /topsheets/{id}` | Any authenticated |
| `GET /topsheets/{id}/details` | Any authenticated |
| `POST /topsheets/{id}/approve` | `FINANCE` |
| `POST /topsheets/{id}/pay` | `FINANCE` |
| `GET /exports/topsheet/{id}.xlsx` | `SECRETARY`, `FINANCE` |
| `POST /attachments/presign/upload` | Any authenticated |
| `GET /attachments/{id}/presign/download` | Any authenticated |
| `PUT /attachments/{id}/blob` | Public (token-gated) |
| `GET /attachments/{id}/blob` | Public (token-gated) |
| `GET /activities` | Any authenticated |
| `POST /ocr/extract` | `SECRETARY` |
| `GET /ocr/batches` | `SECRETARY` |
| `GET /ocr/batches/{id}/rows` | `SECRETARY` |
| `GET /ocr/templates` | `SYSADMIN`, `SECRETARY` |
| `POST /ocr/templates` | `SYSADMIN` |
| `PUT /ocr/templates/{id}` | `SYSADMIN` |
| `POST /admin/jobs/expire-grace` | `SYSADMIN` |

### Password Policy

- **Rule:** Passwords must be at least 12 characters long.
  - **Enforcement point:** `PasswordPolicy.validate()` in `PasswordPolicy.kt`.
  - **Rationale:** Length contributes more to strength than character-class rules; 12 is the floor.

- **Rule:** Passwords must be at most 72 characters long.
  - **Enforcement point:** `PasswordPolicy.validate()` in `PasswordPolicy.kt`.
  - **Rationale:** bcrypt silently truncates input past 72 bytes; a longer password would give a false sense of added strength.

- **Rule:** Passwords must contain at least one uppercase letter, one lowercase letter, and one digit.
  - **Enforcement point:** `PasswordPolicy.validate()` in `PasswordPolicy.kt`.

- **Rule:** Passwords must not contain whitespace.
  - **Enforcement point:** `PasswordPolicy.validate()` in `PasswordPolicy.kt`.

- **Rule:** Passwords must not contain the user's username (case-insensitive).
  - **Enforcement point:** `PasswordPolicy.validate()` in `PasswordPolicy.kt`.

- **Rule:** A new password must differ from the current password.
  - **Enforcement point:** `applyNewPassword()` in `AuthUseCases.kt` — verifies the new password against the existing hash.
  - **Rationale:** Prevents users from "changing" to the same password, which would defeat the purpose of rotation.

- **Rule:** The password policy applies to both the forced first-login change and self-service rotation.
  - **Enforcement point:** `applyNewPassword()` is the single shared path called by `CompleteFirstLoginUseCase` and `ChangeOwnPasswordUseCase`.

### Session Management

- **Rule:** Two JWT token types exist, told apart by the `typ` claim: `access` (session tokens) and `pwd_change` (password-change challenges). A token of the wrong type is rejected by the opposing auth provider.
  - **Enforcement point:** `configureAuthentication()` in `AuthSupport.kt` — `AUTH_SESSION` provider admits only `typ=access`; `AUTH_PASSWORD_CHANGE` provider admits only `typ=pwd_change`.
  - **Rationale:** A password-change challenge is given to someone who has proven only temporary-password possession; it must never be accepted as a session token.

- **Rule:** Access tokens carry `sub` (userId), `sid` (sessionId), `username`, and `role` claims. Challenge tokens carry only `sub` (userId) — deliberately no role or session.
  - **Enforcement point:** `JwtService.accessToken()` and `JwtService.passwordChangeChallenge()` in `AuthSupport.kt`.
  - **Rationale:** A challenge token has nothing an authorization check could act on.

- **Rule:** Refresh token rotation is unconditional — the presented refresh token is revoked at refresh time, and a new token pair is issued.
  - **Enforcement point:** `RefreshSessionUseCase` in `AuthUseCases.kt` — calls `sessions.revoke(session.id, now)` before issuing a new session.
  - **Rationale:** A leaked token is usable at most once; the legitimate client's next refresh fails loudly rather than silently sharing a session.

- **Rule:** Changing a password (first-login or self-service) revokes all existing sessions for that user, then issues a fresh one.
  - **Enforcement point:** `applyNewPassword()` in `AuthUseCases.kt` — calls `sessions.revokeAllForUser()` after storing the new hash.
  - **Rationale:** Whoever knew the old password — including the sysadmin who issued the temporary one — loses any session they had. The caller immediately gets a fresh session on the device they are holding.

- **Rule:** Refresh tokens are stored only as a SHA-256 fingerprint (hash), never in plaintext.
  - **Enforcement point:** `SessionIssuer.start()` in `AuthUseCases.kt` stores `secrets.fingerprint(refreshToken)`; DB column `sessions.refresh_token_hash` (V6 migration).
  - **Rationale:** A database leak yields nothing that can be replayed against the API.

- **Rule:** Session rows record `userAgent` and `ipAddress` for audit purposes. `X-Forwarded-For` is stored but never trusted for authorization decisions.
  - **Enforcement point:** `clientContext()` in `AuthController.kt`; `SessionIssuer.start()` in `AuthUseCases.kt`.

- **Rule:** If a password reset has occurred since a refresh token was issued, the refresh is rejected and the session is revoked, forcing a fresh login.
  - **Enforcement point:** `RefreshSessionUseCase` in `AuthUseCases.kt` — checks `credentials.mustChangePassword`; if true, revokes and throws `reauthentication_required`.

- **Rule:** Logout is idempotent — revoking an already-revoked session is not an error.
  - **Enforcement point:** `LogoutUseCase` in `AuthUseCases.kt`.

- **Rule:** `POST /auth/logout-all` revokes every session for the caller ("sign out everywhere").
  - **Enforcement point:** `LogoutEverywhereUseCase` in `AuthUseCases.kt`.

### First-Login Flow

- **Rule:** Accounts are provisioned by a sysadmin with a system-generated temporary password. There is no self-registration.
  - **Enforcement point:** `ProvisionUserUseCase` in `UserUseCases.kt`; controller RBAC (`SYSADMIN` only).
  - **Rationale:** Only authorized administrators can create user accounts.

- **Rule:** Logging in with a temporary password does **not** start a session. It returns a `PASSWORD_CHANGE_REQUIRED` outcome with a change-password challenge token.
  - **Enforcement point:** `LoginUseCase` in `AuthUseCases.kt` — checks `credentials.mustChangePassword`.
  - **Rationale:** Possession of a temporary password never yields API access on its own.

- **Rule:** The change-password challenge token is good for exactly one call to `POST /auth/password/change`. Redeeming it sets the user's password and creates the first session.
  - **Enforcement point:** `CompleteFirstLoginUseCase` in `AuthUseCases.kt`; `AUTH_PASSWORD_CHANGE` provider in `AuthSupport.kt`.
  - **Rationale:** The user becomes authenticated only at the moment they set their own password.

- **Rule:** A temporary password past its deadline (72 hours by default) is treated as invalid even if it hashes correctly. It must be reissued by a sysadmin.
  - **Enforcement point:** `LoginUseCase` and `CompleteFirstLoginUseCase` — both check `credentials.isTempPasswordExpiredAt(now)`.
  - **Rationale:** Temporary passwords travel out-of-band (chat/phone) and are short-lived.

### Account Lockout

- **Rule:** After 5 failed login attempts (default), the account is locked for 15 minutes (default).
  - **Enforcement point:** `LoginUseCase.registerFailedAttempt()` in `AuthUseCases.kt`; `SessionPolicy` defaults (`maxFailedLogins=5`, `lockoutDuration=15.minutes`).
  - **Rationale:** Brute-force protection. Configurable via `AppConfig`.

- **Rule:** A locked account is rejected before password verification.
  - **Enforcement point:** `LoginUseCase` — checks `credentials.isLockedAt(now)` before `hasher.verify()`.

- **Rule:** Successful login clears all failed login attempts.
  - **Enforcement point:** `LoginUseCase` — calls `users.clearLoginFailures(credentials.userId)` after successful verification.

- **Rule:** Every login rejection (wrong username, wrong password) surfaces the same message: "invalid username or password".
  - **Enforcement point:** `LoginUseCase.invalidCredentials()` in `AuthUseCases.kt`.
  - **Rationale:** Distinguishing "no such user" from "wrong password" would turn the endpoint into a username oracle.

- **Rule:** A decoy bcrypt hash is computed for non-existent usernames so a miss costs the same wall-clock time as a wrong password.
  - **Enforcement point:** `LoginUseCase` — `decoyHash` is computed lazily and verified when `credentials?.passwordHash == null`.
  - **Rationale:** Prevents timing-based username enumeration.

- **Rule:** Users with `status = INACTIVE` cannot log in (returns `403 Forbidden`).
  - **Enforcement point:** `LoginUseCase` — checks `user.status == UserStatus.INACTIVE` after successful password verification.
  - **Rationale:** Resigned or disabled employees must be blocked from authentication.

---

## Module: User Management

### User Provisioning Workflow

- **Rule:** Only `SYSADMIN` can provision users.
  - **Enforcement point:** Controller RBAC (`UserController.kt` — `call.authorize(UserRole.SYSADMIN)`).

- **Rule:** The temporary password is generated server-side, bcrypt-hashed, and returned to the admin exactly once. It cannot be recovered later.
  - **Enforcement point:** `ProvisionUserUseCase` in `UserUseCases.kt` — `secrets.temporaryPassword()` + `hasher.hash()`.
  - **Rationale:** Only the hash is persisted; a lost temp password must be reset, not recovered.

- **Rule:** The temporary password expires after 72 hours (default).
  - **Enforcement point:** `ProvisionUserUseCase` — `expiresAt = now + policy.temporaryPasswordTtl`; `SessionPolicy.temporaryPasswordTtl = 72.hours`.

- **Rule:** Newly provisioned users default to role `PENDING` and status `ACTIVE`.
  - **Enforcement point:** `ProvisionUserRequest` defaults (`role = UserRole.PENDING`, `status = UserStatus.ACTIVE`).

### Role Assignment Rules

- **Rule:** Only `SYSADMIN` can change a user's role.
  - **Enforcement point:** Controller RBAC (`UserController.kt`).

- **Rule:** The last remaining `SYSADMIN` cannot be demoted to another role.
  - **Enforcement point:** `UpdateUserRoleUseCase` in `UserUseCases.kt` — checks `target.role == SYSADMIN && newRole != SYSADMIN && users.countByRole(SYSADMIN) <= 1`.
  - **Rationale:** Prevents the system from locking itself out of user administration.

- **Rule:** Only `SYSADMIN` can reset a user's password (issue a new temporary password).
  - **Enforcement point:** Controller RBAC (`UserController.kt`).
  - **Rationale:** Password reset revokes all sessions and doubles as a credential-leak cutoff.

- **Rule:** Resetting a password revokes all existing sessions for that user.
  - **Enforcement point:** `ResetUserPasswordUseCase` in `UserUseCases.kt` — calls `sessions.revokeAllForUser()`.

### Status Transitions

- **Rule:** User status can be toggled between `ACTIVE` and `INACTIVE` by `SYSADMIN` only.
  - **Enforcement point:** `UpdateUserStatusUseCase` in `UserUseCases.kt`; controller RBAC.
  - **Rationale:** Used to disable accounts when employees resign without deleting the user record.

- **Rule:** An `INACTIVE` user is blocked from logging in.
  - **Enforcement point:** `LoginUseCase` in `AuthUseCases.kt`.
  - **Rationale:** Prevents resigned employees from accessing the system.

- **Rule:** User records are never deleted; they are deactivated.
  - **Enforcement point:** No delete endpoint exists; only `PATCH /users/{id}/status`.
  - **Rationale:** Preserves audit trail and referential integrity.

### Username Policy

- **Rule:** Usernames must be 3–32 characters long and contain only letters, digits, dot (`.`), underscore (`_`), or hyphen (`-`).
  - **Enforcement point:** `UsernamePolicy.normalize()` in `UsernamePolicy.kt`; DB CHECK constraint `users_username_format` in V6 migration.
  - **Rationale:** The domain service produces a readable error; the DB is the last line of defence.

- **Rule:** Usernames are normalized to lowercase (trim + lowercase) before validation and storage.
  - **Enforcement point:** `UsernamePolicy.normalize()` in `UsernamePolicy.kt`.
  - **Rationale:** The `username` column is `CITEXT` (case-insensitive), so two accounts cannot differ by case alone.

- **Rule:** Usernames must be unique.
  - **Enforcement point:** `ProvisionUserUseCase` — `users.existsByUsername(username)` check; DB unique constraint `users_username_key` in V6 migration.

---

## Module: Providers

### Provider Lifecycle

- **Rule:** Creating a provider requires `SYSADMIN` role.
  - **Enforcement point:** Controller RBAC (`ProviderController.kt`).

- **Rule:** Provider name is required (non-blank) and is trimmed before storage.
  - **Enforcement point:** `CreateProviderUseCase` in `ProviderUseCases.kt`.

- **Rule:** Provider name must be unique.
  - **Enforcement point:** DB unique constraint on `providers.name` (V1 migration).

- **Rule:** `paymentScheduleDay` must be between 1 and 31 (inclusive).
  - **Enforcement point:** `CreateProviderUseCase` and `UpdateProviderUseCase` in `ProviderUseCases.kt`; DB CHECK constraint in V1 migration.

- **Rule:** Creating a provider also seeds its `invoice_sequences` row (prefix = acronym derived from provider name) in the same transaction.
  - **Enforcement point:** `CreateProviderUseCase` — calls `sequences.seed(provider.id, InvoiceNumberFormatter.prefix(provider.name))`.
  - **Rationale:** An invoice number can be minted for the provider immediately after creation.

- **Rule:** Updating a provider requires `SYSADMIN` role. If `name` is provided it cannot be blank; if `paymentScheduleDay` is provided it must be 1..31.
  - **Enforcement point:** `UpdateProviderUseCase` in `ProviderUseCases.kt`; controller RBAC.
  - **Rationale:** Partial update — `null` means "no change".

- **Rule:** Deactivating a provider requires `SYSADMIN` role. It sets status to `INACTIVE` and records `deactivatedAt`.
  - **Enforcement point:** `DeactivateProviderUseCase` in `ProviderUseCases.kt`; controller RBAC.

### Provider Constraints

- **Rule (NOT ENFORCED):** A provider can be deactivated while it still has active accounts referencing it. There is no check for dependent accounts before deactivation.
  - **Enforcement point:** `DeactivateProviderUseCase` performs no account-existence check.
  - **Impact:** Deactivated providers may still appear in billing compilations if their accounts are still `ACTIVE` and eligible. This is a known gap.

### Payment Schedule Rules

- **Rule:** `paymentScheduleDay` is a `SMALLINT` constrained to 1..31, representing the day of the month on which the provider expects payment.
  - **Enforcement point:** DB CHECK constraint (`payment_schedule_day BETWEEN 1 AND 31`); use-case validation.
  - **Rationale:** Used for Finance planning; no automated billing-schedule logic currently acts on it.

---

## Module: Stores

### Store Lifecycle

- **Rule:** Creating a store requires `SECRETARY` role.
  - **Enforcement point:** Controller RBAC (`StoreController.kt`).

- **Rule:** `branchCode` is required (non-blank) and must be unique across all stores.
  - **Enforcement point:** `CreateStoreUseCase` and `UpdateStoreUseCase` in `StoreUseCases.kt` — `stores.existsByBranchCode()` check; DB unique index `idx_stores_branch_code` (V1 migration).
  - **Rationale:** Branch code is a business key.

- **Rule:** Store name is required (non-blank).
  - **Enforcement point:** `CreateStoreUseCase` and `UpdateStoreUseCase`.

- **Rule:** `proofOfInstallationId` is required and must reference an existing attachment.
  - **Enforcement point:** `CreateStoreUseCase` and `UpdateStoreUseCase` — `attachments.exists()` check; DB NOT NULL FK `fk_store_install_proof` (V1 migration).
  - **Rationale:** Mandatory proof of installation invariant, formerly enforced in app code, now in DB.

- **Rule:** Updating a store requires `SECRETARY` role and enforces the same validations as creation.
  - **Enforcement point:** `UpdateStoreUseCase`; controller RBAC.

- **Rule:** Closing (deactivating) a store requires `SECRETARY` role. A closure `reason` (non-blank) and a valid `proofOfClosureId` (must exist) are required.
  - **Enforcement point:** `CloseStoreUseCase` in `StoreUseCases.kt` — validates reason and proof; DB nullable FK `fk_store_closure_proof`.
  - **Rationale:** Closure is an audited action requiring documented justification and proof.

### Store Types

- **Rule:** Store type is either `PUREGOLD` or `PUREMART`.
  - **Enforcement point:** `StoreType` enum in `DomainModels.kt`; DB enum `store_type` (V1 migration).

### Proof of Installation/Closure Requirements

- **Rule:** `proof_of_installation_id` is `NOT NULL` — every store must have an installation proof.
  - **Enforcement point:** DB column definition (V1 migration) + FK constraint.

- **Rule:** `proof_of_closure_id` is nullable — only set when the store is closed.
  - **Enforcement point:** DB column definition (V1 migration) + `CloseStoreUseCase`.

### Floating Accounts

- **Rule:** When a store is closed, the response includes the list of active accounts at that store ("floating accounts").
  - **Enforcement point:** `CloseStoreUseCase` — calls `accounts.listActiveByStore(id)` and returns them in `CloseStoreResult`.
  - **Rationale:** Alerts the operator that these accounts need manual resolution (transfer or deactivation).

- **Rule:** Floating accounts are also queryable via `GET /stores/floating-accounts` (any authenticated role).
  - **Enforcement point:** `GetFloatingAccountsUseCase` in `StoreUseCases.kt` — calls `accounts.listFloating()`.
  - **Rationale:** Provides a system-wide view of accounts whose store is closed/inactive but that are still active.

- **Rule (NOT ENFORCED):** Closing a store does not automatically deactivate or transfer its active accounts. No automated action is taken on floating accounts.
  - **Enforcement point:** `CloseStoreUseCase` only returns the list; no status changes on accounts.
  - **Impact:** Floating accounts remain `ACTIVE` and billable until manually resolved.

---

## Module: Accounts

### Account Lifecycle

- **Rule:** Creating an account requires `SECRETARY` or `PAYABLES` role.
  - **Enforcement point:** Controller RBAC (`AccountController.kt` — `call.authorize(UserRole.SECRETARY, UserRole.PAYABLES)`).

- **Rule:** `accountNumber` is required (non-blank).
  - **Enforcement point:** `CreateAccountUseCase` and `UpdateAccountUseCase` in `AccountUseCases.kt`.

- **Rule:** `rate` (Monthly Recurring Charge / MRC) must be greater than zero.
  - **Enforcement point:** `CreateAccountUseCase` and `UpdateAccountUseCase` — `Money.isPositive(input.rate)` check; DB column `rate NUMERIC(14,2) NOT NULL`.
  - **Rationale:** A zero or negative MRC is not a valid billing relationship.

- **Rule:** `providerId` must reference an existing provider.
  - **Enforcement point:** `CreateAccountUseCase` and `UpdateAccountUseCase` — `providers.findById()` check; DB FK `accounts.provider_id REFERENCES providers(id)`.

- **Rule:** `storeId` must reference an existing store.
  - **Enforcement point:** `CreateAccountUseCase` and `UpdateAccountUseCase` — `stores.findById()` check; DB FK `accounts.store_id REFERENCES stores(id)`.

- **Rule:** `installationDate` is required.
  - **Enforcement point:** `AccountUpsertRequest` model (non-nullable field); DB column `installation_date DATE NOT NULL`.
  - **Rationale:** Installation date drives proration calculations.

- **Rule:** Updating an account requires `SECRETARY` or `PAYABLES` role and enforces the same validations as creation.
  - **Enforcement point:** `UpdateAccountUseCase`; controller RBAC.
  - **Note:** The update use case does **not** record an activity log entry (`NOT ENFORCED` — see Activity & Audit module).

### Status Transitions

```
ACTIVE ──(deactivate)──→ TERMINATION_REQUESTED ──(grace expiry)──→ INACTIVE
ACTIVE ──(transfer)──→ TRANSFERRED  (+ new ACTIVE account created)

TERMINATED: enum value exists but no use case currently sets this status.
```

- **Rule:** `ACTIVE → TERMINATION_REQUESTED`: requests deactivation via `POST /accounts/{id}/deactivate`. Requires `SECRETARY` role, a valid `proofId`, and sets `terminationRequestedAt` to the current time.
  - **Enforcement point:** `DeactivateAccountUseCase` in `AccountLifecycleUseCases.kt` — calls `accounts.markTerminationRequested()`.

- **Rule:** `TERMINATION_REQUESTED → INACTIVE`: occurs automatically when the 30-day grace period expires (via the daily job or manual trigger).
  - **Enforcement point:** `ExpireGracePeriodAccountsUseCase` in `ExpireGracePeriodAccountsUseCase.kt`.

- **Rule:** `ACTIVE → TRANSFERRED`: occurs via the transfer endpoint. The old account is marked `TRANSFERRED` and a new `ACTIVE` account is created at the new store.
  - **Enforcement point:** `TransferAccountUseCase` in `AccountLifecycleUseCases.kt`.

- **Rule (NOT ENFORCED):** The `TERMINATED` status exists in the enum and DB type but no code path sets it. Accounts that complete the grace period go to `INACTIVE`, not `TERMINATED`.
  - **Impact:** `TERMINATED` is effectively dead code / reserved for future use.

### Required vs Optional Fields

| Field | Required? | Notes |
|-------|-----------|-------|
| `accountNumber` | Yes | Non-blank |
| `providerId` | Yes | FK to providers |
| `storeId` | Yes | FK to stores |
| `rate` | Yes | MRC, must be > 0 |
| `installationDate` | Yes | Drives proration |
| `circuitId` | No | |
| `planName` | No | |
| `serviceType` | No | |
| `speed` | No | |
| `contractDurationMonths` | No | |
| `contractStartDate` | No | |
| `contractEndDate` | No | |
| `notes` | No | |
| `installationFee` | No | |
| `billingPeriodLabel` | No | Descriptive, e.g. "1st to 30th" |
| `subscriptionProofIds` | No | List of attachment IDs (0..n) |

### Uniqueness Constraints

- **Rule:** `(provider_id, account_number)` is unique among **live** accounts only — i.e., accounts whose status is not `transferred` or `terminated`.
  - **Enforcement point:** DB partial unique index `uq_account_number_per_provider_active` (V4 migration); use-case check `accounts.existsByProviderAndNumber()` in `CreateAccountUseCase`.
  - **Rationale:** A transferred account and its active successor can share the same `(provider_id, account_number)` because the old one is marked `TRANSFERRED`.

### Relationship to Stores and Providers

- **Rule:** Every account belongs to exactly one provider and one store.
  - **Enforcement point:** DB NOT NULL FKs `accounts.provider_id` and `accounts.store_id` (V1 migration).

- **Rule:** Account subscription proofs are stored in a many-to-many join table `account_attachments` (account_id, attachment_id).
  - **Enforcement point:** DB table `account_attachments` (V1 migration); `AccountUpsertRequest.subscriptionProofIds`.

---

## Module: Account Change Requests (NEW)

### Submission Rules

- **Rule:** Only `SECRETARY` can submit a change request.
  - **Enforcement point:** Controller RBAC (`AccountChangeRequestController.kt` — `call.authorize(UserRole.SECRETARY)`).

- **Rule:** A change request can only be submitted for an account whose status is `ACTIVE`.
  - **Enforcement point:** `SubmitAccountChangeRequestUseCase` in `AccountChangeRequestUseCases.kt` — `if (account.status != AccountStatus.ACTIVE) throw Conflict`.
  - **Rationale:** Changes to terminated/transferred/inactive accounts are not meaningful.

- **Rule:** At least one field must be changed — submitting an empty request (all fields null) is rejected.
  - **Enforcement point:** `SubmitAccountChangeRequestUseCase` — checks all input fields for null.

### Editable Fields and Constraints

| Editable Field | Constraint |
|---------------|------------|
| `accountNumber` | No validation beyond non-null (uniqueness checked at approval) |
| `installationDate` | No additional validation |
| `rate` | Must be > 0 if provided (`Money.isPositive`) |
| `providerId` | Must reference an existing provider if provided |
| `circuitId` | No validation |
| `planName` | No validation |
| `proofAttachmentId` | Must reference an existing attachment if provided |

- **Enforcement point:** `SubmitAccountChangeRequestUseCase` in `AccountChangeRequestUseCases.kt`.

### One-Pending-Per-Account Rule

- **Rule:** An account may have at most one `PENDING` change request at a time.
  - **Enforcement point:** DB partial unique index `idx_acr_one_pending_per_account ON account_change_requests (account_id) WHERE status = 'pending'` (V8 migration).

### Auto-Cancel Behavior

- **Rule:** If a `PENDING` change request already exists for an account when a new one is submitted, the existing request is automatically cancelled.
  - **Enforcement point:** `SubmitAccountChangeRequestUseCase` — calls `requests.cancel(existing.id, clock.now())` and logs `account_change_request.auto_cancelled`.
  - **Rationale:** The new submission supersedes the old one; the DB unique index would otherwise reject the insert.

### Approval Workflow

- **Rule:** Only `MANAGER` can approve a change request.
  - **Enforcement point:** Controller RBAC (`AccountChangeRequestController.kt` — `call.authorize(UserRole.MANAGER)`).

- **Rule:** Only `PENDING` requests can be approved.
  - **Enforcement point:** `ApproveAccountChangeRequestUseCase` — `if (request.status != PENDING) throw Conflict`.

- **Rule:** On approval, the proposed values are merged into the account using delta-merge logic: each `*New` field from the request overrides the account's current value; `null` means "keep current".
  - **Enforcement point:** `ApproveAccountChangeRequestUseCase` — constructs `AccountUpsertRequest` with `request.*New ?: account.*`.
  - **Fields affected:** `accountNumber`, `circuitId`, `providerId`, `planName`, `rate`, `installationDate`. Unchanged fields (`serviceType`, `speed`, `contractDurationMonths`, `contractStartDate`, `contractEndDate`, `notes`, `installationFee`, `billingPeriodLabel`) are carried over from the current account.

- **Rule:** If the change request modifies `accountNumber` or `providerId` (and the resulting `(newProvider, newNumber)` differs from the current values), a uniqueness check is performed before applying the update.
  - **Enforcement point:** `ApproveAccountChangeRequestUseCase` — calls `accounts.existsByProviderAndNumber(newProvider, newNumber)`.
  - **Rationale:** Prevents creating a duplicate live account on approval.

- **Rule:** On approval, the request is marked `APPROVED` with `approvedById` and `approvedAt` recorded.
  - **Enforcement point:** `ApproveAccountChangeRequestUseCase` — calls `requests.approve(requestId, approverId, clock.now())`.

### Rejection

- **Rule:** Only `MANAGER` can reject a change request.
  - **Enforcement point:** Controller RBAC.

- **Rule:** Only `PENDING` requests can be rejected.
  - **Enforcement point:** `RejectAccountChangeRequestUseCase`.

- **Rule:** A rejection reason (non-blank) is required.
  - **Enforcement point:** `RejectAccountChangeRequestUseCase` — `if (reason.isBlank()) throw Validation`.

- **Rule:** Rejection is a soft-delete — the record is retained with status `REJECTED` and the reason stored in `rejectedReason`. The account is not modified.
  - **Enforcement point:** `RejectAccountChangeRequestUseCase` — calls `requests.reject(requestId, reason, clock.now())`.

### Cancellation

- **Rule:** Only `SECRETARY` can cancel a change request, and only their own request.
  - **Enforcement point:** Controller RBAC (`SECRETARY`); `CancelAccountChangeRequestUseCase` — `if (request.submittedById != cancellerId) throw Forbidden`.
  - **Rationale:** A secretary cannot cancel another secretary's request.

- **Rule:** Only `PENDING` requests can be cancelled.
  - **Enforcement point:** `CancelAccountChangeRequestUseCase`.

- **Rule:** Cancellation records `cancelledAt` and sets status to `CANCELLED`.
  - **Enforcement point:** `CancelAccountChangeRequestUseCase` — calls `requests.cancel(requestId, clock.now())`.

### Delta Merge Logic on Approval

The merge is a field-by-field overlay. For each editable field:

```
mergedValue = request.<field>New ?: account.<field>
```

Only non-null `*New` fields in the request cause a change. The merged `AccountUpsertRequest` is then passed to `accounts.update()`, which performs the same validations as a direct update (rate > 0, provider/store existence, etc.).

### Proof Attachment Handling (Additive Only)

- **Rule:** If the change request includes a `proofAttachmentId`, it is **appended** to the account's existing `subscriptionProofIds` list (with `distinct()` to avoid duplicates). Existing proofs are never removed.
  - **Enforcement point:** `ApproveAccountChangeRequestUseCase` — `(account.subscriptionProofIds + request.proofAttachmentId).distinct()`.
  - **Rationale:** Proof attachments are additive — historical proofs are preserved for audit.

### Status Transitions

```
PENDING ──(manager approves)──→ APPROVED
PENDING ──(manager rejects)───→ REJECTED
PENDING ──(submitter cancels)──→ CANCELLED
PENDING ──(auto-cancel on new submission)──→ CANCELLED
```

All transitions are terminal — no reversal or re-opening.

### Diff View

- **Rule:** `GET /accounts/{id}/change-requests/{requestId}` returns the request with a computed field-by-field diff between the account's current values and the proposed values.
  - **Enforcement point:** `GetAccountChangeRequestWithDiffUseCase` — builds `List<FieldDiff>` for each non-null `*New` field.
  - **Rationale:** Lets the manager review exactly what will change before approving.

---

## Module: Billing & TopSheets

### Billing Period Format and Semantics

- **Rule:** Billing periods are expressed as `YYYY-MM` strings (e.g. `"2026-08"`).
  - **Enforcement point:** `BillingPeriod` value object in `BillingPeriod.kt` — validates via regex `^\d{4}-\d{2}$`; DB CHECK constraint on `topsheets.billing_period` and `topsheet_details.billing_period` (V1 migration).
  - **Rationale:** Calendar-month granularity for ISP billing.

- **Rule:** The compact form `YYYYMM` is used inside invoice numbers.
  - **Enforcement point:** `BillingPeriod.compact()` and `InvoiceNumberFormatter.format()`.

### Money Handling Rules

- **Rule:** Money is carried as a 2-decimal-place string on the wire (e.g. `"1499.00"`), stored as `NUMERIC(14,2)` in PostgreSQL, and converted via `BigDecimal` at scale 2 with `HALF_UP` rounding. `Double` is never used for money.
  - **Enforcement point:** `Money` object in `Money.kt` — `parse()` and `format()` are the single conversion point; `ProrationEngine` uses fixed-point integer math internally.
  - **Rationale:** Floating-point drift is unacceptable in billing.

- **Rule:** `Money.isPositive()` is used to validate that rates/amounts are greater than zero.
  - **Enforcement point:** `CreateAccountUseCase`, `SubmitAccountChangeRequestUseCase`.

### Proration Rules

- **Rule:** Full monthly rate is billed when the account is active for the entire billing period.
  - **Enforcement point:** `ProrationEngine.proratedAmount()` — returns `account.rate` when `startDay == 1 && endDay == daysInMonth`.

- **Rule:** If the account was installed mid-period, billing starts from the installation day through month end.
  - **Enforcement point:** `ProrationEngine.proratedAmount()` — `startDay = install.dayOfMonth` when `installPeriod == billingPeriod`.

- **Rule:** If the account was installed after the billing period, the prorated amount is `0.00` (not yet active).
  - **Enforcement point:** `ProrationEngine.proratedAmount()` — returns `"0.00"` when `installPeriod > billingPeriod`.

- **Rule:** If a termination was requested, the billing end day is the grace-end date (`terminationRequestedAt + 30 days`) if it falls within the billing period. If the grace-end date is before the billing period, the amount is `0.00`.
  - **Enforcement point:** `ProrationEngine.proratedAmount()` — computes `termDate = termAt.plus(30 days)` and adjusts `endDay` or returns `"0.00"`.

- **Rule:** Prorated amount = `rate / daysInMonth * activeDays`, rounded to 2 decimal places (half-up).
  - **Enforcement point:** `ProrationEngine.proratedAmount()` — `divideRound2(mul(rate.toScaled2(), activeDays), daysInMonth)`.
  - **Rationale:** Fixed-point integer math avoids float drift.

- **Rule:** `activeDays = max(0, endDay - startDay + 1)`.
  - **Enforcement point:** `ProrationEngine.proratedAmount()`.

- **Rule:** An account is "first-bill prorated" if its installation month equals the billing period.
  - **Enforcement point:** `ProrationEngine.isFirstBillProrated()`.

### TopSheet Lifecycle

```
COMPILED ──(finance approves)──→ APPROVED ──(finance pays)──→ PAID
```

All transitions are one-way; no reversal endpoints exist.

- **Rule:** Compiling a TopSheet requires `SECRETARY` role and is idempotent (via `Idempotency-Key` header).
  - **Enforcement point:** Controller RBAC (`TopSheetController.kt`); `CompileTopSheetUseCase` uses `idempotent()` wrapper.

- **Rule:** Previewing a compilation is read-only and requires `SECRETARY` role.
  - **Enforcement point:** `PreviewCompilationUseCase`; controller RBAC.

- **Rule:** Approving a TopSheet requires `FINANCE` role. Only `COMPILED` topsheets can be approved.
  - **Enforcement point:** `ApproveTopSheetUseCase` in `TopSheetUseCases.kt`; controller RBAC.

- **Rule:** Paying a TopSheet requires `FINANCE` role, is idempotent, and only `APPROVED` topsheets can be paid. Payment cascades line items to `PAID` status.
  - **Enforcement point:** `PayTopSheetUseCase` in `TopSheetUseCases.kt`; controller RBAC.

### Invoice Number Format

- **Rule:** Invoice numbers are formatted as `<ACRONYM>-YYYYMM-XXXX` (e.g. `CONV-202608-0007`).
  - **Enforcement point:** `InvoiceNumberFormatter.format()` in `InvoiceNumberFormatter.kt`.

- **Rule:** The provider acronym is derived from the provider name: multi-word names take initials (first 4); single-word names take the first 4 characters; empty names default to `INV`.
  - **Enforcement point:** `InvoiceNumberFormatter.acronym()`.
  - **Examples:** `"Converge" → "CONV"`, `"Philippine Long Distance Telephone" → "PLDT"`.

- **Rule:** The sequence number is a per-provider atomic counter, incremented via a row-locked `UPDATE ... SET current_value = current_value + 1 RETURNING`.
  - **Enforcement point:** `InvoiceSequenceRepository.nextValue()` (called by `CompileTopSheetUseCase`); DB table `invoice_sequences` (V1 migration).

- **Rule:** The invoice prefix is stored in `invoice_sequences.prefix` at provider creation and can be overridden.
  - **Enforcement point:** `CompileTopSheetUseCase` — `sequences.prefixOf(providerId) ?: InvoiceNumberFormatter.prefix(provider.name)`.

### Eligibility Rules for Compilation

- **Rule:** `TRANSFERRED` accounts are excluded from compilation.
  - **Enforcement point:** `ProrationEngine.isEligible()` — `if (account.status == AccountStatus.TRANSFERRED) return false`.

- **Rule:** The account's `providerId` must match the requested `providerId`.
  - **Enforcement point:** `ProrationEngine.isEligible()`.

- **Rule:** The account's installation period must be ≤ the billing period (not installed yet → excluded).
  - **Enforcement point:** `ProrationEngine.isEligible()` — `if (installPeriod > billingPeriod) return false`.

- **Rule:** If `terminationRequestedAt` is set and the grace-end date's period is before the billing period, the account is excluded (fully terminated).
  - **Enforcement point:** `ProrationEngine.isEligible()` — `if (periodOf(termDate) < billingPeriod) return false`.

- **Rule:** If no termination was requested and the account status is `INACTIVE` or `TERMINATED`, the account is excluded.
  - **Enforcement point:** `ProrationEngine.isEligible()` — `else if (account.status == INACTIVE || account.status == TERMINATED) return false`.
  - **Note:** `TERMINATION_REQUESTED` accounts **are** eligible (they are still in the grace window and billable).

- **Rule:** An account already billed in the same billing period is excluded.
  - **Enforcement point:** `ProrationEngine.isEligible()` — `return account.id !in alreadyBilledAccountIds`; `TopSheetRepository.billedAccountIds()`.

- **Rule:** Compiling with zero eligible accounts is rejected with `409 Conflict` (`nothing_to_compile`).
  - **Enforcement point:** `CompileTopSheetUseCase` — `if (lines.isEmpty()) throw Conflict`.

### Double-Billing Guard

- **Rule:** The same account cannot be billed twice in the same billing period.
  - **Enforcement point:** DB unique index `uq_account_per_period ON topsheet_details (account_id, billing_period)` (V1 migration); application-level `alreadyBilledAccountIds` check in `CompileTopSheetUseCase`.

### Immutability of Compiled TopSheets

- **Rule:** No update or delete endpoint exists for compiled topsheets or their line items. Status transitions are one-way.
  - **Enforcement point:** No route exists to mutate topsheet lines or reverse a status; `TopSheetController.kt` exposes only compile/approve/pay (forward transitions).
  - **Note (NOT EXPLICITLY ENFORCED at DB level):** There is no DB trigger preventing direct SQL updates to topsheet rows. Immutability is enforced by application design, not by a database constraint.

- **Rule:** TopSheet line items include denormalized snapshots of account data at compile time (`branchCode`, `storeName`, `circuitId`, `accountNumber`, `accountStatus`).
  - **Enforcement point:** `CompileTopSheetUseCase` — populates snapshot fields in `NewTopSheetLine`.
  - **Rationale:** A compiled topsheet reflects the state of the world at compilation, not later changes to the account.

---

## Module: Transfers

### Transfer Workflow

- **Rule:** Transferring an account requires `SECRETARY` role.
  - **Enforcement point:** Controller RBAC (`AccountController.kt`, `TransferController.kt`).

- **Rule:** A transfer proof attachment is required and must exist.
  - **Enforcement point:** `TransferAccountUseCase` in `AccountLifecycleUseCases.kt` — `if (!attachments.exists(proofId)) throw Validation`.

- **Rule:** The old account is marked `TRANSFERRED` (which frees the partial unique index on `(provider_id, account_number)`).
  - **Enforcement point:** `TransferAccountUseCase` — `accounts.updateStatus(old.id, AccountStatus.TRANSFERRED)`.

- **Rule:** A new `ACTIVE` account is created at the new store carrying the same details (account number, circuit ID, provider, plan, rate, installation date, proofs, etc.) but with a distinct ID.
  - **Enforcement point:** `TransferAccountUseCase` — `accounts.create(AccountUpsertRequest(...), createdBy = actor)`.
  - **Rationale:** The new account can still be billed in the current period.

- **Rule:** A transfer record is created linking old store, new store, old account, new account, proof, and requester.
  - **Enforcement point:** `TransferAccountUseCase` — `transfers.create(old.storeId, newStoreId, old.id, moved.id, proofId, actor, clock.now())`.

- **Rule:** An already-transferred account cannot be transferred again.
  - **Enforcement point:** `TransferAccountUseCase` — `if (old.status == AccountStatus.TRANSFERRED) throw Conflict`.

- **Rule:** The destination store must exist.
  - **Enforcement point:** `TransferAccountUseCase` — `stores.findById(newStoreId) ?: throw Validation`.

- **Rule:** Authentication is required — `actorId` must not be null.
  - **Enforcement point:** `TransferAccountUseCase` — `val actor = actorId ?: throw Unauthorized`.

### Proof Requirements

- **Rule:** A valid `proofId` (referencing an existing attachment) is mandatory for every transfer.
  - **Enforcement point:** `TransferAccountUseCase`; DB column `transfers.proof_id` is nullable (allows null at DB level) but the use case enforces non-null + existence.

### Idempotency Rules

- **Rule:** Transfers support idempotency via the `Idempotency-Key` header.
  - **Enforcement point:** `TransferAccountUseCase` — `idempotent(idempotency, "account.transfer", idem, 201) { ... }`.
  - **Behavior:** Same key + same request body → replay stored result (no re-run). Same key + different body → `409 Conflict`. In-progress reservation → `409 Conflict`. Failed mutation rolls back the reservation.

---

## Module: Grace Period & Termination

### 30-Day Grace Period Policy

- **Rule:** When an account's deactivation is requested (`TERMINATION_REQUESTED`), a 30-day grace window begins from `terminationRequestedAt`.
  - **Enforcement point:** `GracePeriodPolicy.GRACE_DAYS = 30` in `GracePeriodPolicy.kt`; `ProrationEngine.GRACE_DAYS = 30`.
  - **Rationale:** The account keeps billing until 30 days after the termination request, then expires to `INACTIVE`.

- **Rule:** `graceEnd = terminationRequestedAt + 30 days` (computed in UTC).
  - **Enforcement point:** `GracePeriodPolicy.graceEnd()`.

- **Rule:** The grace period has expired when `now >= graceEnd`.
  - **Enforcement point:** `GracePeriodPolicy.hasExpired()`.

### Automatic Expiry Job

- **Rule:** The `ExpireGracePeriodAccountsUseCase` finds all `TERMINATION_REQUESTED` accounts whose grace has expired and flips them to `INACTIVE`.
  - **Enforcement point:** `ExpireGracePeriodAccountsUseCase` in `ExpireGracePeriodAccountsUseCase.kt`.
  - **Rationale:** Replaces the lazy client-side update that used to happen in the React `loadData`.

- **Rule:** The job runs on a daily schedule and can also be triggered manually by `SYSADMIN` via `POST /admin/jobs/expire-grace`.
  - **Enforcement point:** `JobsController.kt` — `call.authorize(UserRole.SYSADMIN)`; scheduled by the infrastructure bootstrap.

- **Rule:** The job returns the count of expired accounts.
  - **Enforcement point:** `ExpireGracePeriodAccountsUseCase` — returns `expired.size`.

- **Rule:** The entire expiry operation runs in a single transaction.
  - **Enforcement point:** `ExpireGracePeriodAccountsUseCase` — `tx.inTransaction { ... }`.

### Status Transitions During Grace

- **Rule:** During the grace window, the account remains `TERMINATION_REQUESTED` and is still billable (proration bills up to the grace-end day).
  - **Enforcement point:** `ProrationEngine.isEligible()` — `TERMINATION_REQUESTED` accounts are **not** excluded (only `INACTIVE` and `TERMINATED` without a termination request are).

- **Rule:** After the grace window expires, the account becomes `INACTIVE` and is no longer eligible for billing.
  - **Enforcement point:** `ExpireGracePeriodAccountsUseCase` + `ProrationEngine.isEligible()`.

---

## Module: Attachments

### Presign Upload Flow (2-Step)

- **Rule:** Step 1 — `POST /attachments/presign/upload` (any authenticated role) creates an attachment row (no bytes yet) and returns a presigned upload URL + the attachment ID.
  - **Enforcement point:** `PresignUploadUseCase` in `AttachmentUseCases.kt`; controller RBAC (`AttachmentController.kt`).

- **Rule:** Step 2 — `PUT /attachments/{id}/blob?token=...` (public, token-gated) accepts the file bytes and stores them.
  - **Enforcement point:** `StoreBlobUseCase` in `AttachmentUseCases.kt`; blob routes registered outside the auth block (`attachmentBlobRoutes`).

- **Rule:** The upload token is validated (binds attachment ID + upload operation + expiry) before bytes are accepted.
  - **Enforcement point:** `StoreBlobUseCase` — `presign.isValid(id, PresignOp.UPLOAD, token)`.

- **Rule:** Empty file uploads are rejected.
  - **Enforcement point:** `StoreBlobUseCase` — `if (bytes.isEmpty()) throw Validation`.

### Presign Download Flow

- **Rule:** `GET /attachments/{id}/presign/download` (any authenticated role) returns a presigned download URL.
  - **Enforcement point:** `PresignDownloadUseCase`; controller RBAC.

- **Rule:** `GET /attachments/{id}/blob?token=...` (public, token-gated) streams the file bytes.
  - **Enforcement point:** `ReadBlobUseCase`; blob routes.

- **Rule:** The download token is validated before bytes are served.
  - **Enforcement point:** `ReadBlobUseCase` — `presign.isValid(id, PresignOp.DOWNLOAD, token)`.

### Attachment Purposes

| Purpose | Wire value | Usage |
|---------|-----------|-------|
| `INSTALLATION_PROOF` | `installation_proof` | Store creation — mandatory FK |
| `CLOSURE_PROOF` | `closure_proof` | Store closure — mandatory at use-case level |
| `SUBSCRIPTION_PROOF` | `subscription_proof` | Account subscription documents (0..n per account) |
| `DEACTIVATION_PROOF` | `deactivation_proof` | Account deactivation (termination request) |
| `TRANSFER_PROOF` | `transfer_proof` | Account transfer |
| `OCR_SOURCE` | `ocr_source` | OCR batch source file |

- **Enforcement point:** `AttachmentPurpose` enum in `DomainModels.kt`; DB enum `attachment_purpose` (V1 migration).

### Entity Linking Pattern

- **Rule:** Attachments are softly linked to entities via `entity_type` (`'store' | 'account' | 'transfer' | 'ocr'`) and `entity_id` (nullable UUID).
  - **Enforcement point:** `attachments` table columns (V1 migration); `PresignUploadUseCase` creates rows with `entityType=null, entityId=null` (linked later by the consuming use case).
  - **Rationale:** Flexible soft link for audit; hard FKs are used where the relationship is structural (stores → proof_of_installation_id).

- **Rule:** Account subscription proofs use a many-to-many join table `account_attachments (account_id, attachment_id)`.
  - **Enforcement point:** DB table `account_attachments` (V1 migration).

### Storage Key Format

- **Rule:** Storage keys are formatted as `purpose/<uuid>-<sanitized-filename>`.
  - **Enforcement point:** `PresignUploadUseCase` — `key = "${purpose.name.lowercase()}/${UUID.randomUUID()}-$safeName"`.
  - **Sanitization:** Filename characters not in `[A-Za-z0-9._-]` are replaced with `_`; blank names default to `"file"`.

---

## Module: Bulk Import

> **Note:** `BulkImportAccountsUseCase.kt` is not present in the `account-change-requests`
> worktree. The rules below are documented from the main repository
> (`/Users/puregoldmobileteam/IdeaProjects/ibms-backend/src/application/usecase/BulkImportAccountsUseCase.kt`)
> for completeness and alignment.

### Import Format and Validation Rules

- **Rule:** The import file must be a valid XLSX spreadsheet with a header row. Column order does not matter — headers are matched by name (case-insensitive).
  - **Enforcement point:** `BulkImportAccountsUseCase` — `XSSFWorkbook` + header map.

- **Rule:** Required columns: `Store Code`, `Store Name`, `ISP/Provider`, `Account No`, `Monthly Recurring Amount`.
  - **Enforcement point:** `BulkImportAccountsUseCase.colOf()` — throws `Validation` if a required column is missing.

- **Rule:** Optional columns: `Service Type`, `Circuit ID`, `Start Date`.
  - **Enforcement point:** `BulkImportAccountsUseCase` — uses `headerMap[...]` (nullable) for optional columns.

### Error Handling Per Row

- **Rule:** Rows missing `Store Code`, `ISP/Provider`, or `Account No` are skipped and reported in the summary.
  - **Enforcement point:** `BulkImportAccountsUseCase` — skip + `skipReasons.add(...)`.

- **Rule:** Rows with an invalid or zero `Monthly Recurring Amount` are skipped and reported.
  - **Enforcement point:** `BulkImportAccountsUseCase` — `if (rate == null || !Money.isPositive(rate)) skip`.

- **Rule:** The import returns a `BulkImportSummary` with counts of providers/stores/accounts created and reused, rows skipped, and skip reasons.
  - **Enforcement point:** `BulkImportAccountsUseCase` — returns `BulkImportSummary`.

### Idempotency

- **Rule:** The import is idempotent — re-running with the same file creates no duplicates.
  - **Enforcement point:**
    - Providers are matched by name (`providers.findByName()`) — found or created.
    - Stores are matched by `branchCode` (`stores.findByBranchCode()`) — found or created.
    - Accounts are matched by `(providerId, accountNumber)` (`accounts.existsByProviderAndNumber()`) — found → reused.

### Provider Creation During Import

- **Rule:** Providers created during import default to `paymentScheduleDay = 5` and have their invoice sequence seeded.
  - **Enforcement point:** `BulkImportAccountsUseCase` — `DEFAULT_PAYMENT_SCHEDULE_DAY = 5`; `sequences.seed()`.

### Store Creation During Import

- **Rule:** Stores created during import default to `storeType = PUREGOLD` and use a shared placeholder attachment for `proofOfInstallationId`.
  - **Enforcement point:** `BulkImportAccountsUseCase` — `StoreType.PUREGOLD`; single `attachments.create(INSTALLATION_PROOF, ...)` with key `"bulk-import/placeholder-installation-proof"`.
  - **Rationale:** Bulk-imported stores do not carry individual proof documents.

### Account Creation During Import

- **Rule:** If `Start Date` is missing or unparseable, `installationDate` defaults to `1970-01-01` and a note is added: "Installation date unavailable from source (bulk import)".
  - **Enforcement point:** `BulkImportAccountsUseCase` — `parseDate(startDate) ?: LocalDate(1970, 1, 1)`.
  - **Impact:** Default date affects proration; accounts with the default date will be treated as long-installed (full-month billing).

- **Rule:** Date parsing accepts multiple formats: `M/d/yyyy`, `MM/dd/yyyy`, `MMM d, yyyy`, `yyyy-MM-dd`, and Excel serial dates.
  - **Enforcement point:** `BulkImportAccountsUseCase.parseDate()`.

- **Rule:** Amount parsing strips thousands separators and currency symbols (e.g. `"2,798.00" → "2798.00"`, `"₱2798" → "2798"`).
  - **Enforcement point:** `BulkImportAccountsUseCase.parseAmount()`.

- **Rule:** Each imported account is logged as `account.bulk_imported` in the activity log.
  - **Enforcement point:** `BulkImportAccountsUseCase` — `activity.record(actorId, "account.bulk_imported", "account", created.id)`.

---

## Module: Activity & Audit

### What Actions Are Logged

The following actions are recorded in the activity log:

| Action | Trigger | Use Case |
|--------|---------|----------|
| `account.created` | Account created | `CreateAccountUseCase` |
| `account.transferred` | Account transferred | `TransferAccountUseCase` |
| `account.deactivation_requested` | Deactivation requested | `DeactivateAccountUseCase` |
| `account.bulk_imported` | Account created via bulk import | `BulkImportAccountsUseCase` |
| `store.created` | Store created | `CreateStoreUseCase` |
| `topsheet.compiled` | TopSheet compiled | `CompileTopSheetUseCase` |
| `topsheet.approved` | TopSheet approved | `ApproveTopSheetUseCase` |
| `account_change_request.submitted` | Change request submitted | `SubmitAccountChangeRequestUseCase` |
| `account_change_request.auto_cancelled` | Existing request auto-cancelled on new submission | `SubmitAccountChangeRequestUseCase` |
| `account_change_request.approved` | Change request approved | `ApproveAccountChangeRequestUseCase` |
| `account_change_request.rejected` | Change request rejected | `RejectAccountChangeRequestUseCase` |
| `account_change_request.cancelled` | Change request cancelled | `CancelAccountChangeRequestUseCase` |

### Actions NOT Logged (Known Gaps)

The following mutations do **not** record an activity log entry:

| Mutation | Use Case | Status |
|----------|----------|--------|
| Account update (`PUT /accounts/{id}`) | `UpdateAccountUseCase` | **NOT ENFORCED** — no `activity.record()` call |
| Store update (`PUT /stores/{id}`) | `UpdateStoreUseCase` | **NOT ENFORCED** |
| Store close (`POST /stores/{id}/deactivate`) | `CloseStoreUseCase` | **NOT ENFORCED** |
| Provider create/update/deactivate | `CreateProviderUseCase`, etc. | **NOT ENFORCED** |
| User provision/reset-password/role-update/status-update | `UserUseCases.kt` | **NOT ENFORCED** |
| TopSheet pay (`POST /topsheets/{id}/pay`) | `PayTopSheetUseCase` | **NOT ENFORCED** |
| Attachment presign/upload/download | `AttachmentUseCases.kt` | **NOT ENFORCED** |
| OCR extract/batch/template operations | `OcrUseCases.kt` | **NOT ENFORCED** |
| Session login/refresh/logout | `AuthUseCases.kt` | **NOT ENFORCED** (sessions table serves as audit) |

### Activity Record Structure

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Primary key |
| `userId` | UUID (nullable) | The actor who performed the action |
| `userEmail` | TEXT (nullable) | Denormalized snapshot |
| `userName` | TEXT (nullable) | Denormalized snapshot |
| `action` | TEXT | Action identifier (e.g. `"account.created"`) |
| `details` | TEXT (nullable) | Free-form details |
| `entityType` | TEXT (nullable) | e.g. `"account"`, `"store"`, `"topsheet"` |
| `entityId` | UUID (nullable) | The affected entity |
| `createdAt` | TIMESTAMPTZ | When the action occurred |

- **Enforcement point:** `activities` table (V1 migration); `ActivityRecorder` port.

### Transactional Guarantees

- **Rule:** Activity records are written inside the same database transaction as the mutation they describe. If the mutation fails (throws), the activity record is rolled back.
  - **Enforcement point:** All use cases call `activity.record(...)` inside `tx.inTransaction { ... }`.
  - **Rationale:** Only successful mutations have audit entries; there are no orphan activity records for failed operations.

### Access Control

- **Rule:** The activity log is read-only and accessible to any authenticated role.
  - **Enforcement point:** Controller RBAC (`ActivityController.kt` — `call.authorize()` with no role restriction).
  - **Filtering:** Optional `entityId` query parameter; cursor-paginated.

---

## Appendix: Error Categories

All business-rule failures are raised as `DomainError` subtypes, mapped to HTTP status codes by the `StatusPages` plugin:

| Error Type | HTTP Status | Default Code | Use Case |
|-----------|-------------|--------------|----------|
| `DomainError.Validation` | 400 | `validation_error` | Request payload violated a rule (e.g. rate ≤ 0, bad billingPeriod) |
| `DomainError.Unauthorized` | 401 | `unauthorized` | Auth token missing/invalid |
| `DomainError.Forbidden` | 403 | `forbidden` | Caller lacks the required role/permission |
| `DomainError.NotFound` | 404 | `not_found` | Entity not found |
| `DomainError.Conflict` | 409 | `conflict` | Rule conflict (e.g. duplicate branch_code, double-billing, last sysadmin) |

- **Enforcement point:** `DomainError` sealed class in `DomainError.kt`; `StatusPages` plugin in `StatusPages.kt`.
- **Rationale:** Use cases stay free of framework/HTTP types; the boundary maps domain errors to HTTP responses.

---

## Appendix: Configuration Defaults

| Parameter | Default | Source |
|-----------|---------|--------|
| Refresh token TTL | 30 days | `SessionPolicy.refreshTtl` |
| Temporary password TTL | 72 hours | `SessionPolicy.temporaryPasswordTtl` |
| Max failed logins | 5 | `SessionPolicy.maxFailedLogins` |
| Lockout duration | 15 minutes | `SessionPolicy.lockoutDuration` |
| Password min length | 12 | `PasswordPolicy.MIN_LENGTH` |
| Password max length | 72 | `PasswordPolicy.MAX_LENGTH` |
| Username min length | 3 | `UsernamePolicy.MIN_LENGTH` |
| Username max length | 32 | `UsernamePolicy.MAX_LENGTH` |
| Grace period | 30 days | `GracePeriodPolicy.GRACE_DAYS` |
| Money scale | 2 dp | `Money.SCALE` |
| Money rounding | HALF_UP | `Money.format()` |
| Bulk import default payment day | 5 | `BulkImportAccountsUseCase.DEFAULT_PAYMENT_SCHEDULE_DAY` |

All `SessionPolicy` values are configurable via `AppConfig` (environment-driven); the defaults above are the production posture.
