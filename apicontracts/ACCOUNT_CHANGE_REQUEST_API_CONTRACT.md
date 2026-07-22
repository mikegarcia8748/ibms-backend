# Account Change Request API Contract

> **Status:** In Review
> **Last Updated:** 2026-07-21

Secretaries submit proposed account field changes. Changes only apply after a manager approves.

---

## Endpoints

All endpoints require authentication. Base path: `/accounts/{accountId}/change-requests`.

| Method | Path | Role Required | Description |
|--------|------|---------------|-------------|
| POST | `/accounts/{accountId}/change-requests` | SECRETARY | Submit a change request |
| GET | `/accounts/{accountId}/change-requests` | Any authenticated | List change requests (paginated, filterable) |
| GET | `/accounts/{accountId}/change-requests/{requestId}` | Any authenticated | Get single request with diff (current vs proposed) |
| POST | `/accounts/{accountId}/change-requests/{requestId}/approve` | MANAGER | Approve and apply changes to account |
| POST | `/accounts/{accountId}/change-requests/{requestId}/reject` | MANAGER | Reject with reason |
| POST | `/accounts/{accountId}/change-requests/{requestId}/cancel` | SECRETARY | Cancel own pending request |

## Editable Fields (delta — only changed fields submitted)

- `accountNumber` (string) — the ISP account number
- `installationDate` (ISO date string, e.g. `"2026-08-15"`)
- `rate` (string, 2dp decimal, e.g. `"2500.00"`) — monthly recurring charge
- `providerId` (UUID string) — references existing provider entity
- `circuitId` (string)
- `planName` (string)
- `proofAttachmentId` (UUID string, optional) — proof of contract/installation attachment

---

## Request / Response Schemas

### POST /accounts/{accountId}/change-requests (Submit)

Request body (all fields optional, at least one required):

```json
{
  "accountNumber": "ACC-12345",
  "installationDate": "2026-08-15",
  "rate": "2500.00",
  "providerId": "uuid-of-provider",
  "circuitId": "CKT-001",
  "planName": "Enterprise 100Mbps",
  "proofAttachmentId": "uuid-of-attachment"
}
```

Response **201**:

```json
{
  "data": {
    "id": "uuid-of-change-request",
    "accountId": "uuid-of-account",
    "submittedById": "uuid-of-secretary",
    "status": "pending",
    "accountNumberNew": "ACC-12345",
    "installationDateNew": null,
    "rateNew": "2500.00",
    "providerIdNew": null,
    "circuitIdNew": "CKT-001",
    "planNameNew": null,
    "proofAttachmentId": null,
    "approvedById": null,
    "approvedAt": null,
    "rejectedReason": null,
    "cancelledAt": null,
    "createdAt": "2026-07-21T08:00:00Z",
    "updatedAt": "2026-07-21T08:00:00Z"
  }
}
```

---

### GET /accounts/{accountId}/change-requests (List)

Query params:

- `status` (optional): filter by `"pending"`, `"approved"`, `"rejected"`, `"cancelled"`
- `cursor` (optional): pagination cursor
- `limit` (optional, default 20, max 100)

Response **200** (cursor-paginated):

```json
{
  "data": [ "...array of AccountChangeRequest objects..." ],
  "cursor": "next-page-cursor-or-null"
}
```

---

### GET /accounts/{accountId}/change-requests/{requestId} (Get with Diff)

Response **200** — enriched with diff showing current account values alongside proposed changes:

```json
{
  "data": {
    "id": "uuid-of-change-request",
    "accountId": "uuid-of-account",
    "submittedById": "uuid-of-secretary",
    "status": "pending",
    "accountNumberNew": "ACC-12345",
    "installationDateNew": null,
    "rateNew": "2500.00",
    "providerIdNew": null,
    "circuitIdNew": "CKT-001",
    "planNameNew": "Enterprise 100Mbps",
    "proofAttachmentId": "uuid-of-proof",
    "approvedById": null,
    "approvedAt": null,
    "rejectedReason": null,
    "cancelledAt": null,
    "createdAt": "2026-07-21T08:00:00Z",
    "updatedAt": "2026-07-21T08:00:00Z",
    "diff": [
      { "field": "accountNumber", "currentValue": "ACC-OLD", "proposedValue": "ACC-12345" },
      { "field": "rate", "currentValue": "1500.00", "proposedValue": "2500.00" },
      { "field": "circuitId", "currentValue": null, "proposedValue": "CKT-001" },
      { "field": "planName", "currentValue": "Basic 50Mbps", "proposedValue": "Enterprise 100Mbps" }
    ]
  }
}
```

The `diff` array contains **only the fields that have proposed changes** (non-null new values). Each entry shows the current live account value and the proposed replacement.

---

### POST .../approve (Approve)

No request body required.

Response **200**: Returns the updated AccountChangeRequest object with `status="approved"`, `approvedById`, `approvedAt` populated.

```json
{
  "data": {
    "id": "uuid-of-change-request",
    "accountId": "uuid-of-account",
    "submittedById": "uuid-of-secretary",
    "status": "approved",
    "accountNumberNew": "ACC-12345",
    "installationDateNew": null,
    "rateNew": "2500.00",
    "providerIdNew": null,
    "circuitIdNew": "CKT-001",
    "planNameNew": null,
    "proofAttachmentId": null,
    "approvedById": "uuid-of-manager",
    "approvedAt": "2026-07-21T09:00:00Z",
    "rejectedReason": null,
    "cancelledAt": null,
    "createdAt": "2026-07-21T08:00:00Z",
    "updatedAt": "2026-07-21T09:00:00Z"
  }
}
```

---

### POST .../reject (Reject)

Request body:

```json
{
  "reason": "The requested rate does not match the contract terms"
}
```

Response **200**: Returns the updated AccountChangeRequest object with `status="rejected"`, `rejectedReason` populated.

```json
{
  "data": {
    "id": "uuid-of-change-request",
    "accountId": "uuid-of-account",
    "submittedById": "uuid-of-secretary",
    "status": "rejected",
    "accountNumberNew": "ACC-12345",
    "installationDateNew": null,
    "rateNew": "2500.00",
    "providerIdNew": null,
    "circuitIdNew": "CKT-001",
    "planNameNew": null,
    "proofAttachmentId": null,
    "approvedById": "uuid-of-manager",
    "approvedAt": null,
    "rejectedReason": "The requested rate does not match the contract terms",
    "cancelledAt": null,
    "createdAt": "2026-07-21T08:00:00Z",
    "updatedAt": "2026-07-21T09:00:00Z"
  }
}
```

---

### POST .../cancel (Cancel)

No request body required.

Response **200**: Returns the updated AccountChangeRequest object with `status="cancelled"`, `cancelledAt` populated.

```json
{
  "data": {
    "id": "uuid-of-change-request",
    "accountId": "uuid-of-account",
    "submittedById": "uuid-of-secretary",
    "status": "cancelled",
    "accountNumberNew": "ACC-12345",
    "installationDateNew": null,
    "rateNew": "2500.00",
    "providerIdNew": null,
    "circuitIdNew": "CKT-001",
    "planNameNew": null,
    "proofAttachmentId": null,
    "approvedById": null,
    "approvedAt": null,
    "rejectedReason": null,
    "cancelledAt": "2026-07-21T09:00:00Z",
    "createdAt": "2026-07-21T08:00:00Z",
    "updatedAt": "2026-07-21T09:00:00Z"
  }
}
```

---

## Error Cases

| Status | Code/Scenario | Body |
|--------|---------------|------|
| 404 | Account or change request not found | `{"error": "not_found", "message": "..."}` |
| 403 | Insufficient role / non-submitter trying to cancel | `{"error": "forbidden", "message": "..."}` |
| 409 | Request not in PENDING status (already approved/rejected/cancelled) | `{"error": "conflict", "message": "only pending requests can be ..."}` |
| 409 | Account not ACTIVE (terminated/transferred/inactive) | `{"error": "conflict", "message": "can only submit changes for active accounts"}` |
| 422 | Validation: no fields changed, rate <= 0, invalid providerId, etc. | `{"error": "validation", "message": "..."}` |

---

## Behavior Notes

1. **Multiple pending per account allowed**: Unlike the original design, multiple pending change requests can coexist for the same account. Submitting a new request does **not** auto-cancel existing pending requests.
2. **Approval applies immediately**: When a manager approves a request, the proposed changes are written to the account record right away:
   - **Field changes**: Only the non-null proposed fields overwrite the current account values. Fields left null in the request are untouched.
   - **Rate change (MRC)**: The new rate takes effect immediately on the account. The next topsheet compilation uses the updated rate. If a topsheet was already compiled for the current billing period, it remains unchanged.
   - **Proof attachment**: If `proofAttachmentId` is provided, the attachment is appended to the account's `subscriptionProofIds` list (via the existing `account_attachments` junction table). The list is **additive only** — existing proofs are preserved and cannot be removed through a change request.
3. **What cannot be changed**: The following fields are intentionally excluded from change requests:
   - **`storeId`** — moving an account between stores is handled by the dedicated transfer flow (`POST /transfers`), which has its own proof and audit trail.
   - **Removing proofs** — `subscriptionProofIds` is additive only. Existing proofs cannot be removed via a change request.
4. **Stale pending requests**: If multiple pending requests exist and one is approved, the account fields are updated. Other pending requests remain in `pending` status — their diffs may become stale relative to the new account state.
   - **Frontend guidance**: The `GET .../{requestId}` endpoint computes the `diff` array at request time using the **current** account values. If the account was modified after the request was submitted (e.g., another change request was approved), the `currentValue` in the diff will reflect the latest state, which may differ from what the secretary originally saw. The frontend should compare `createdAt` of the change request against the account's `updatedAt` — if the account was updated after the request was submitted, show a warning banner: *"This account has been modified since this request was submitted. The current values shown below may differ from what was originally proposed."*
5. **Activity log entries**: The following actions are recorded in the activity log:
   - `account_change_request.submitted` — when secretary submits
   - `account_change_request.approved` — when manager approves
   - `account_change_request.rejected` — when manager rejects
   - `account_change_request.cancelled` — when secretary cancels
   - The `details` field includes the actor's role at the time of the action for audit purposes.
6. **SYSADMIN**: The SYSADMIN role has global access and can perform any action.

---

## Code Examples

### cURL — Submit a change request

```bash
curl -X POST "https://api.example.com/accounts/{accountId}/change-requests" \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{
    "rate": "3000.00",
    "planName": "Premium 200Mbps"
  }'
```

### cURL — Approve a change request

```bash
curl -X POST "https://api.example.com/accounts/{accountId}/change-requests/{requestId}/approve" \
  -H "Authorization: Bearer {token}"
```

### cURL — Reject a change request

```bash
curl -X POST "https://api.example.com/accounts/{accountId}/change-requests/{requestId}/reject" \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Rate does not match contract"}'
```

### cURL — Cancel a change request

```bash
curl -X POST "https://api.example.com/accounts/{accountId}/change-requests/{requestId}/cancel" \
  -H "Authorization: Bearer {token}"
```

### JavaScript (Fetch) — Submit

```javascript
const response = await fetch(`/accounts/${accountId}/change-requests`, {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({
    rate: '3000.00',
    planName: 'Premium 200Mbps',
  }),
});
const { data } = await response.json();
```

### JavaScript (Fetch) — Get with diff for manager review

```javascript
const response = await fetch(`/accounts/${accountId}/change-requests/${requestId}`, {
  headers: { 'Authorization': `Bearer ${token}` },
});
const { data } = await response.json();
// data.diff contains [{field, currentValue, proposedValue}, ...]
```

### JavaScript (Fetch) — List change requests with status filter

```javascript
const response = await fetch(
  `/accounts/${accountId}/change-requests?status=pending&limit=20`,
  { headers: { 'Authorization': `Bearer ${token}` } },
);
const { data, cursor } = await response.json();
```

---

## Frontend Guidance

1. **Secretary view**: Show a form with the current account values pre-filled. Secretary modifies only the fields they want to change. On submit, send **only the changed fields** (delta). Display pending/history list filtered by their submissions.
2. **Manager view**: Show a review panel with the `diff` array rendered as a side-by-side or inline comparison (current → proposed). Provide Approve and Reject buttons. Reject requires a reason text input.
3. **Status badges**: Use distinct colors for pending (yellow/orange), approved (green), rejected (red), cancelled (gray).
4. **Stale diff warning**: Since multiple pending requests can coexist, when a manager opens a request whose diff was computed against older account values, show a warning that the current account state may have changed since submission.
5. **Proof attachment (inline upload)**: Show an optional file upload field in the submission form. On submit, the frontend transparently uploads the file via the existing presign flow (`POST /attachments/presign/upload` → `PUT /attachments/{id}/blob`) and includes the resulting `proofAttachmentId` in the change request body. To add proof **after** submission, the secretary must cancel their pending request and re-submit with the proof attached.
