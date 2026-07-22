# Bulk Import Accounts — API Contract

**Feature:** Bulk import of providers, stores, and accounts from an XLSX spreadsheet.

Base URL: `/api/v1` · Auth: `Authorization: Bearer <JWT>` · Role: **sysadmin only**.

---

## Endpoint

| Method | Path | Body | Auth | Notes |
|--------|------|------|------|-------|
| POST | `/accounts/bulk-import` | `multipart/form-data` | Bearer (sysadmin) | Uploads an XLSX file and bulk-imports providers, stores, and accounts. Returns an import summary. **Idempotent** — re-uploading the same file produces no duplicates. |

---

## Request

### Content-Type
`multipart/form-data`

### Form Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `file` | File | **Yes** | The XLSX spreadsheet to import. Must be a valid `.xlsx` (Office Open XML) file. |

### Expected Spreadsheet Format

The XLSX must have a header row with the following column names (column order does not matter — headers are matched by name, **case-insensitive**):

| Required Column | Description | Example |
|----------------|-------------|---------|
| `Store Code` | Unique store identifier / branch code. Maps to `store.branchCode`. | `118`, `8041` |
| `Store Name` | Display name of the store. Maps to `store.name`. | `PUREGOLD PRICE CLUB - QI CENTRAL` |
| `ISP/Provider` | Provider name. A single file may contain rows for multiple providers (e.g. PLDT, Globe, Radius, Converge). Each unique name is matched or created automatically. | `Globe`, `Converge` |
| `Account No` | Unique account number per row. Satisfies the `(provider_id, account_number)` unique constraint. | `71214756` |
| `Monthly Recurring Amount` | The MRC rate. Must be > 0. Accepts plain numbers (`5598`) and comma-formatted decimals (`2,798.00`). | `5598`, `2,798.00` |

| Optional Column | Description | Example |
|----------------|-------------|---------|
| `Service Type` | Account service type (e.g. SDWAN, Broadband, GPON, DIA). | `SDWAN` |
| `Circuit ID` | Nullable circuit identifier. | `IC-AWZ-2200` |
| `Start Date` | Installation / contract start date. Mixed formats accepted: `M/d/yyyy`, `MM/dd/yyyy`, `MMM d, yyyy`, Excel serial dates. Nullable. | `11/20/2024`, `45572` |

### Rows Skipped

Rows are skipped (and reported in `skipReasons`) when:
- `Store Code` is blank
- `ISP/Provider` is blank
- `Account No` is blank
- `Monthly Recurring Amount` is blank, zero, or not a valid positive number

---

## Response

### Success — `200 OK`

Returns the standard response envelope with a `BulkImportSummary` payload:

```json
{
  "result": "success",
  "message": "bulk import completed",
  "status": "200",
  "data": {
    "providers": [
      {
        "name": "Converge",
        "created": true,
        "accountsCreated": 120,
        "accountsReused": 0
      },
      {
        "name": "Globe",
        "created": true,
        "accountsCreated": 50,
        "accountsReused": 0
      }
    ],
    "storesCreated": 15,
    "storesReused": 0,
    "accountsCreated": 170,
    "accountsReused": 0,
    "rowsSkipped": 2,
    "skipReasons": [
      "Row 45: missing Store Code",
      "Row 112: invalid or zero Monthly Recurring Amount"
    ],
    "totalRows": 172
  }
}
```

### Response Fields — `BulkImportSummary`

| Field | Type | Description |
|-------|------|-------------|
| `providers` | `ProviderImportSummary[]` | Per-provider breakdown of the import. Sorted alphabetically by provider name. See below. |
| `storesCreated` | `integer` | Number of new stores created in this import. |
| `storesReused` | `integer` | Number of stores that already existed (matched by `branchCode` / Store Code) and were reused. |
| `accountsCreated` | `integer` | Total number of new accounts created in this import (across all providers). |
| `accountsReused` | `integer` | Total number of accounts that already existed (matched by `providerId` + `accountNumber`) and were reused. |
| `rowsSkipped` | `integer` | Number of rows that were skipped due to validation failures. |
| `skipReasons` | `string[]` | Human-readable descriptions of why each row was skipped, including the row number (1-indexed). |
| `totalRows` | `integer` | Total number of data rows processed (excluding the header row). |

### Response Fields — `ProviderImportSummary`

| Field | Type | Description |
|-------|------|-------------|
| `name` | `string` | Provider name (e.g. `"Globe"`, `"Converge"`). |
| `created` | `boolean` | `true` if the provider was newly created in this import; `false` if an existing provider with the same name was reused. |
| `accountsCreated` | `integer` | Number of new accounts created for this provider. |
| `accountsReused` | `integer` | Number of accounts for this provider that already existed and were reused. |

### Error Responses

| Status | Condition | Response |
|--------|-----------|----------|
| `400` | No file uploaded (`file` part missing) | `{"result":"error","message":"file is required","status":"400","data":null}` |
| `400` | File is not a valid XLSX | `{"result":"error","message":"file is not a valid XLSX: ...","status":"400","data":null}` |
| `400` | Missing required columns (incl. `ISP/Provider`) | `{"result":"error","message":"missing required column 'Store Code' in header row","status":"400","data":null}` |
| `400` | No header row in spreadsheet | `{"result":"error","message":"spreadsheet has no header row","status":"400","data":null}` |
| `401` | No bearer token | Standard unauthorized response. |
| `403` | Caller is not sysadmin | Standard forbidden response. |

---

## Idempotency Behavior

The import is **fully idempotent**:

1. **Providers** — matched by name (read from the `ISP/Provider` column). If a provider with that name already exists, it is reused (no duplicate). A single file may contain rows for multiple providers; each is matched or created independently.
2. **Stores** — matched by `branchCode` (the Store Code column). If a store with that branch code exists, it is reused. Same store code appearing multiple times (different service types or providers) creates the store once and links multiple accounts.
3. **Accounts** — matched by `(providerId, accountNumber)`. If an account with the same provider and account number exists, it is reused (no duplicate).

Re-uploading the same file will return:
```json
{
  "providers": [
    {"name": "Converge", "created": false, "accountsCreated": 0, "accountsReused": 120},
    {"name": "Globe", "created": false, "accountsCreated": 0, "accountsReused": 50}
  ],
  "storesCreated": 0,
  "storesReused": 15,
  "accountsCreated": 0,
  "accountsReused": 170
}
```

---

## Side Effects

- **Activity logging:** each newly created account records an `account.bulk_imported` activity entry (visible in `GET /activities`).
- **Placeholder attachment:** a single shared placeholder attachment is created (purpose = `installation_proof`) to satisfy the NOT NULL constraint on `stores.proof_of_installation_id`. All bulk-imported stores reference this one attachment.
- **Invoice sequence:** when a provider is created for the first time, its `invoice_sequences` row is seeded automatically.

---

## Example — cURL

```bash
curl -X POST http://localhost:8080/accounts/bulk-import \
  -H "Authorization: Bearer <your-jwt-token>" \
  -F "file=@/path/to/Converge Accounts.xlsx"
```

### Example — JavaScript / Fetch

```javascript
const formData = new FormData();
formData.append('file', xlsxFile); // xlsxFile is a File object

const response = await fetch('/api/v1/accounts/bulk-import', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
  },
  body: formData,
});

const { data } = await response.json();
console.log(`Imported ${data.accountsCreated} accounts, ${data.rowsSkipped} rows skipped`);
data.providers.forEach(p => {
  console.log(`  ${p.name}: ${p.accountsCreated} created, ${p.accountsReused} reused${p.created ? ' (new)' : ''}`);
});
if (data.skipReasons.length > 0) {
  console.warn('Skipped rows:', data.skipReasons);
}
```

---

## Notes for Frontend

- **Do NOT set `Content-Type` header** when sending multipart form data — the browser/runtime sets it automatically with the correct boundary.
- The import runs in a **single database transaction**: either all valid rows are committed or none are.
- For large files (172+ rows), the request may take a few seconds. Show a loading indicator.
- The `skipReasons` array can be displayed to the user as a warning/notification after a successful import.
- The endpoint is under the `/accounts` route group and requires **sysadmin** role.
