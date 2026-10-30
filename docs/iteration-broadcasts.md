# Iteration — admin broadcasts («✉️ Розсилки»)

**Status:** shipped 2026-10-30; backend + PWA gates green, verified locally by the owner.
**Migration:** V180 `broadcasts`. **PWA:** 1.68.0 (only the copied request-DTO snapshot changed there).

## Goal

~155 masters registered, a handful use the app, two or three pay. Nothing nudged anyone who stalled.
The owner wanted a dropdown of «cases» in the admin, the selection visible before sending, his own
text with the master's name substituted, and one button to send it to everyone in the selection.

## What shipped

### Segments (`AudienceRules`, `BroadcastAudience`)

| Segment | Rule |
|---|---|
| `UNVERIFIED` | email not verified · registered 3+ days ago |
| `NO_OBJECT` | verified · no object · 3+ days |
| `NO_ESTIMATE` | verified · an object · no estimate (ADDENDUM doesn't count) · 3+ days |
| `EMPTY_ESTIMATE` | verified · estimates, but every one has < 5 lines · 3+ days |
| `INACTIVE` | verified · an estimate with 5+ lines · `last_active_at` (or `created_at`) older than 30 days |

- Checked in this order and the first match wins, so **a master is in at most one segment** — two sends
  never reach one person twice (owner decision 2026-10-30).
- Nobody outside `role = USER ∧ plan = FREE ∧ broadcast_opt_out_at IS NULL` is in any segment — no admins,
  no PRO/TEAM, no running trial (a trial is `plan = PRO`).
- The rules are Java, the facts are three aggregate queries for the whole audience
  (`findBroadcastAudience`, `ProjectRepository.countByOwnerIdIn`, `EstimateRepository.findEstimateFillByOwnerIdIn`).
- **No SQL from the browser.** The owner described «a query under each choice»; it is on the server. A new
  segment is a few lines in `AudienceRules` + `BroadcastSegment`.

### Sending (`BroadcastService`, `BroadcastDispatcher`)

- `POST /api/admin/broadcasts` takes segment, subject, body and the ticked `userIds`; the server recomputes
  the segment and sends to the INTERSECTION. One `broadcast` row + one `broadcast_recipient` per letter
  (PENDING, its own random unsubscribe token), then `@Async` dispatch after commit.
- Per letter: re-check opt-out (SKIPPED), for UNVERIFIED re-check verification (SKIPPED) and mint a fresh
  verify link for the button; render; send; save SENT/FAILED immediately. 600 ms between letters
  (`app.broadcast.send-interval-ms`, Resend's default limit is 2 req/s).
- `resumeUnfinished` on `ApplicationReadyEvent` continues any broadcast without `finished_at` — a deploy
  mid-send repeats at most the one letter in flight.
- `POST …/preview` renders for the segment's newest master; `POST …/test` sends to the admin's own address
  synchronously with «[ТЕСТ]» in the subject; `GET …` is the last 50 sends with per-status counts.

### The letter (`BroadcastLetter`, `ResendEmailService.sendBroadcast`)

- Plain text in, HTML out: everything escaped, blank line = paragraph, `**bold**`, `{{імʼя}}` (any apostrophe,
  or `{{name}}`); with no name «Вітаю, {{імʼя}}!» becomes «Вітаю!».
- The button (label per segment) and the unsubscribe footer are appended by the renderer — no text can omit them.
- From «Андрій з Майстра» at the `EMAIL_FROM` address, Reply-To `majstr.app@ukr.net`
  (`BROADCAST_FROM_NAME`, `BROADCAST_REPLY_TO`), plus a text part and RFC 8058 `List-Unsubscribe` headers.
- `sendBroadcast` is the one NON-fail-soft method on `EmailService`: it throws, because the caller records the outcome.

### Unsubscribe (`PublicUnsubscribeController`, `UnsubscribeService`)

- `GET /api/public/unsubscribe/{token}` shows one button and changes nothing — mail scanners and link previews
  open every link. `POST` opts out (idempotent), and is what Gmail's one-click sends.
- Only broadcasts honour `broadcast_opt_out_at`; verification, password and billing mail keep going.

### Admin UI

New sidebar item «✉️ Розсилки» (its own view, not on the dashboard): segment dropdown with live counts and
criteria → table of who is in it (checkbox per row; anyone who already got THIS segment's letter starts
unticked) → subject + text (draft kept per segment in localStorage; INACTIVE and EMPTY_ESTIMATE start from
the texts agreed in chat) → «Попередній перегляд» (sandboxed iframe) / «Тестовий лист собі» / «Надіслати N
листів» with a confirm → history that polls while a send is running.

## Tests

`AudienceRulesTest`, `BroadcastLetterTest`, `BroadcastDispatcherTest` (Mockito); `BroadcastIntegrationTest`
(real SQL per segment incl. ADDENDUM, admin/PRO/TEAM/opted-out excluded, send → row FAILED with the key blank,
GET does not unsubscribe, POST does over the real security chain, unknown token 404, admin API 401).
`application-test.yml` pins `app.email.resend-api-key: ""` so no test can mail anyone.

## Not changed / confirmed

- No scheduled/automatic drip — a one-off send from the admin, by decision. The tables would carry one.
- Push is not used for broadcasts.
- The privacy policy was not edited — whether it covers these letters is open (see open-questions).

## Gotchas

- The request records live in `dto/`, so `RequestContractSnapshotTest` picked them up; the snapshot was
  refreshed and copied to `majstr-pwa/src/api/contract/request-dtos.json` (mirrored pair).
- Set `EMAIL_FROM` on Railway to a verified-domain address before the first real send; the default
  `onboarding@resend.dev` only delivers to the Resend account owner.
