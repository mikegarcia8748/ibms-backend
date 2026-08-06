# Account Deactivation API Contract

> **Status:** APPROVED
> **Last Updated:** 2026-07-23

Manages the account deactivation lifecycle: a secretary initiates deactivation (triggering a 30-day grace period), during which the account remains billable. After grace expiry a scheduled job moves the account to inactive. Deactivation can be cancelled at any time before expiry, reverting the account to active.

---

## Endpoints

All endpoints require authentication. Base path: `/accounts`.

| Method | Path | Role Required | Description |
|--------|------|---------------|-------------|
| POST | `/accounts/{id}/deactivate` | SECRETARY | Initiate account deactivation (30-day grace period) |
| POST | `/accounts/{id}/cancel-deactivation` | SECRETARY | Cancel pending deactivation, revert to active |
| GET | `/accounts?status=termination_requested` | Any authenticated | List accounts pending deactivation with grace end dates |

> `SYSADMIN` is a global superuser and is admitted to every endpoint in this contract in
> addition to the role named above.

---

## State Transition Diagram

```mermaid
stateDiagram-v2
    [*] --> ACTIVE
    ACTIVE --> TERMINATION_REQUESTED: POST /accounts/{id}/deactivate
    TERMINATION_REQUESTED --> INACTIVE: 30-day grace period expires (scheduled job)
    TERMINATION_REQUESTED --> ACTIVE: POST /accounts/{id}/cancel-deactivation
```

---

## POST `/accounts/{id}/deactivate`

Initiates deactivation of an active account. Sets the status to `termination_requested` and starts a 30-day grace period.

### Request

Headers:

```
Authorization: Bearer <jwt>
Content-Type: application/json
Idempotency-Key: <unique-string>
```

Body:

```json
{
  "proofIds": [
    "uuid-of-deactivation-proof-1",
    "uuid-of-deactivation-proof-2"
  ]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `proofIds` | `string[] (UUID)` | **Yes** | 1 to 3 previously uploaded deactivation proof attachments. Each must exist, be fully uploaded, be a PDF, and have been presigned with `purpose: "deactivation_proof"`. Order is preserved. |
| `proofId` | `string (UUID)` | No | **Deprecated.** The single-proof form, still accepted. Ignored when `proofIds` is present and non-empty. |

See [ACCOUNT_ACTIVITY_PROOFS_API_CONTRACT.md](ACCOUNT_ACTIVITY_PROOFS_API_CONTRACT.md) for the upload flow and the full proof validation error table.

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "ok",
  "status": "200",
  "data": {
    "id": "uuid-of-account",
    "accountNumber": "71214756",
    "circuitId": "IC-AWZ-2200",
    "providerId": "uuid-of-provider",
    "storeId": "uuid-of-store",
    "planName": "Enterprise 100Mbps",
    "serviceType": "fiber",
    "speed": "100Mbps",
    "contractDurationMonths": 24,
    "contractStartDate": "2025-01-15",
    "contractEndDate": "2027-01-14",
    "notes": null,
    "installationFee": "5000.00",
    "rate": "5598.00",
    "installationDate": "2025-01-15",
    "billingPeriodLabel": null,
    "isProrated": false,
    "status": "termination_requested",
    "terminationRequestedAt": "2026-07-23T08:00:00Z",
    "graceEndDate": "2026-08-22T08:00:00Z",
    "subscriptionProofIds": ["uuid-of-subscription-proof"],
    "createdAt": "2025-01-15T10:30:00Z",
    "updatedAt": "2026-07-23T08:00:00Z"
  }
}
```

### Error Responses

| Status | Condition | Body |
|--------|-----------|------|
| `404` | Account not found | `{"result":"error","status":"404","message":"account {id} not found","data":null}` |
| `409` | Account is not in `active` status | `{"result":"error","status":"409","message":"only active accounts can be deactivated","data":null}` |
| `409` | Another request moved the account between the check and the write | `{"result":"error","status":"409","message":"account {id} is no longer active; deactivation was not applied","data":null}` |
| `409` | Idempotency-Key reused with a different request (including a different account) | `{"result":"error","status":"409","message":"Idempotency-Key reused with a different request","data":null}` |
| `400` | A proof is unknown, not yet uploaded, not a PDF, or not a `deactivation_proof` | `{"result":"error","status":"400","message":"a valid deactivation proof is required","data":null}` |
| `400` | No proof supplied at all | `{"result":"error","status":"400","message":"at least one deactivation proof is required","data":null}` |
| `400` | More than 3 proofs supplied | `{"result":"error","status":"400","message":"at most 3 files may be attached to deactivation proof","data":null}` |
| `403` | Caller is not SECRETARY | Standard forbidden response |
| `401` | No bearer token | Standard unauthorized response |

---

## POST `/accounts/{id}/cancel-deactivation`

Cancels a pending deactivation, reverting the account to active status. Only accounts currently in `termination_requested` status can be cancelled.

### Request

Headers:

```
Authorization: Bearer <jwt>
Content-Type: application/json
```

Body:

```json
{
  "reason": "Customer renewed contract — deactivation no longer needed"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reason` | `string` | **Yes** | Textual explanation for why the deactivation is being cancelled. |

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "ok",
  "status": "200",
  "data": {
    "id": "uuid-of-account",
    "accountNumber": "71214756",
    "circuitId": "IC-AWZ-2200",
    "providerId": "uuid-of-provider",
    "storeId": "uuid-of-store",
    "planName": "Enterprise 100Mbps",
    "serviceType": "fiber",
    "speed": "100Mbps",
    "contractDurationMonths": 24,
    "contractStartDate": "2025-01-15",
    "contractEndDate": "2027-01-14",
    "notes": null,
    "installationFee": "5000.00",
    "rate": "5598.00",
    "installationDate": "2025-01-15",
    "billingPeriodLabel": null,
    "isProrated": false,
    "status": "active",
    "terminationRequestedAt": null,
    "graceEndDate": null,
    "subscriptionProofIds": ["uuid-of-subscription-proof"],
    "createdAt": "2025-01-15T10:30:00Z",
    "updatedAt": "2026-07-23T09:00:00Z"
  }
}
```

### Error Responses

| Status | Condition | Body |
|--------|-----------|------|
| `404` | Account not found | `{"result":"error","status":"404","message":"account {id} not found","data":null}` |
| `409` | Account is not in `termination_requested` status | `{"result":"error","status":"409","message":"only accounts in termination_requested status can have deactivation cancelled","data":null}` |
| `409` | The grace-expiry job archived the account between the check and the write | `{"result":"error","status":"409","message":"account {id} is no longer in termination_requested status; cancellation was not applied","data":null}` |
| `400` | `reason` is blank | `{"result":"error","status":"400","message":"a cancellation reason is required","data":null}` |
| `403` | Caller is not SECRETARY | Standard forbidden response |
| `401` | No bearer token | Standard unauthorized response |

---

## GET `/accounts` (with status filter)

Lists accounts filtered by status. Use `?status=termination_requested` to retrieve all accounts pending deactivation.

### Request

```
GET /accounts?status=termination_requested&limit=20&cursor=<cursor>
Authorization: Bearer <jwt>
```

Query parameters:

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `status` | `string` | No | Filter by account status: `active`, `termination_requested`, `transferred`, `inactive` |
| `storeId` | `string (UUID)` | No | Filter by store |
| `providerId` | `string (UUID)` | No | Filter by provider |
| `cursor` | `string` | No | Pagination cursor from previous response |
| `limit` | `integer` | No | Page size (default 20, max 100) |

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "ok",
  "status": "200",
  "data": {
    "items": [
      {
        "id": "uuid-1",
        "accountNumber": "71214756",
        "circuitId": "IC-AWZ-2200",
        "providerId": "uuid-of-provider",
        "storeId": "uuid-of-store",
        "planName": "Enterprise 100Mbps",
        "serviceType": "fiber",
        "speed": "100Mbps",
        "contractDurationMonths": 24,
        "contractStartDate": "2025-01-15",
        "contractEndDate": "2027-01-14",
        "notes": null,
        "installationFee": "5000.00",
        "rate": "5598.00",
        "installationDate": "2025-01-15",
        "billingPeriodLabel": null,
        "isProrated": false,
        "status": "termination_requested",
        "terminationRequestedAt": "2026-07-20T10:00:00Z",
        "graceEndDate": "2026-08-19T10:00:00Z",
        "subscriptionProofIds": ["uuid-proof-1"],
        "createdAt": "2025-01-15T10:30:00Z",
        "updatedAt": "2026-07-20T10:00:00Z"
      }
    ],
    "nextCursor": "next-page-cursor-or-null"
  }
}
```

---

## Account Response Schema

The full Account JSON schema returned by all deactivation endpoints:

```json
{
  "id": "string (UUID)",
  "accountNumber": "string",
  "circuitId": "string | null",
  "providerId": "string (UUID)",
  "storeId": "string (UUID)",
  "planName": "string | null",
  "serviceType": "string | null",
  "speed": "string | null",
  "contractDurationMonths": "integer | null",
  "contractStartDate": "ISO date (YYYY-MM-DD) | null",
  "contractEndDate": "ISO date (YYYY-MM-DD) | null",
  "notes": "string | null",
  "installationFee": "decimal string | null",
  "rate": "decimal string (MRC)",
  "installationDate": "ISO date (YYYY-MM-DD)",
  "billingPeriodLabel": "string | null",
  "isProrated": "boolean",
  "status": "string (active | termination_requested | transferred | inactive)",
  "terminationRequestedAt": "ISO 8601 timestamp | null",
  "graceEndDate": "ISO 8601 timestamp | null",
  "subscriptionProofIds": ["array of UUID strings"],
  "createdAt": "ISO 8601 timestamp",
  "updatedAt": "ISO 8601 timestamp | null"
}
```

### Key Deactivation Fields

| Field | Type | Description |
|-------|------|-------------|
| `status` | `string` | `"termination_requested"` when deactivation is pending |
| `terminationRequestedAt` | `string (ISO 8601)` | Timestamp when deactivation was initiated; `null` if not pending deactivation |
| `graceEndDate` | `string (ISO 8601)` | Timestamp when the 30-day grace period expires; computed as `terminationRequestedAt + 30 days` (UTC); `null` if not pending deactivation |

---

## Grace Period Explanation

| Aspect | Detail |
|--------|--------|
| **Duration** | 30 calendar days from the `terminationRequestedAt` timestamp |
| **Computation** | `graceEndDate = terminationRequestedAt + 30 days` (UTC timezone) |
| **Billing** | Account remains billable (prorated) through grace-end date; topsheet compilation includes the account until expiry |
| **Expiry** | A daily scheduled job checks all `termination_requested` accounts and moves expired ones to `inactive` status |
| **Manual trigger** | `POST /admin/jobs/expire-grace` (SYSADMIN only) — runs the expiry check on demand |
| **Cancellation** | Account can be reverted to `active` at any time before grace expiry via `POST /accounts/{id}/cancel-deactivation` |

---

## Side Effects

1. **Activity log — deactivation requested**: Action `"account.deactivation_requested"` is recorded when a secretary initiates deactivation.
2. **Activity log — deactivation cancelled**: Action `"account.deactivation_cancelled"` is recorded when a secretary cancels a pending deactivation.
3. **Proof attachments linked**: Each proof in `proofIds` is linked to the account tagged `deactivation_proof`, and is returned by `GET /accounts/{id}/attachments?purpose=deactivation_proof`. All proofs of one request share an identical `linkedAt`, so a re-request after a cancellation forms its own distinct set. Deactivation proofs are **not** part of `subscriptionProofIds` — that field carries only the account's subscription proofs. (Builds before V23 did append them to that list; the two are now cleanly separated.)
4. **Account Change Requests blocked**: Submitting a change request for an account in `termination_requested` status returns `409 Conflict` with message `"can only submit changes for active accounts"`.
5. **Email notification**: an `account.deactivation_requested` notification is queued to every subscriber of that event. It carries the account number, the circuit, the store, and the grace-end date — the account number alone cannot identify the subject, since one number legitimately recurs across stores and circuits. Grace expiry queues `account.terminated` the same way. See [NOTIFICATION_SUBSCRIPTION_ADMIN_API_CONTRACT.md](NOTIFICATION_SUBSCRIPTION_ADMIN_API_CONTRACT.md).
6. **The identity slot stays occupied**: `termination_requested` still counts as live for the account-identity uniqueness rule `(store, provider, account number, circuit)`. Re-provisioning the same identity at that store is rejected with `409` until the grace period expires to `inactive`; the message names the blocking account's pending-termination status. A *transfer*, by contrast, frees the source slot immediately.

---

## Idempotency

The `POST /accounts/{id}/deactivate` endpoint supports idempotency via a request header:

```
Idempotency-Key: <unique-string>
```

| Scenario | Behavior |
|----------|----------|
| Same key + same request | Replays the stored response (no side effects re-executed) |
| Same key + different request | Returns `409 Conflict` |
| No key provided | Normal (non-idempotent) execution |

- **Scope**: Per-operation (`account.deactivate`). Keys are **global within that scope** —
  they are not partitioned by user, so treat a key as globally unique (a UUID) rather than
  assuming your session owns its own namespace.
- **What "same request" means**: the hash covers the **account id** as well as the proof
  set. Reusing one key against a different account is therefore a *different* request and
  returns `409` — it does not, as it once did, replay the first account's response while
  leaving the second account untouched.
- **Semantics**: Identical to the transfer endpoint's idempotency behavior. Both transfer
  entry points (`POST /accounts/{id}/transfer` and `POST /transfers`) hash to the same
  value for the same logical transfer, so a retry may switch between them.

### Choosing a key

One key per **logical attempt**, generated once and reused for every retry of that attempt:

```
Idempotency-Key: 6f1b1f2e-3c2a-4a90-9a1e-7b2d0c4e5a11   // a UUID, generated once
```

Two anti-patterns to avoid:

- **A per-call key** (e.g. one containing `Date.now()`) is a new key every time, so retries
  are never deduplicated — the header is present but does nothing.
- **A key that is stable for longer than one attempt** (e.g. `deactivate-{accountId}-{date}`)
  is reused by the *next* attempt on the same account that day. Because completed keys are
  retained, a deactivate → cancel-deactivation → deactivate sequence within that window
  replays the first response: the caller is told `termination_requested` while the account
  is still `active`. Generate a fresh key whenever the user starts a new deactivation.

---

## Code Examples

### cURL — Deactivate an account

```bash
curl -X POST "http://localhost:8080/accounts/{accountId}/deactivate" \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"proofIds": ["<proof-attachment-uuid>"]}'
```

### cURL — Cancel a deactivation

```bash
curl -X POST "http://localhost:8080/accounts/{accountId}/cancel-deactivation" \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Customer renewed contract"}'
```

### cURL — List accounts pending deactivation

```bash
curl "http://localhost:8080/accounts?status=termination_requested&limit=20" \
  -H "Authorization: Bearer <jwt>"
```

### JavaScript (Fetch) — Deactivate an account

```javascript
// Generated ONCE when the user confirms, then reused by every retry of this attempt.
// Do not derive it from the clock (a new key each call defeats idempotency) or from the
// account id plus the date (a later attempt on the same account would replay this one).
const idempotencyKey = crypto.randomUUID();

const response = await fetch(`/accounts/${accountId}/deactivate`, {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
    'Idempotency-Key': idempotencyKey,
  },
  body: JSON.stringify({ proofIds: proofAttachmentIds }),
});
const { data: account } = await response.json();
// account.status === 'termination_requested'
// account.graceEndDate is set (30 days from now)
```

### JavaScript (Fetch) — Cancel a deactivation

```javascript
const response = await fetch(`/accounts/${accountId}/cancel-deactivation`, {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({ reason: 'Customer renewed contract' }),
});
const { data: account } = await response.json();
// account.status === 'active'
// account.terminationRequestedAt === null
// account.graceEndDate === null
```

### JavaScript (Fetch) — List accounts pending deactivation

```javascript
const response = await fetch(
  `/accounts?status=termination_requested&limit=20`,
  { headers: { 'Authorization': `Bearer ${token}` } },
);
const { data } = await response.json();
// data.items = array of Account objects with graceEndDate populated
// data.nextCursor = pagination cursor or null
```

---

## Frontend Guidance

1. **"Pending Deactivation" badge**: Display a prominent badge/indicator on accounts with `status === "termination_requested"`. Include a countdown showing remaining days until grace expiry.
2. **Countdown computation**: `remainingDays = Math.ceil((new Date(graceEndDate) - Date.now()) / 86400000)`. Show "X days remaining" or "Expires today" when ≤ 0.
3. **Disable edit actions**: For accounts in `termination_requested` status, disable account edit forms and change request submission (the backend will reject with 409 regardless).
4. **"Cancel Deactivation" button**: Show on accounts with `status === "termination_requested"`. On click, prompt the user for a cancellation reason (required text input), then call `POST /accounts/{id}/cancel-deactivation`.
5. **After cancellation**: Revert the UI to normal active state — remove the badge, re-enable edit actions, clear the grace period countdown.
6. **Proof upload flow**: Before calling deactivate, upload **1 to 3** deactivation proofs via the presign flow (`POST /attachments/presign/upload` with `purpose: "deactivation_proof"` → `PUT /attachments/{id}/blob`), then pass the resulting attachment UUIDs as `proofIds`. Read them back with `GET /accounts/{id}/attachments?purpose=deactivation_proof`. Full details in [ACCOUNT_ACTIVITY_PROOFS_API_CONTRACT.md](ACCOUNT_ACTIVITY_PROOFS_API_CONTRACT.md).
7. **Status filter**: Use the accounts list endpoint with `?status=termination_requested` to populate a "Pending Deactivation" dashboard view.
