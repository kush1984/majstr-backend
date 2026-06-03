# Fix E — Email the estimate portal link to the client

- **Status:** ✅ Code complete — `./gradlew test` + app restart (V20) pending a
  local run (Gradle wasn't reachable from the agent's sandbox)
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** `V20__add_client_email`
- **Goal:** let a contractor send the client a portal link to an estimate by
  email, reusing the Resend transport from Fix D. **Backend only** — the PWA
  share UI / client-email field is a separate task.

## 1. Optional client email

- `Client.email` (String, nullable); `V20` adds the column. Validated as an
  email **only when present** (`@Email` on the optional `ClientRequest.email`)
  — a contractor often only has the phone, so it's never required.
- `ClientRequest` / `ClientResponse` gain `email`; `ClientService.create` and
  `update` store it (trimmed, blank → null).

## 2. Email the share link

`POST /api/estimates/{id}/share/send-email` (authenticated, own estimate):
- Same preconditions as `share`: plan feature `CLIENT_PORTAL` + verified
  contractor email (`requireSharable`); then flips `DRAFT → SENT`.
- Reuses the estimate's existing active share link if there is one
  (`findFirstByEstimateIdAndRevokedFalseOrderByCreatedAtDesc`), else creates
  one — same token/flow as `share`.
- If the project's client has no email → **400 with code
  `CLIENT_EMAIL_MISSING`** ("У клієнта не вказано email"). Checked **before**
  any state change, so a missing email never silently moves the estimate to
  SENT.
- Sends a Ukrainian email via `EmailService.sendEstimateShareEmail` (Resend,
  `@Async`, fail-soft): subject "Кошторис від {company/name}", body greets the
  client and links to the portal.
- Returns the `ShareLinkResponse` (the link that was emailed).

## Abuse protection

- Ownership: estimate loaded via `loadOwned` (foreign → 403); client read from
  that estimate's project only.
- `EstimateEmailRateLimiter` (Bucket4j) — **20 emails/hour per account**
  (`app.rate-limit.estimate-email.max-per-hour`); over → 429 + `Retry-After`.
  Process-local map (single-node limitation, tracked open question).
- Client email is validated by `@Email` on write.

## Not changed (confirmed)

- Money/estimate logic untouched. `share` behaviour unchanged (it now shares
  the `requireSharable` + `flipToSentIfDraft` helpers, same effect).
- Email transport reused from Fix D (no new provider wiring).

## Tests

- `ClientServiceTest` — optional email stored; the no-limit loop updated for
  the widened `ClientRequest`.
- `ShareLinkServiceTest` — `sendByEmail` emails the client + flips DRAFT→SENT;
  client without email → `ClientEmailMissingException`, nothing sent, status
  stays DRAFT.
- `EstimateEmailRateLimiterTest` — blocks past the hourly cap; accounts
  independent. `VerificationEmailRateLimiterTest` updated for the widened
  `RateLimitProperties`.
- The Resend HTTP call is mocked in unit tests; verify the real send live.

## Note / open question

Sending to **client** emails means arbitrary third-party recipients, so a
Resend-verified sending domain is now a hard requirement for production (in
sandbox, Resend only delivers to the account owner's own address). Tracked
under "Production email delivery" in `open-questions.md`. The Viber/Telegram
client channel stays a future item (SPEC §G2).
