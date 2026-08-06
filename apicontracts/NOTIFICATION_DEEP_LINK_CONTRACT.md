# Notification Deep Link Contract

> **Status:** DRAFT
> **Last Updated:** 2026-08-05

Every notification email carries a **"View … in IBMS" button**. This document is the
contract for where that button points and what the web client must do when someone
clicks it.

Unlike the other contracts in this folder, almost nothing here is enforceable by the
backend — the backend's entire share is "emit a correct URL". Everything that makes the
click *work* (routing, authentication, the return-to journey, the error states) lives in
the client. That asymmetry is why this is written down rather than left implicit.

---

## The shape of the link

```
<WEB_CLIENT_URL><path>
```

`WEB_CLIENT_URL` is a backend config key: the **web client's** public base URL. It is
deliberately *not* `APP_URL`, which is this API's own origin — the backend refuses to
boot if the two are set to the same value.

The reason is worth stating once. Backend routes are mounted at the root with no `/api`
prefix, so `/topsheets/{id}` is simultaneously a JSON endpoint on the API and a page on
the client. Before this change the button was built from `APP_URL`, so clicking it in an
inbox sent a browser GET to the API with no `Authorization` header and returned
`401 {"error": …}`. Emails are permanent; that link is still wrong in every inbox that
holds one.

### Paths by event

Source of truth is `src/domain/model/DeepLinks.kt`, pinned by `test/domain/DeepLinksSpec.kt`.

| Event key | Path | Notes |
|---|---|---|
| `store.created` | `/stores/{storeId}` | |
| `account.created` | `/accounts/{accountId}` | |
| `account.updated` (direct edit) | `/accounts/{accountId}?tab=activity` | Opens the activity log — the interest is *what changed* |
| `account.updated` (change request approved) | `/accounts/{accountId}/change-requests/{requestId}` | The diff view † |
| `account.transferred` | `/accounts/{accountId}?tab=activity` | `{accountId}` is the **destination** account |
| `account.deactivation_requested` | `/accounts/{accountId}` | Status is on the account screen itself |
| `account.terminated` | `/accounts/{accountId}` | System job; the email names no actor |
| `topsheet.compiled` | `/topsheets/{topsheetId}` | |
| `topsheet.released` | `/topsheets/{topsheetId}` | |

† Not the activity tab, for a concrete reason: the approval's activity row is recorded
against the **request** id, and the activity feed filters on entity id alone, so an
approved change request would never appear on the account's activity tab at all.

Ids are always UUIDs. Nothing else is interpolated into a path — no names, no invoice
numbers — so no segment needs percent-decoding.

### The link carries no credential

There is no token, no signature, and no session material in the URL. It grants nothing;
it only says *where*. This is a deliberate rejection of the magic-link pattern, for four
reasons:

1. Mail reaches users through **Exchange**, and Defender **Safe Links pre-clicks URLs**
   to scan them. A single-use token would be burned before the human saw it.
2. Email gets forwarded. IBMS moves money — a forwarded message must not be account
   access.
3. Tokens in URLs leak via `Referer`, browser history, and proxy logs.
4. It would route around the "a temporary password is not an authenticated session"
   invariant the auth design exists to protect.

---

## What the client must do

### 1. Routing mode — decide this before the first production email

**This is the highest-risk item in the whole feature.** The backend emits a plain path.
For that path to resolve, one of these must be true:

- **History routing** (preferred): the client routes on `location.pathname`, *and* the
  static host serving it has an SPA fallback rewrite (`try_files $uri /index.html`, or
  the Caddy `try_files` equivalent). Without the rewrite, a deep link is a 404 from the
  static host and the app never boots.
- **Hash routing**: set `WEB_CLIENT_URL` to include the `#` (e.g.
  `https://client.example/#`) so the emitted URL is `https://client.example/#/topsheets/x`.
  No host configuration needed, but be aware some mail clients truncate at `#`, and
  Safe Links rewriting interacts badly with fragments.

Whichever is chosen, record it here and in the deployment's `WEB_CLIENT_URL`.

### 2. Try to refresh before deciding the user is signed out

Access tokens live 60 minutes; refresh tokens live 30 days. A three-week-old email will
essentially always meet an expired access token. On a cold deep-link load the client must
attempt `POST /auth/refresh` with its stored refresh token **before** concluding the user
is unauthenticated — otherwise every stale click bounces to a login screen it did not
need to show.

Done properly, most clicks land on the page with no login at all. That, not a magic link,
is what makes this feel instant.

### 3. Login, then return

When there is genuinely no session:

1. Stash the target path **before** navigating to login.
2. Authenticate.
3. Pop the stash and navigate to it. Clear the stash whether or not the navigation
   succeeds.

Storage: use **`sessionStorage`**, not `localStorage`. On a shared machine a
`localStorage` stash would hand the previous user's target to the next one. Give it a
short TTL and ignore anything older.

**The forced-password-change path is two-legged and must not lose the target.**
`POST /auth/login` for a user holding a temporary password returns
`outcome: "password_change_required"` with `session: null` and a challenge token; real
tokens are minted only by `POST /auth/password/change`. If the stash lives only in memory,
a reload between those two legs drops the deep link. This is the case most likely to be
missed in testing, because it only happens to brand-new users — who are exactly the people
being onboarded by an email.

### 4. If the target is put in the URL, treat it as hostile

If the client encodes the pending target as a query parameter (`?returnTo=…`) rather than
keeping it in storage, it is an open-redirect vector. Accept it **only** when it:

- starts with a single `/`
- does **not** start with `//` or `/\`
- contains no `scheme:` and no `@`

and percent-encode it when writing it. Strip it from the URL once consumed so it does not
leak through `Referer` or browser history.

Correct percent-encoding is a hard requirement, not a nicety: Safe Links rewrites the
button to `safelinks.protection.outlook.com/?url=<encoded>`, and a sloppily-encoded
parameter does not survive the round trip.

### 5. Required states on arrival

| Situation | Client must show |
|---|---|
| No session / expired | Login, then return to the target (§3) |
| `403` from the API | "You don't have access to this record — contact your system administrator." Never a blank screen or a raw error |
| `404` from the API | "This record no longer exists." Topsheets get cancelled and emails outlive them |
| Signed in as someone else | Land normally, but make the signed-in identity visible — a forwarded email is the common cause |

A recipient cannot currently hit `403`: `GET /topsheets/{id}`, `GET /accounts/{id}` and
the change-request diff view all admit any authenticated role. That is a property of
today's route set, not a guarantee, and emails already sent will outlive any tightening
of it. Build the state now.

---

## Backend configuration

| Key | Example | Notes |
|---|---|---|
| `WEB_CLIENT_URL` | `https://ibms-client.example` | Required outside dev; must be `https://` in prod; must not be `localhost`; must not equal `APP_URL`. Dev default `http://localhost:8081` |
| `CORS_ALLOWED_HOSTS` | `ibms-client.example` | Bare `host[:port]`, **no scheme** — a scheme is rejected at boot. Should include `WEB_CLIENT_URL`'s host, or the page the email opens cannot call the API; a startup warning says so |

## Not in scope

- **Highlighting the exact activity row.** `?tab=activity` opens the log; it does not
  anchor to a specific entry. Doing so needs `ActivityRecorder.record` to return the new
  id and a `GET /activities/{id}` endpoint, neither of which exists.
- **Repairing emails already queued.** Bodies are rendered at enqueue time and stored in
  `email_log`. Rows queued before this shipped still carry the old href. The dispatcher
  drains every 60s so the backlog is normally empty.
