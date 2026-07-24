# Account Export — API Contract

**Feature:** Export filtered accounts to an Excel spreadsheet, selectable by ISP provider and/or account status.

Base URL: `/` · Auth: `Authorization: Bearer <JWT>` · Role: **any authenticated role**.

---

## Endpoints

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/exports/accounts.xlsx` | Bearer (any role) | Download filtered accounts as an Excel spreadsheet. `providerId` and `status` are optional query parameters. |

---

## GET `/exports/accounts.xlsx`

Downloads a filtered list of accounts as an Excel (`.xlsx`) file. The spreadsheet contains a title, a metadata block summarizing the applied filters, a column-header row, one row per account, and a grand total of the MRC (monthly recurring charge) column.

### Query Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `providerId` | `string (UUID)` | No | Filter by ISP provider. Omit to include all providers. |
| `status` | `string (enum)` | No | Filter by account status. Values: `active`, `termination_requested`, `terminated`, `transferred`, `inactive`. Omit to include all statuses. |

### Authorization

Bearer token. Allowed roles: **any authenticated role** (SYSADMIN, SECRETARY, FINANCE, MANAGER).

### Excel Column Layout

| # | Column | Description |
|---|--------|-------------|
| 1 | NO. | Row number (1-based). |
| 2 | STORE CO | Store branch code. |
| 3 | STORE NAME | Store display name. |
| 4 | PROVIDER | ISP provider name. |
| 5 | ACCT# | Account number. |
| 6 | CID# | Circuit identifier (may be blank). |
| 7 | PLAN NAME | Plan name (may be blank). |
| 8 | SERVICE TYPE | Service type (may be blank). |
| 9 | SPEED | Speed (may be blank). |
| 10 | MRC | Monthly recurring charge (rate). |
| 11 | INSTALLATION DATE | Account installation date. |
| 12 | CONTRACT START | Contract start date (may be blank). |
| 13 | CONTRACT END | Contract end date (may be blank). |
| 14 | STATUS | Account status. |

A **GRAND TOTAL** row appears at the bottom with the sum of all MRC values.

### Success — `200 OK`

| Header | Value |
|--------|-------|
| `Content-Type` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| `Content-Disposition` | `attachment; filename="Accounts_<provider\|all>_<status\|all>.xlsx"` |

Body: Binary Excel (XLSX) bytes. The file starts with the ZIP magic bytes `PK`.

### Generated Filename

The filename follows the pattern `Accounts_<provider>_<status>.xlsx`:

- When `providerId` is provided and valid, `<provider>` is the provider name (spaces replaced with underscores).
- When `providerId` is omitted, `<provider>` is `all`.
- When `status` is provided, `<status>` is the status value.
- When `status` is omitted, `<status>` is `all`.

Example: `Accounts_Converge_active.xlsx`, `Accounts_all_all.xlsx`.

### Error Responses

| Status | Condition | Body |
|--------|-----------|------|
| `401` | Missing or invalid bearer token. | Standard error envelope. |
| `404` | `providerId` provided but no provider found with that ID. | Standard error envelope: `"provider <id> not found"`. |
| `400` | `status` value is not a valid account status enum. | Standard error envelope. |

### Side Effects

None. This is a read-only export endpoint.

### Idempotency

Not applicable — GET request with no side effects.

---

## Example — cURL

### Export all accounts (no filters)

```bash
curl -O -J http://localhost:8080/exports/accounts.xlsx \
  -H "Authorization: Bearer <jwt>"
```

### Export accounts for a specific provider

```bash
curl -O -J "http://localhost:8080/exports/accounts.xlsx?providerId=<provider-uuid>" \
  -H "Authorization: Bearer <jwt>"
```

### Export active accounts for a specific provider

```bash
curl -O -J "http://localhost:8080/exports/accounts.xlsx?providerId=<provider-uuid>&status=active" \
  -H "Authorization: Bearer <jwt>"
```

### Export all terminated accounts

```bash
curl -O -J "http://localhost:8080/exports/accounts.xlsx?status=terminated" \
  -H "Authorization: Bearer <jwt>"
```

---

## Example — JavaScript (Fetch)

```javascript
const response = await fetch('/exports/accounts.xlsx?providerId=<uuid>&status=active', {
  headers: { Authorization: `Bearer ${token}` },
});

const blob = await response.blob();
const url = URL.createObjectURL(blob);
const a = document.createElement('a');
a.href = url;
a.download = response.headers
  .get('Content-Disposition')
  ?.match(/filename="(.+)"/)?.[1] ?? 'accounts.xlsx';
a.click();
URL.revokeObjectURL(url);
```

---

## Notes for Frontend

- The endpoint streams a binary attachment — it bypasses the standard JSON response envelope.
- Use `Content-Disposition` header to extract the suggested filename.
- Both `providerId` and `status` are optional; omitting both exports every account in the system.
- The Excel file includes a metadata block at the top showing the applied filters and total account count, followed by the data table and a GRAND TOTAL row summing all MRC values.
- Rows are sorted by store branch code, then account number.
- Large exports may take a few seconds; consider showing a loading indicator to the user.
