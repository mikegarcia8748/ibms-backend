# Notification Subscription Administration API Contract

> **Status:** DRAFT
> **Last Updated:** 2026-07-30

Sysadmin surface for deciding **who receives which notification emails**. IBMS emails are
event-driven: eight user actions each enqueue an email to every user subscribed to that
event. Subscriptions are not self-service — a sysadmin configures them, either one user at
a time on the user's profile or in bulk across the organisation.

This contract covers the two already-shipped per-user endpoints plus four new
administration capabilities: a machine-readable **event catalogue**, an **org-wide
subscription matrix**, a **bulk apply** operation, and **per-role defaults** that seed
newly provisioned users.

Every endpoint here is gated to **SYSADMIN** only. They live under `/admin/notifications`,
except the two per-user endpoints which remain on `/users/{id}`.

---

## Endpoints

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/admin/notifications/events` | **New** | The catalogue of subscribable events (key, label, description) |
| GET | `/admin/notifications/subscriptions` | **New** | Org-wide matrix: every user with their subscribed events (paginated, filterable) |
| POST | `/admin/notifications/subscriptions/bulk` | **New** | Add / remove / replace events across many users or whole roles |
| GET | `/admin/notifications/defaults` | **New** | Per-role default subscriptions applied to newly provisioned users |
| PUT | `/admin/notifications/defaults` | **New** | Set per-role defaults |
| PATCH | `/users/{id}/email` | **New** | Set or clear a user's notification delivery address |
| GET | `/users/{id}/notification-subscriptions` | Existing | One user's subscribed events + the catalogue |
| PUT | `/users/{id}/notification-subscriptions` | Existing | Replace one user's subscriptions wholesale |

`POST /users` (provisioning) also gains an optional `email` field, and `UserProfile`
now carries `email` wherever it is returned. Both are additive.

---

## Conventions

**Response envelope.** Every endpoint returns:

```json
{ "result": "success", "message": "success", "status": "200", "data": { } }
```

Note `status` is a **string**, not a number. Errors use the same shape with
`"result": "error"` and `"data": null`.

**Pagination.** `GET /admin/notifications/subscriptions` is cursor/keyset paginated over
users (there is **no total row count**):

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| `cursor` | `string` | — | Opaque cursor; pass back the previous `nextCursor`. It is a user id. |
| `limit` | `int` | `50` | Clamped to `1..100`. |

`data` is `{ "items": [ ... ], "nextCursor": "<user id or null>" }`. `nextCursor` is `null`
on the last page.

**Event keys are server-owned.** Do **not** hardcode the eight keys in the frontend.
Fetch `GET /admin/notifications/events` and render whatever it returns — the set grows as
new notifiable actions ship, and an unknown key sent back to the server is rejected with
`400`.

**Enum wire values are lowercase.** Roles are `sysadmin`, `secretary`, `finance`,
`manager`, `pending`. User statuses are `active`, `inactive`.

**Filter parsing.** Unlike event keys, an **unrecognised `role` or `status` filter value
parses to `null` and is treated as "no filter"** rather than erroring — validate these
client-side. An unrecognised `event` filter value **does** error with `400`.

**Common errors.**

| Status | When |
|--------|------|
| `401 Unauthorized` | Missing/invalid bearer token. |
| `403 Forbidden` | Authenticated but not SYSADMIN. |
| `400 Bad Request` | Unknown event key, unknown role in a body, or malformed `cursor`. |
| `404 Not Found` | Referenced user does not exist. |

---

## Deliverability — read this before building the UI

A subscription row is necessary but **not sufficient** for a user to actually receive
email. Recipients are resolved at send time as:

> subscribed to the event **AND** `status = active` **AND** `email IS NOT NULL`

Two consequences the UI must surface, because the backend fails silently on both:

1. A user with no email address on file, or an inactive user, is **skipped** even though
   their subscription row exists and the matrix shows them as subscribed.
2. If an event ends up with **zero** deliverable subscribers, no outbox row is written at
   all — the notification is dropped, not queued. Nobody is alerted.

Every matrix row therefore carries a computed **`deliverable`** boolean plus a
`notDeliverableReason`. Render subscribed-but-undeliverable users as a warning state, and
warn when an event has no deliverable subscriber anywhere.

**The fix for `no_email` is `PATCH /users/{id}/email`.** Until this release there was no
way at all to put an address on a user — provisioning hardcoded `null` and no endpoint
wrote the column — so on an existing deployment **every** user is `no_email` and every
notification is being dropped. Expect the first load of this screen to be entirely
warnings, and make setting an address a first-class action on each row.

---

## GET `/admin/notifications/events`

The catalogue of subscribable events. Static per deployment — safe for the frontend to
fetch once at app start and cache for the session.

### Request

```
GET /admin/notifications/events
Authorization: Bearer <jwt>
```

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "events": [
      {
        "key": "store.created",
        "label": "New store added",
        "description": "A secretary registered a new store branch.",
        "deliverableSubscribers": 3
      },
      {
        "key": "account.created",
        "label": "New account added",
        "description": "A new ISP account was created for a store.",
        "deliverableSubscribers": 3
      },
      {
        "key": "account.updated",
        "label": "Account details updated",
        "description": "Account details changed directly, or a change request was approved.",
        "deliverableSubscribers": 2
      },
      {
        "key": "account.transferred",
        "label": "Account transferred",
        "description": "An account was moved to a different store.",
        "deliverableSubscribers": 2
      },
      {
        "key": "account.deactivation_requested",
        "label": "Account termination requested",
        "description": "Deactivation was requested and the 30-day grace period began.",
        "deliverableSubscribers": 4
      },
      {
        "key": "account.terminated",
        "label": "Account terminated",
        "description": "A grace period expired and the account became inactive.",
        "deliverableSubscribers": 0
      },
      {
        "key": "topsheet.compiled",
        "label": "Topsheet compiled",
        "description": "A draft top sheet was confirmed into a compiled billing batch.",
        "deliverableSubscribers": 5
      },
      {
        "key": "topsheet.released",
        "label": "Topsheet released to finance",
        "description": "A compiled top sheet was released to Finance for payment.",
        "deliverableSubscribers": 5
      }
    ]
  }
}
```

### Response Fields — `NotificationEventInfo`

| Field | Type | Notes |
|-------|------|-------|
| `key` | `string` | Stable storage/wire identifier. The value to send back in every write. |
| `label` | `string` | Short human label for a checkbox or column header. |
| `description` | `string` | One-sentence helper text explaining what triggers the email. |
| `deliverableSubscribers` | `int` | Live count of users who would actually receive this event today (see [Deliverability](#deliverability--read-this-before-building-the-ui)). `0` means the notification is currently going nowhere. |

> The `key` + `label` pair is identical to the `available[]` array already embedded in the
> two per-user endpoints. This endpoint adds `description` and
> `deliverableSubscribers`, and lets the frontend load the catalogue without first
> picking a user.

---

## GET `/admin/notifications/subscriptions`

The org-wide matrix — one row per user with the events they are subscribed to. This is the
data source for a "who gets what" admin grid.

### Request

```
GET /admin/notifications/subscriptions?role=secretary&event=topsheet.compiled&limit=50
Authorization: Bearer <jwt>
```

Query parameters:

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `role` | `string` | No | Only users with this role. Unrecognised value = no filter. |
| `status` | `string` | No | `active` or `inactive`. Unrecognised value = no filter. |
| `event` | `string` | No | Only users subscribed to this event key. Unrecognised value → `400`. |
| `deliverable` | `boolean` | No | Asymmetric on purpose: `true` returns every active user with an address (subscribed or not); `false` returns only users who are subscribed to something **and** cannot receive it — the fix-me worklist, not every dormant account. Unparseable value = no filter. |
| `cursor` | `string` | No | Pagination cursor from the previous response. |
| `limit` | `int` | No | Page size, default 50, clamped `1..100`. |

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "items": [
      {
        "userId": "uuid-of-user",
        "username": "jdelacruz",
        "name": "Juan Dela Cruz",
        "email": "jdelacruz@example.com",
        "role": "secretary",
        "status": "active",
        "subscribed": ["account.created", "store.created", "topsheet.compiled"],
        "deliverable": true,
        "notDeliverableReason": null
      },
      {
        "userId": "uuid-of-other-user",
        "username": "mreyes",
        "name": "Maria Reyes",
        "email": null,
        "role": "finance",
        "status": "active",
        "subscribed": ["topsheet.released"],
        "deliverable": false,
        "notDeliverableReason": "no_email"
      }
    ],
    "nextCursor": "uuid-of-last-user-or-null"
  }
}
```

### Response Fields — `UserNotificationSubscriptionRow`

| Field | Type | Notes |
|-------|------|-------|
| `userId` | `string (UUID)` | Use this as the `{id}` for the per-user endpoints. |
| `username` | `string` | Login name. |
| `name` | `string` | Display name. |
| `email` | `string \| null` | **`null` means this user can never receive email.** Not present on `UserProfile` elsewhere in the API — exposed here specifically so the admin can spot the gap. |
| `role` | `string` | Lowercase role wire value. |
| `status` | `string` | `active` or `inactive`. |
| `subscribed` | `string[]` | Subscribed event keys, sorted. Empty array when the user receives nothing. |
| `deliverable` | `boolean` | `true` only when `status = active` **and** `email` is non-null. |
| `notDeliverableReason` | `string \| null` | `null` when deliverable; otherwise `"no_email"`, `"inactive"`, or `"no_email_and_inactive"`. |

> Rows are returned for **every** user matching the filters, including users subscribed to
> nothing (`subscribed: []`), so the grid can offer them for opt-in. Ordering is stable
> `(created_at, id)` ascending.

---

## POST `/admin/notifications/subscriptions/bulk`

Apply one change to many users at once. Targets are named either explicitly by
`userIds`, or by `roles` (every user currently holding those roles), and the operation is
one of three modes.

### Request

```
POST /admin/notifications/subscriptions/bulk
Authorization: Bearer <jwt>
Content-Type: application/json
```

```json
{
  "mode": "add",
  "events": ["topsheet.compiled", "topsheet.released"],
  "roles": ["finance"]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `mode` | `string` | **Yes** | `add`, `remove`, or `replace`. See the table below. |
| `events` | `string[]` | **Yes** | Event keys to apply. May be empty **only** when `mode` is `replace` (which then unsubscribes the targets from everything). |
| `userIds` | `string[]` | No | Explicit target users. |
| `roles` | `string[]` | No | Target every user holding any of these roles. |

Supply `userIds`, `roles`, or both — but **at least one must be non-empty**. When both are
given the targets are the union, de-duplicated.

| Mode | Effect on each target user |
|------|---------------------------|
| `add` | Union: the listed events are added; existing subscriptions are kept. Already-subscribed events are a no-op, not an error. |
| `remove` | Difference: the listed events are removed; other subscriptions are kept. Not-subscribed events are a no-op. |
| `replace` | The user's subscription set becomes **exactly** `events`, discarding everything else. Destructive — confirm in the UI. |

The whole operation runs in **one transaction**: if any target user id does not exist the
request fails with `404` and **nothing is applied**. Roles that currently have no members
are not an error — they simply contribute no targets.

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "mode": "add",
    "events": ["topsheet.compiled", "topsheet.released"],
    "usersMatched": 4,
    "usersChanged": 3,
    "undeliverableTargets": 1
  }
}
```

### Response Fields — `BulkNotificationSubscriptionResult`

| Field | Type | Notes |
|-------|------|-------|
| `mode` | `string` | Echo of the requested mode. |
| `events` | `string[]` | Echo of the applied event keys, sorted. |
| `usersMatched` | `int` | Distinct target users resolved from `userIds` + `roles`. |
| `usersChanged` | `int` | Targets whose subscription set actually differed afterwards. `usersMatched - usersChanged` were already in the requested state. |
| `undeliverableTargets` | `int` | Of the matched users, how many cannot receive email (no address or inactive). Surface as a warning — the write succeeded but has no effect for them. |

### Error Responses

| Status | Condition | Body |
|--------|-----------|------|
| `400` | Unknown event key | `{"result":"error","status":"400","message":"unknown notification event 'foo.bar'","data":null}` |
| `400` | Unknown role | `{"result":"error","status":"400","message":"unknown role 'payables'","data":null}` |
| `400` | Both `userIds` and `roles` empty | `{"result":"error","status":"400","message":"specify at least one userId or role","data":null}` |
| `400` | `events` empty with mode `add`/`remove` | `{"result":"error","status":"400","message":"events is required for mode 'add'","data":null}` |
| `404` | A named user id does not exist | `{"result":"error","status":"404","message":"user {id} not found","data":null}` |
| `403` | Caller is not SYSADMIN | Standard forbidden response |

---

## GET `/admin/notifications/defaults`

Per-role default subscriptions. These are applied **only when a new user is provisioned**
— they are a template, not a live rule.

### Request

```
GET /admin/notifications/defaults
Authorization: Bearer <jwt>
```

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "defaults": [
      { "role": "sysadmin", "events": [] },
      { "role": "secretary", "events": ["account.created", "store.created"] },
      { "role": "finance", "events": ["topsheet.compiled", "topsheet.released"] },
      { "role": "manager", "events": ["topsheet.compiled"] },
      { "role": "pending", "events": [] }
    ]
  }
}
```

`defaults` always contains **one entry per role**, in the enum's declaration order, with an
empty `events` array for roles that have no defaults configured. The frontend never has to
handle a missing role.

---

## PUT `/admin/notifications/defaults`

### Request

```
PUT /admin/notifications/defaults
Authorization: Bearer <jwt>
Content-Type: application/json
```

```json
{
  "defaults": [
    { "role": "finance", "events": ["topsheet.compiled", "topsheet.released"] },
    { "role": "manager", "events": ["topsheet.compiled"] }
  ]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `defaults` | `object[]` | **Yes** | One entry per role to set. |
| `defaults[].role` | `string` | **Yes** | Lowercase role wire value. |
| `defaults[].events` | `string[]` | **Yes** | The role's complete new default set. Empty array clears the role's defaults. |

**Partial by role.** Only the roles named in the payload are rewritten; roles absent from
the payload keep their current defaults. Within a named role the `events` array is a
wholesale replacement, so this is safely idempotent. A role listed twice is rejected with
`400`.

### Success — `200 OK`

Returns the **full** defaults map — all roles, not just the ones written — so the client
can replace its cached copy without a follow-up `GET`. Body shape is identical to
`GET /admin/notifications/defaults`.

### Error Responses

| Status | Condition | Body |
|--------|-----------|------|
| `400` | Unknown event key | `{"result":"error","status":"400","message":"unknown notification event 'foo.bar'","data":null}` |
| `400` | Unknown role | `{"result":"error","status":"400","message":"unknown role 'payables'","data":null}` |
| `400` | Same role listed twice | `{"result":"error","status":"400","message":"duplicate role 'finance' in defaults","data":null}` |
| `403` | Caller is not SYSADMIN | Standard forbidden response |

### What defaults do and do not do

| Aspect | Behaviour |
|--------|-----------|
| **New users** | `POST /users` seeds the new user's subscriptions from the defaults for the role in the provisioning request, inside the same transaction. |
| **Existing users** | **Never touched.** Changing a default does not retrofit anyone. Use `POST /admin/notifications/subscriptions/bulk` with `roles` to apply deliberately. |
| **Role changes** | `PATCH /users/{id}/role` does **not** re-seed subscriptions. A user who moves from secretary to finance keeps their old subscriptions until a sysadmin changes them. |
| **Empty defaults** | A provisioned user with no defaults for their role starts subscribed to nothing and receives no email. |

---

## GET `/users/{id}/notification-subscriptions` (existing)

Unchanged, documented here for completeness.

### Success — `200 OK`

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "subscribed": ["account.created", "store.created"],
    "available": [
      { "key": "store.created", "label": "New store added", "description": "A secretary registered a new store branch." },
      { "key": "account.created", "label": "New account added", "description": "A new ISP account was created for a store." }
    ]
  }
}
```

`subscribed` is sorted event keys. `available` is the full catalogue, abbreviated above.

> **Change in this release:** `available[]` entries gain a `description` field. This is
> additive — existing clients reading `key`/`label` are unaffected. `available[]` here does
> **not** carry `deliverableSubscribers`; use `GET /admin/notifications/events` for that.

### Error Responses

| Status | Condition |
|--------|-----------|
| `404` | User not found (`"user {id} not found"`) |
| `403` | Caller is not SYSADMIN |

---

## PUT `/users/{id}/notification-subscriptions` (existing)

Replaces the user's subscription set wholesale.

### Request

```json
{ "events": ["account.created", "store.created"] }
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `events` | `string[]` | **Yes** | The user's complete new subscription set. Send `[]` to unsubscribe from everything. |

This is a **PUT, not a PATCH** — omitting an event unsubscribes it. Always send the full
set the checkbox grid represents, never a delta.

### Success — `200 OK`

Same body as the `GET`, reflecting the new state.

### Error Responses

| Status | Condition | Body |
|--------|-----------|------|
| `400` | Unknown event key | `{"result":"error","status":"400","message":"unknown notification event 'foo.bar'","data":null}` |
| `404` | User not found | `{"result":"error","status":"404","message":"user {id} not found","data":null}` |
| `403` | Caller is not SYSADMIN | Standard forbidden response |

---

## PATCH `/users/{id}/email`

Sets or clears the address notification mail is delivered to. This is the **only** way an
address ever gets onto a user record.

### Request

```json
{ "email": "jdelacruz@example.com" }
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `email` | `string \| null` | **Yes** | The delivery address. Send an explicit `null` to clear it. |

The address is **trimmed and lowercased** before storage, so `"  John.Doe@Example.COM "`
is stored and returned as `john.doe@example.com`. Compare case-insensitively client-side.

Addresses are **not unique** — two users may share a team mailbox. Recipient resolution
de-duplicates by address, so a shared mailbox still receives one copy of each notification.

### Success — `200 OK`

`data` is the full updated `UserProfile`:

```json
{
  "result": "success",
  "message": "success",
  "status": "200",
  "data": {
    "id": "uuid-of-user",
    "username": "jdelacruz",
    "name": "Juan Dela Cruz",
    "email": "jdelacruz@example.com",
    "role": "secretary",
    "status": "active",
    "mustChangePassword": false
  }
}
```

> `email` is **omitted** from `UserProfile` when it is null (the API does not serialize
> nulls that equal a field default). Treat absent and `null` as the same thing.

### Error Responses

| Status | Condition | Body |
|--------|-----------|------|
| `400` | Malformed address | `{"result":"error","status":"400","message":"'nope' is not a valid email address","data":null}` |
| `404` | User not found | `{"result":"error","status":"404","message":"user {id} not found","data":null}` |
| `403` | Caller is not SYSADMIN | Standard forbidden response |

### Provisioning with an address

`POST /users` accepts the same optional field, normalised identically:

```json
{ "username": "jdelacruz", "name": "Juan Dela Cruz", "email": "jdelacruz@example.com", "role": "secretary" }
```

Omitting it provisions a user who cannot receive notifications until `PATCH` supplies one.
Clearing or changing an address never touches subscriptions — the two are independent.

---

## Behaviour Notes

1. **Sysadmin-only, by design.** There is no self-service preference screen and no
   per-user opt-out. Users cannot see or change what they receive or the address it goes
   to; only a sysadmin can.
2. **No email is sent by these endpoints.** They only decide future recipients. Changing a
   subscription never generates a notification, and never affects emails already queued.
3. **Subscriptions survive role changes and deactivation.** Rows are keyed by user and are
   only removed by an explicit write, or by `ON DELETE CASCADE` if the user row is deleted.
   Reactivating a user restores their previous delivery.
4. **Duplicate `account.updated` emitters.** Both a direct account edit and an approved
   account change request raise `account.updated`, so a single business change can produce
   two emails. Expected, not a bug.
5. **`account.terminated` has no human actor.** It is raised by the nightly grace-expiry
   job, so its email has no "changed by" attribution.
6. **At-least-once delivery.** The dispatcher drains the outbox roughly once a minute; a
   crash between send and status write can re-send. Subscribers may occasionally see a
   duplicate.
7. **SYSADMIN**: The SYSADMIN role has global access and can perform any action.

---

## Code Examples

### cURL — Fetch the event catalogue

```bash
curl "http://localhost:8080/admin/notifications/events" \
  -H "Authorization: Bearer <jwt>"
```

### cURL — Find subscribed users who cannot receive email

```bash
curl "http://localhost:8080/admin/notifications/subscriptions?deliverable=false&limit=100" \
  -H "Authorization: Bearer <jwt>"
```

### cURL — Subscribe all of Finance to both topsheet events

```bash
curl -X POST "http://localhost:8080/admin/notifications/subscriptions/bulk" \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{"mode":"add","events":["topsheet.compiled","topsheet.released"],"roles":["finance"]}'
```

### cURL — Set the default subscriptions for new secretaries

```bash
curl -X PUT "http://localhost:8080/admin/notifications/defaults" \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{"defaults":[{"role":"secretary","events":["account.created","store.created"]}]}'
```

### JavaScript (Fetch) — Load the catalogue once, then page the matrix

```javascript
const auth = { 'Authorization': `Bearer ${token}` };

// 1. Catalogue drives the grid columns — never hardcode the keys.
const { data: catalogue } = await (
  await fetch('/admin/notifications/events', { headers: auth })
).json();
const columns = catalogue.events; // [{ key, label, description, deliverableSubscribers }]

// 2. Page through users to fill the rows.
const rows = [];
let cursor = null;
do {
  const qs = new URLSearchParams({ limit: '100', ...(cursor && { cursor }) });
  const { data } = await (
    await fetch(`/admin/notifications/subscriptions?${qs}`, { headers: auth })
  ).json();
  rows.push(...data.items);
  cursor = data.nextCursor;
} while (cursor);
```

### JavaScript (Fetch) — Toggle one checkbox in the grid

```javascript
// The per-user endpoint is a wholesale PUT: rebuild the full set from the row.
async function toggle(row, eventKey, checked) {
  const events = checked
    ? [...row.subscribed, eventKey]
    : row.subscribed.filter((k) => k !== eventKey);

  const res = await fetch(`/users/${row.userId}/notification-subscriptions`, {
    method: 'PUT',
    headers: { ...auth, 'Content-Type': 'application/json' },
    body: JSON.stringify({ events }),
  });
  const { data } = await res.json();
  return data.subscribed; // authoritative, sorted
}
```

### JavaScript (Fetch) — Bulk apply with a destructive-mode guard

```javascript
async function bulkApply({ mode, events, roles = [], userIds = [] }) {
  if (mode === 'replace' && !confirm('Replace discards all other subscriptions. Continue?')) {
    return null;
  }
  const res = await fetch('/admin/notifications/subscriptions/bulk', {
    method: 'POST',
    headers: { ...auth, 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode, events, roles, userIds }),
  });
  const { data } = await res.json();
  // data.usersChanged < data.usersMatched  → some were already in this state
  // data.undeliverableTargets > 0          → warn: no email will reach them
  return data;
}
```

---

## Notes for Frontend

1. **Build the grid from the catalogue, not from constants.** Columns come from
   `GET /admin/notifications/events`; rows come from
   `GET /admin/notifications/subscriptions`. A hardcoded event list will silently rot when
   a ninth event ships, and posting a stale key returns `400`.
2. **Fetch the catalogue once per session.** It does not change at runtime. The matrix, by
   contrast, must be refetched after every write.
3. **Two write paths, deliberately.** Use `PUT /users/{id}/notification-subscriptions` for
   a single checkbox toggle, and the bulk endpoint for column/row-header actions
   ("subscribe all of Finance", "clear this event for everyone"). Do not emulate bulk with
   a loop of per-user PUTs — it is not atomic and will partially apply on failure.
4. **Per-user PUT is wholesale.** Always send the complete set derived from the row's
   `subscribed` array. Sending only the changed key unsubscribes everything else.
5. **Trust the response, not local state.** Both write paths return the authoritative
   result. Re-render from it rather than optimistically mutating, so a rejected key or a
   concurrent admin edit cannot desync the grid.
6. **Surface undeliverability prominently.** Render rows with `deliverable: false` in a
   warning style with a tooltip from `notDeliverableReason` (`no_email` → "No email
   address on file — this user will not receive notifications"). A checked box on an
   undeliverable user is a configuration trap, and the backend gives no other signal.
7. **Flag orphaned events.** A catalogue entry with `deliverableSubscribers: 0` means that
   notification currently goes nowhere at all. Show it as an error state at the top of the
   screen — this is the single most useful thing this screen can tell an admin.
8. **Add a "needs attention" filter.** `?deliverable=false` gives the fix-me worklist
   directly; pair it with the zero-subscriber events for an admin health panel.
9. **Confirm `replace`.** `add` and `remove` are additive and safe to apply optimistically;
   `replace` discards subscriptions not in the payload and needs an explicit confirm
   dialog naming how many users are affected.
10. **Reconcile `usersChanged` vs `usersMatched`.** A bulk call reporting
    `usersChanged: 0` is a successful no-op, not a failure — everyone was already in the
    requested state. Word the toast accordingly.
11. **Defaults are a template, not a rule.** The defaults editor must not imply it changes
    existing users. Label it "Applied to newly created users" and, where an admin likely
    wants a retrofit, offer a separate explicit "apply to existing <role> users now"
    action that calls the bulk endpoint.
12. **Role changes do not re-seed.** After `PATCH /users/{id}/role`, prompt the admin to
    review that user's subscriptions — the backend leaves them untouched.
13. **Ship email capture alongside this screen.** An email field on the user
    create/edit forms plus an inline "set address" action on undeliverable matrix rows.
    Without it the subscription grid is decorative: on an existing deployment every user
    starts with no address, so nothing is delivered no matter what is ticked.

---

## Changelog

- **2026-07-30** — Initial draft. New: `GET /admin/notifications/events`,
  `GET /admin/notifications/subscriptions`,
  `POST /admin/notifications/subscriptions/bulk`,
  `GET`/`PUT /admin/notifications/defaults`, and `PATCH /users/{id}/email`.
  `POST /users` gains an optional `email`; `UserProfile` now carries `email`.
  Existing `GET`/`PUT /users/{id}/notification-subscriptions` documented, and their
  `available[]` entries extended with an additive `description` field.
