# Archived Accounts API Contract

> **Status:** APPROVED
> **Last Updated:** 2026-08-05

The archive view for ISP accounts: a denormalized, paginated list of accounts that
have reached the terminal `inactive` state after their 30-day grace period expired.
This is the canonical spec for `GET /dashboard/archived-accounts`; the entry in
[MANAGER_DASHBOARD_API_CONTRACT.md](MANAGER_DASHBOARD_API_CONTRACT.md) (feature 6a)
defers to this document.

---

## Endpoints

| Method | Path | Role Required | Description |
|--------|------|---------------|-------------|
| GET | `/dashboard/archived-accounts` | MANAGER, FINANCE, SECRETARY | Archived (`inactive`) accounts, with provider and store names |

SYSADMIN is auto-admitted to every endpoint as a global superuser and so is omitted
from the role column, per the convention used across these contracts.

**SECRETARY access is intentional.** Secretaries file the deactivations that produce
archived accounts, so they need to see where those accounts landed. This is the only
`/dashboard/*` route open to them — every sibling route (`/dashboard/summary`,
`/dashboard/accounts`, `/dashboard/billing-history`, `/dashboard/archived-stores`,
`/dashboard/exports/accounts.xlsx`) remains MANAGER/FINANCE and returns `403` to a
secretary. `PENDING` users (awaiting role assignment) get `403` here too.

---

## What counts as "archived"

The endpoint returns **exactly one status: `inactive`**. Every item in
`data.items` has `"status": "inactive"`.

| Status | In the archive? | Why |
|--------|-----------------|-----|
| `inactive` | **yes** | Terminal state, reached when the 30-day grace period expires. |
| `termination_requested` | no | Pending deactivation and **still billable** inside the grace window. Fetch separately — see below. |
| `transferred` | no | A lineage marker on a superseded row, not an archive state: the replacement account created by the transfer carries the live service. |
| `active` | no | Live. |

### `terminated` is not a status in this API

The account status enum is exactly:

```
active | termination_requested | transferred | inactive
```

A `terminated` value existed in the original schema but was removed in migration
`V14__deactivation_api_enhancements.sql`, which raises an exception if any row still
uses it. **`inactive` is the state that `terminated` used to mean.** Clients still
declaring `terminated` (for example a TypeScript
`AccountStatus` union, or an array literal in an archive filter) should drop it and
use `inactive`.

> **Caution — unknown status values do not error.** Across the account list
> endpoints, an unrecognized `?status=` value parses to `null`, which means *no
> filter*. So `GET /accounts?status=terminated` returns **every** account —
> `active` and `transferred` included — rather than an empty list or a `400`.
> Validate status values client-side.

---

## GET `/dashboard/archived-accounts`

### Request

```
GET /dashboard/archived-accounts?providerId=1f...&limit=50
Authorization: Bearer <jwt>
```

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `providerId` | `string (UUID)` | — | Optional filter by ISP/provider. |
| `cursor` | `string` | — | Opaque keyset cursor; pass back the previous `nextCursor`. |
| `limit` | `int` | `50` | Clamped to `1..100`. |

There is no `status` parameter — the status set is fixed. There is also no
`storeId` filter on this route.

### Success — `200 OK`

Cursor/keyset paginated; there is **no total row count**. `nextCursor` is `null` on
the last page. Use `GET /dashboard/summary` → `statusBreakdown` for an
`inactive` count (MANAGER/FINANCE only).

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "items": [
      {
        "id": "9c2f8e14-3b7a-4d61-9f02-7a1c5e8b4d33",
        "accountNumber": "PLDT-000123",
        "circuitId": "CIR-88421",
        "providerId": "2a6d1f90-5c33-4e88-b1a7-0d94f6e2c581",
        "providerName": "PLDT",
        "storeId": "7b104c2e-9a55-4f13-8c60-2e7d38b9a4f6",
        "branchCode": "118",
        "storeName": "Puregold Store 118",
        "planName": "Fiber Biz 500",
        "serviceType": "fiber",
        "speed": "500 Mbps",
        "rate": "2500.00",
        "status": "inactive",
        "installationDate": "2021-03-15",
        "contractStartDate": "2021-04-01",
        "contractEndDate": "2024-03-31"
      }
    ],
    "nextCursor": null
  }
}
```

### Item fields (`AccountListItem`)

Same shape as `/dashboard/accounts` — denormalized with provider and store names,
unlike `GET /accounts`, which returns bare FK UUIDs.

| Field | Type | Notes |
|-------|------|-------|
| `id` | `string (UUID)` | Account id. |
| `accountNumber` | `string` | |
| `circuitId` | `string \| null` | Optional. |
| `providerId` | `string (UUID)` | |
| `providerName` | `string` | Denormalized from the provider. |
| `storeId` | `string (UUID)` | |
| `branchCode` | `string` | Denormalized from the store. |
| `storeName` | `string` | Denormalized from the store. |
| `planName` | `string \| null` | |
| `serviceType` | `string \| null` | |
| `speed` | `string \| null` | |
| `rate` | `string` | Decimal string, 2 dp (e.g. `"2500.00"`). |
| `status` | `string` | Always `"inactive"` on this endpoint. |
| `installationDate` | `string (YYYY-MM-DD)` | |
| `contractStartDate` | `string (YYYY-MM-DD) \| null` | |
| `contractEndDate` | `string (YYYY-MM-DD) \| null` | |

The item carries **no** `terminationRequestedAt` or `graceEndDate`. Those are only
meaningful for `termination_requested` accounts and are served by
`GET /accounts?status=termination_requested`.

### Errors

| Status | When |
|--------|------|
| `401 Unauthorized` | Missing or invalid bearer token. |
| `403 Forbidden` | Authenticated as `PENDING` (any assigned role is permitted). |
| `400 Bad Request` | Malformed or unknown `cursor` (`"invalid cursor"`). |

---

## Front-end integration notes

**A client-side "archived" filter spanning three statuses does not match this
endpoint.** A predicate such as
`["termination_requested", "terminated", "inactive"].includes(a.status)` is broader
than what the API returns, and `terminated` no longer exists. To render an archive
view that includes pending deactivations, make two calls:

| Bucket | Call | Shape | Who can call it |
|--------|------|-------|-----------------|
| Terminal (archived) | `GET /dashboard/archived-accounts` | `AccountListItem` — provider/store names included | MANAGER, FINANCE, SECRETARY |
| Pending deactivation | `GET /accounts?status=termination_requested` | Bare `Account` — FK UUIDs, **no** names; includes `graceEndDate` | Any authenticated user |

`GET /dashboard/accounts?status=termination_requested` returns the named shape but
is **MANAGER/FINANCE only**, so a secretary cannot use it — hence the bare-`Account`
route above for that role.

Once wired up, the client should render `data.items` as returned rather than
re-filtering by status, and drop `terminated` from its status union.

---

## Changelog

- **2026-08-05** — Role gate widened from MANAGER/FINANCE to include SECRETARY.
  Filtering is unchanged (`inactive` only). Contract extracted from
  `MANAGER_DASHBOARD_API_CONTRACT.md` (feature 6a) into this document.
