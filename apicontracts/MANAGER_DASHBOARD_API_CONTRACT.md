# Manager's Dashboard API Contract

> **Status:** DRAFT
> **Last Updated:** 2026-07-27

Consolidated read/aggregation surface for the Manager's Dashboard: headline account
and Monthly Recurring Charge (MRC) totals, per-ISP breakdowns, a denormalized account
listing with store names, billing history (compiled top sheets), and archive views —
plus the already-shipped filtered Excel export.

All dashboard endpoints are **read-only** and gated to **MANAGER** and **FINANCE**
(SYSADMIN is auto-admitted as a global superuser). They live under the base path
`/dashboard`. The one exception is `/dashboard/archived-accounts`, which also
admits **SECRETARY** — see
[ARCHIVED_ACCOUNTS_API_CONTRACT.md](ARCHIVED_ACCOUNTS_API_CONTRACT.md).

---

## Endpoints

| Method | Path | Role | Feature | Description |
|--------|------|------|---------|-------------|
| GET | `/dashboard/summary` | MANAGER, FINANCE | 1 & 2 | Total active accounts + MRC, per-status counts, per-ISP breakdown |
| GET | `/dashboard/accounts` | MANAGER, FINANCE | 3 | Accounts with associated store (denormalized, paginated) |
| GET | `/dashboard/billing-history` | MANAGER, FINANCE | 5 | Billing history / compiled top sheets (non-draft) |
| GET | `/dashboard/archived-accounts` | MANAGER, FINANCE, **SECRETARY** | 6a | Archived (inactive) accounts, with store names |
| GET | `/dashboard/archived-stores` | MANAGER, FINANCE | 6b | Archived (closed) stores |
| GET | `/dashboard/exports/accounts.xlsx` | MANAGER, FINANCE | 4 | Excel export filtered by ISP + status (alias of `/exports/accounts.xlsx`) |

> Features 4/5/6 also remain available on their original paths (see
> [Reused endpoints](#reused-endpoints)); the `/dashboard/*` routes above are a
> single manager-facing namespace layered over the same use cases.

---

## Conventions

**Response envelope.** Every JSON endpoint returns:

```json
{ "result": "success", "message": "success", "status": "200", "data": { } }
```

Binary endpoints (`*.xlsx`) stream an attachment and bypass this envelope.

**Pagination.** List endpoints are cursor/keyset paginated (there is **no total row
count** — use `/dashboard/summary` for aggregate totals):

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| `cursor` | `string` | — | Opaque cursor; pass back the previous `nextCursor`. |
| `limit` | `int` | `50` | Clamped to `1..100`. |

`data` is `{ "items": [ ... ], "nextCursor": "<id or null>" }`. `nextCursor` is
`null` on the last page.

**Status filters.** `status` values are the lowercase enum wire values. An
**unrecognized value parses to `null`** and is treated as "no filter" (it does not
error), so validate values client-side. Account statuses: `active`,
`termination_requested`, `transferred`, `inactive`. Top-sheet statuses: `draft`,
`compiled`, `approved`, `paid`.

**Common errors.**

| Status | When |
|--------|------|
| `401 Unauthorized` | Missing/invalid bearer token. |
| `403 Forbidden` | Authenticated but not MANAGER/FINANCE/SYSADMIN — except on `/dashboard/archived-accounts`, which also allows SECRETARY. |
| `400 Bad Request` | Malformed/unknown `cursor` (`"invalid cursor"`). |

---

## GET `/dashboard/summary`

Features 1 & 2. Headline totals cover **ACTIVE (billable) accounts only**;
`statusBreakdown` reports counts for **every** account status; `byProvider` breaks
active accounts and MRC down per ISP (provider). MRC values are decimal strings
(2 dp).

### Request

No query parameters.

```
GET /dashboard/summary
Authorization: Bearer <jwt>
```

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "totalActiveAccounts": 128,
    "totalActiveMrc": "254300.00",
    "statusBreakdown": [
      { "status": "active", "count": 128 },
      { "status": "termination_requested", "count": 3 },
      { "status": "transferred", "count": 12 },
      { "status": "inactive", "count": 20 }
    ],
    "byProvider": [
      { "providerId": "1f...", "providerName": "Converge", "activeAccountCount": 80, "activeMrc": "160000.00" },
      { "providerId": "2a...", "providerName": "PLDT", "activeAccountCount": 48, "activeMrc": "94300.00" }
    ]
  }
}
```

Notes:
- `byProvider` includes only providers that currently have at least one active account.
- `statusBreakdown` always contains one entry per status (count `0` when none).

---

## GET `/dashboard/accounts`

Feature 3. Denormalized, paginated accounts joined with their store and provider
names (unlike `GET /accounts`, which returns only FK UUIDs).

### Request

| Param | Type | Description |
|-------|------|-------------|
| `providerId` | `string (UUID)` | Filter by ISP/provider. |
| `storeId` | `string (UUID)` | Filter by store. |
| `status` | `string` | Filter by account status. |
| `cursor`, `limit` | | Pagination (see [Conventions](#conventions)). |

```
GET /dashboard/accounts?providerId=1f...&status=active&limit=50
Authorization: Bearer <jwt>
```

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "items": [
      {
        "id": "9c...",
        "accountNumber": "71214756",
        "circuitId": "IC-AWZ-2200",
        "providerId": "1f...",
        "providerName": "Converge",
        "storeId": "7b...",
        "branchCode": "118",
        "storeName": "Store 118",
        "planName": "Biz 100",
        "serviceType": "Fiber",
        "speed": "100Mbps",
        "rate": "1499.00",
        "status": "active",
        "installationDate": "2020-01-01",
        "contractStartDate": "2020-01-01",
        "contractEndDate": "2022-01-01"
      }
    ],
    "nextCursor": "9c..."
  }
}
```

`rate` is the MRC. `circuitId`, `planName`, `serviceType`, `speed`,
`contractStartDate`, `contractEndDate` may be `null`.

---

## GET `/dashboard/billing-history`

Feature 5. Compiled top sheets — the billing history. **DRAFT top sheets are
excluded by default** (a draft is an in-progress compilation). Pass an explicit
`status` to narrow to a single state.

### Request

| Param | Type | Description |
|-------|------|-------------|
| `providerId` | `string (UUID)` | Filter by ISP/provider. |
| `billingPeriod` | `string` | `YYYY-MM`. |
| `status` | `string` | One of `compiled`, `approved`, `paid` (`draft` is never included even if requested via this endpoint's default; passing `draft` explicitly will filter to drafts). |
| `cursor`, `limit` | | Pagination. |

```
GET /dashboard/billing-history?providerId=1f...&billingPeriod=2026-07
Authorization: Bearer <jwt>
```

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "items": [
      {
        "id": "aa...",
        "invoiceNumber": "CONV-202607-0001",
        "batchNumber": "CONV-202607-B001",
        "billingPeriod": "2026-07",
        "providerId": "1f...",
        "providerName": "Converge",
        "accountCount": 80,
        "totalAmount": "160000.00",
        "status": "compiled",
        "compilerId": "5d...",
        "approvedByFinanceId": null,
        "approvedAt": null,
        "paidAt": null,
        "compilationDate": "2026-07-27T02:00:00Z"
      }
    ],
    "nextCursor": null
  }
}
```

Line items for a given top sheet are fetched via `GET /topsheets/{id}/lines`
(unchanged). A per-top-sheet Excel is available at `GET /exports/topsheet/{id}.xlsx`.

---

## GET `/dashboard/archived-accounts`

Feature 6a. Accounts whose status is `inactive` (the terminal archived state after
grace-period expiry). Same denormalized shape as `/dashboard/accounts`.

> **Canonical spec:** [ARCHIVED_ACCOUNTS_API_CONTRACT.md](ARCHIVED_ACCOUNTS_API_CONTRACT.md).
> Unlike its sibling routes, this endpoint is open to **MANAGER, FINANCE and
> SECRETARY** — secretaries file the deactivations, so they need to see the
> accounts those deactivations retired.

### Request

| Param | Type | Description |
|-------|------|-------------|
| `providerId` | `string (UUID)` | Optional filter by ISP/provider. |
| `cursor`, `limit` | | Pagination. |

```
GET /dashboard/archived-accounts?limit=50
Authorization: Bearer <jwt>
```

### Success — `200 OK`

`data.items` is a list of `AccountListItem` (see `/dashboard/accounts`); every item
has `"status": "inactive"`.

> Accounts pending deactivation (`termination_requested`, still billable within the
> 30-day grace window) are **not** archived — list them via
> `GET /accounts?status=termination_requested`.

---

## GET `/dashboard/archived-stores`

Feature 6b. Stores whose status is `closed` (deactivated/closed stores).

### Request

| Param | Type | Description |
|-------|------|-------------|
| `q` | `string` | Optional text filter over store name / branch code. |
| `cursor`, `limit` | | Pagination. |

```
GET /dashboard/archived-stores?q=118
Authorization: Bearer <jwt>
```

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "items": [
      {
        "id": "7b...",
        "storeType": "puregold",
        "branchCode": "118",
        "name": "Store 118",
        "status": "closed",
        "closedReason": "lease ended"
      }
    ],
    "nextCursor": null
  }
}
```

Items are the full `Store` shape (same as `GET /stores`); key fields shown above.

---

## GET `/dashboard/exports/accounts.xlsx`

Feature 4. Streams an `.xlsx` workbook of accounts filtered by ISP and/or status,
with store/provider names, one row per account, and a GRAND TOTAL of MRC. This is a
role-gated alias of the existing `GET /exports/accounts.xlsx`.

### Request

| Param | Type | Description |
|-------|------|-------------|
| `providerId` | `string (UUID)` | Optional ISP/provider filter. |
| `status` | `string` | Optional account-status filter. |

```
GET /dashboard/exports/accounts.xlsx?providerId=1f...&status=active
Authorization: Bearer <jwt>
```

### Success — `200 OK`

- `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- `Content-Disposition: attachment; filename="Accounts_<Provider>_<status>.xlsx"`
- Body: the raw XLSX bytes.

### Errors

| Status | When |
|--------|------|
| `404 Not Found` | `providerId` given but no such provider. |
| `401` / `403` | See [Conventions](#conventions). |

---

## Reused endpoints

These pre-existing endpoints already satisfy dashboard features and remain the
canonical paths; the `/dashboard/*` aliases above delegate to the same use cases.
The front end may call either.

| Feature | Existing endpoint | Notes |
|---------|-------------------|-------|
| Export (4) | `GET /exports/accounts.xlsx?providerId=&status=` | Any authenticated user; identical output to the dashboard alias. |
| Billing history (5) | `GET /topsheets?status=compiled` (also `approved`, `paid`) | Single-status filter only; `/dashboard/billing-history` adds the non-draft default. |
| Archived accounts (6a) | `GET /accounts?status=inactive` | Returns bare `Account` (FK UUIDs, no names). Use `/dashboard/archived-accounts` for names. |
| Pending deactivation | `GET /accounts?status=termination_requested` | Accounts within the 30-day grace window. |
| Archived stores (6b) | `GET /stores?status=closed` | Cursor-paginated store list. |

---

## Changelog

- **2026-08-05** — `/dashboard/archived-accounts` role gate widened to include
  **SECRETARY**; filtering unchanged (`inactive` only). Endpoint spec extracted to
  [ARCHIVED_ACCOUNTS_API_CONTRACT.md](ARCHIVED_ACCOUNTS_API_CONTRACT.md).
- **2026-07-27** — Initial draft: `/dashboard/summary`, `/dashboard/accounts`,
  `/dashboard/billing-history`, `/dashboard/archived-accounts`,
  `/dashboard/archived-stores`, `/dashboard/exports/accounts.xlsx`.
