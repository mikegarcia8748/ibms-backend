# Create ISP Account — API Contract

> **Status:** In Review
> **Last Updated:** 2026-07-23

Single ISP account creation with mandatory subscription proof attachment and auto-computed proration.

---

## Prerequisites — Attachment Upload (2-step Presign Flow)

Before calling this endpoint, the subscription proof PDF must be uploaded:

1. `POST /attachments/presign/upload` — creates attachment metadata, returns presigned URL + `attachmentId`
   - Body: `{"filename": "proof.pdf", "contentType": "application/pdf", "purpose": "subscription_proof"}`
   - Response 201: `{"data": {"id": "uuid-attachment", "uploadUrl": "http://...?token=..."}}`
2. `PUT /attachments/{id}/blob?token=<presigned-token>` — uploads actual PDF bytes
   - Body: raw file bytes
   - Response 200: `{"data": {"id": "uuid-attachment", "storedAt": "..."}}`

The `attachmentId` from step 1 becomes the `subscriptionProofId` in the create request.

---

## Endpoint

| Method | Path | Body | Auth | Notes |
|--------|------|------|------|-------|
| POST | `/accounts/isp` | `application/json` | Bearer (SECRETARY or FINANCE) | Creates a single ISP account with mandatory proof of subscription. |

---

## Request

### Content-Type
`application/json`

### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `accountNumber` | `string` | **Yes** | ISP account number. Must be non-blank. Leading/trailing whitespace is trimmed. |
| `circuitId` | `string` | **Yes** | ISP circuit identifier. Must be non-blank. |
| `providerId` | `string (UUID)` | **Yes** | References an existing provider. |
| `storeId` | `string (UUID)` | **Yes** | References an existing store. |
| `rate` | `string (2dp decimal)` | **Yes** | Monthly Recurring Charge. Must be > 0. Example: `"2500.00"` |
| `installationDate` | `string (ISO date)` | **Yes** | Account installation/start date. Cannot be in the future. Format: `"YYYY-MM-DD"` |
| `subscriptionProofId` | `string (UUID)` | **Yes** | References a previously uploaded attachment (subscription proof PDF). Must exist. |

### Example Request

```json
{
  "accountNumber": "ACC-12345",
  "circuitId": "CIR-789-XYZ",
  "providerId": "550e8400-e29b-41d4-a716-446655440000",
  "storeId": "660e8400-e29b-41d4-a716-446655440001",
  "rate": "2500.00",
  "installationDate": "2026-07-20",
  "subscriptionProofId": "770e8400-e29b-41d4-a716-446655440002"
}
```

---

## Response

### Success — `201 Created`

Returns the standard response envelope with the created Account:

```json
{
  "result": "success",
  "message": "created",
  "status": "201",
  "data": {
    "id": "uuid-of-new-account",
    "accountNumber": "ACC-12345",
    "circuitId": "CIR-789-XYZ",
    "providerId": "550e8400-e29b-41d4-a716-446655440000",
    "storeId": "660e8400-e29b-41d4-a716-446655440001",
    "planName": null,
    "serviceType": null,
    "speed": null,
    "contractDurationMonths": null,
    "contractStartDate": null,
    "contractEndDate": null,
    "notes": null,
    "installationFee": null,
    "rate": "2500.00",
    "installationDate": "2026-07-20",
    "billingPeriodLabel": null,
    "isProrated": true,
    "status": "active",
    "terminationRequestedAt": null,
    "subscriptionProofIds": ["770e8400-e29b-41d4-a716-446655440002"],
    "createdAt": "2026-07-23T10:30:00Z",
    "updatedAt": "2026-07-23T10:30:00Z"
  }
}
```

### Response Fields — Account

| Field | Type | Description |
|-------|------|-------------|
| `id` | `string (UUID)` | Unique account identifier. |
| `accountNumber` | `string` | The ISP account number (trimmed). |
| `circuitId` | `string?` | Circuit identifier. |
| `providerId` | `string (UUID)` | Provider reference. |
| `storeId` | `string (UUID)` | Store reference. |
| `rate` | `string (2dp)` | Monthly Recurring Charge. |
| `installationDate` | `string (ISO date)` | Installation/start date. |
| `isProrated` | `boolean` | `true` if first payment will be prorated (installed after provider's payment schedule day). |
| `status` | `string` | Always `"active"` on creation. |
| `subscriptionProofIds` | `string[]` | Linked subscription proof attachment IDs. |
| `createdAt` | `string (ISO instant)` | Creation timestamp. |

### Proration Logic

The `isProrated` flag is auto-computed at creation:
- `isProrated = installationDate.dayOfMonth > provider.paymentScheduleDay`
- If the account was installed AFTER the provider's topsheet processing day, the first payment is prorated.
- The actual prorated AMOUNT is computed at topsheet compilation time by the billing engine.
- Example: Provider processes on day 15. Account installed on day 20 → `isProrated=true`. Installed on day 10 → `isProrated=false`.

---

## Error Responses

| Status | Condition | Example Response |
|--------|-----------|-----------------|
| `400` | accountNumber blank | `{"result":"error","message":"accountNumber is required","status":"400","data":null}` |
| `400` | circuitId blank/missing | `{"result":"error","message":"circuitId is required for ISP accounts","status":"400","data":null}` |
| `400` | rate ≤ 0 or invalid | `{"result":"error","message":"rate (MRC) must be greater than 0","status":"400","data":null}` |
| `400` | installationDate in future | `{"result":"error","message":"installationDate cannot be in the future","status":"400","data":null}` |
| `400` | subscriptionProofId blank | `{"result":"error","message":"subscriptionProofId is required","status":"400","data":null}` |
| `400` | subscriptionProofId not found | `{"result":"error","message":"subscription proof attachment not found","status":"400","data":null}` |
| `400` | unknown providerId | `{"result":"error","message":"unknown providerId {id}","status":"400","data":null}` |
| `400` | unknown storeId | `{"result":"error","message":"unknown storeId {id}","status":"400","data":null}` |
| `409` | duplicate (provider, accountNumber) | `{"result":"error","message":"account ACC-12345 already exists for this provider","status":"409","code":"duplicate_account_number","data":null}` |
| `401` | No/invalid bearer token | Standard unauthorized. |
| `403` | Role is not SECRETARY or FINANCE | Standard forbidden. |

---

## Behavior Notes

1. **Subscription proof is MANDATORY** — unlike the generic `POST /accounts` which treats proof as optional, this ISP endpoint requires a valid uploaded attachment.
2. **Proration is computed, not user-supplied** — the system determines the proration flag based on provider configuration and installation date.
3. **Uniqueness** — `(providerId, accountNumber)` must be unique among live (non-transferred, non-terminated) accounts. Same account number is fine with different providers.
4. **Activity log** — records `account.created` on success.
5. **Transaction** — the entire operation (validation + creation + attachment linking) runs in a single DB transaction.
6. **Input trimming** — `accountNumber` and `circuitId` are trimmed of leading/trailing whitespace before storage.

---

## Code Examples

### cURL

```bash
curl -X POST http://localhost:8080/accounts/isp \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "accountNumber": "ACC-12345",
    "circuitId": "CIR-789-XYZ",
    "providerId": "550e8400-e29b-41d4-a716-446655440000",
    "storeId": "660e8400-e29b-41d4-a716-446655440001",
    "rate": "2500.00",
    "installationDate": "2026-07-20",
    "subscriptionProofId": "770e8400-e29b-41d4-a716-446655440002"
  }'
```

### JavaScript (Fetch) — Full Flow

```javascript
// Step 1: Presign the subscription proof upload
const presignRes = await fetch('/attachments/presign/upload', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({
    filename: 'subscription_proof.pdf',
    contentType: 'application/pdf',
    purpose: 'subscription_proof',
  }),
});
const { data: attachment } = await presignRes.json();

// Step 2: Upload the PDF bytes
await fetch(attachment.uploadUrl, {
  method: 'PUT',
  body: pdfFile, // File or Blob object
});

// Step 3: Create the ISP account
const createRes = await fetch('/accounts/isp', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({
    accountNumber: 'ACC-12345',
    circuitId: 'CIR-789-XYZ',
    providerId: selectedProviderId,
    storeId: selectedStoreId,
    rate: '2500.00',
    installationDate: '2026-07-20',
    subscriptionProofId: attachment.id,
  }),
});
const { data: account } = await createRes.json();
console.log(`Created account ${account.id}, prorated: ${account.isProrated}`);
```

---

## Frontend Guidance

1. **Form fields**: All 7 fields are required. Show red asterisks. Disable submit until all filled.
2. **Provider dropdown**: Fetch from `GET /providers` and display active providers only.
3. **Store dropdown**: Fetch from `GET /stores` and display by branch code + name.
4. **Date picker**: Restrict to today or earlier (no future dates).
5. **Rate field**: Numeric input with 2 decimal places. Validate > 0 client-side.
6. **PDF upload**: Show file picker restricted to `.pdf`. Upload via presign flow BEFORE submitting the form. Show upload progress indicator.
7. **Proration indicator**: After creation, display `isProrated` as a badge/chip on the account card ("Prorated" / "Full Month"). This is informational only — the actual prorated amount is shown when the topsheet is compiled.
8. **Error handling**: Display the `message` field from error responses inline near the relevant form field.
