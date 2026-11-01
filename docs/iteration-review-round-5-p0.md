# Review round 5, P0 — what a stranger's input may not do, 2026-11-01

**Status:** built, `./gradlew clean build --no-build-cache` green (Docker up, 2179 tests, 94 integration
suites, none skipped); PWA gate green from a clean `npm ci` (lint → `tsc -b` → `typecheck:tests` → vitest 1693 →
`vite build`), `npm run test:e2e` green (5) and `npm run test:e2e:offline` green (shell, journey and the new
shared-phone spec), both against a real backend. No UI changed, so there is no mobile layout to check.
**No migration.** PWA 1.68.2.
Source: the round 5 review, items B-114 to B-119 and P-73, P-74. Details of the findings live with the
review, outside this repository; this file records what changed and what must stay true.

## Backend

### B-114 — web push talks only to push services, and never for long
- `PushEndpointPolicy`: `https`, no userinfo, default port, host on an allow-list (FCM, Apple, Windows,
  Mozilla). Checked on subscribe (400 `PUSH_ENDPOINT_INVALID`) **and again at send**, because rows stored
  before the rule exist: such a row is dropped with a warning instead of being called.
- A send is bounded: `sendAsync` + `get(10 s)`, cancelled on timeout. The library's `send()` waits for
  ever, and a fixed pool can be emptied by a slow endpoint. `sendTimeoutMillis()` is the test seam.
- `sendToUser` is no longer `@Transactional`: it held a pooled connection across network calls.
- `MAX_PER_USER = 5` subscriptions, the oldest goes first.

### B-115 — a request body has a ceiling before anything parses it
`RequestSizeLimitFilter` (right after the forwarded-header filter): 64 KB on `/api/auth/` and
`/api/billing/webhook` whatever the content type, 2 MB on anything else that is not multipart (multipart
keeps Spring's limits). It refuses on `Content-Length` and also counts the stream, since a chunked body
has none. 413 `error.request.too-large`, with `Connection: close`. A too-large read surfacing through
Jackson reaches `handleUnreadable`, which walks the causes (`RequestBodyTooLargeException`) to the same
413. `RegisterRequest.customTrades` got its missing `@Size`.

### B-116 — one reader of the client address, one source for it
- `ClientIp.of(request)` is the only reader; the private copies in the login and register limiters, the
  auth controller and the five public controllers are gone.
- In prod `TrustedForwardedHeaderFilter` replaces Boot's filter and hides `Forwarded`, which the platform
  edge does not overwrite. It works by being a `ForwardedHeaderFilter` with the header removed from the
  request it sees; `X-Forwarded-*` handling is unchanged.
- **Trap:** Boot's `@ConditionalOnMissingFilterBean` matches the registration's exact generic. The bean must
  be declared `FilterRegistrationBean<ForwardedHeaderFilter>`. Declared with the subclass, Boot registers
  its own filter beside it and only their order decides the outcome; every behaviour test still passes.
  `ForwardedHeaderSpoofIntegrationTest.oursIsTheOnlyForwardedHeaderFilter` is what caught it.

### B-117 — what people typed is text in a letter
`ResendEmailService`: `esc` (`HtmlUtils.htmlEscape`) on every value interpolated into HTML, `oneLine` on
every value in a subject, and an attachment filename keeps letters, digits and `._-`. The templates are
package-private so `ResendEmailHtmlTest` can read them. A new template interpolates nothing raw.

### B-118 — the call that moves money runs outside a transaction
- `checkout` and `chargeAutoRenew` are no longer `@Transactional`. **tx 1** commits the PENDING row, the
  gateway call runs with no transaction and no pooled connection, **tx 2** attaches the invoice id
  (`attachInvoice`, `WHERE invoice_id IS NULL`).
- A webhook finds its row by `invoiceId`, else by `reference` (the payment's own id, only while the row has
  no invoice), so one that beats the charge's response is no longer lost.
- A checkout whose invoice call fails is closed FAILURE: no payment page reached anyone. A charge is
  different. A **4xx** moved no money and closes the row; a **timeout or 5xx is unknown**, so the row stays
  PENDING, and that is what `AutoRenewService`'s `inFlight` check stops on instead of charging again.
- `POST /api/billing/webhook`: the blanket `catch` is gone. 200 only for what was handled or is not ours to
  act on (bad signature, unknown invoice); a failure applying a genuine delivery is a 5xx, which is what
  makes monobank deliver again. The signature is checked before a transaction opens.
- `BillingReconcileJob` (hourly, `app.billing.reconcile-cron`): rows PENDING/PROCESSING older than 20 minutes
  and younger than 30 days are asked about (`MonobankClient.invoiceStatus`) and applied the way a webhook
  is, since monobank gives up after three deliveries and sends none for an expired invoice. An auto-renew
  row with **no invoice id** cannot be asked about: it is never closed by guessing, it keeps blocking
  another charge, its webhook still finds it by reference, and Sentry hears about it once, at 1 hour.

### B-119 — one door to POI
`WorkbookFactory.create` inflates and parses every part before the first row can be read, so the old row
cap protected nothing. `SafeWorkbook.read`: 5 MB file; **8 MB actually inflated**, counted with the same
zip reader POI uses before POI sees the file (a per-entry cap would not bound many entries); one parse at
a time, `error.import.busy` after 20 s. Both importers use it, and a CSV takes `requireSize`. The Dockerfile
gains `-XX:+ExitOnOutOfMemoryError`, so a JVM that does run out exits for the platform to restart instead
of limping on behind a passing healthcheck.

- **The 8 MB is a measured heap budget, not a file size.** With the workbook open, on a 512 MB heap: 6 MB of
  XML held 100 MB, 14 MB held 240 MB, so about 17 times. The first choice of 20 MB would have been about
  340 MB from a file of 2 MB, which together with `ExitOnOutOfMemoryError` is a restart anyone can trigger.
- **Not `java.util.zip`.** `ZipInputStream` refuses a Zip64 entry with a data descriptor, which is what POI's
  own streaming writer produces (`invalid entry size (expected 0 but got 1053 bytes)`). The scan uses
  `ZipArchiveInputStream`, the reader POI itself opens the file with.

## PWA

### P-73 — the outbox never crosses a session
- `runFlush` sends only `op.ownerId === tokens.ownerId()` and rereads the owner before each op, so a session
  change stops the pass. Nobody signed in: no flush, no timer, no 401, no forced redirect, no spent attempt.
- Another master's op is **left, not deleted**: destroying work stays a decision of the login. It is also
  left out of the sync counts, because a `runnable` that can never reach 0 shuts the focus refetch.
- A 401 is the new error kind `auth`: no attempt is spent and the pass stops. A 403 keeps its old meaning.
- `features/auth/startSession.ts` is the one door for login AND registration, which used to skip all of it:
  clear both caches, drop foreign ops **before the token is written**, prime `/me`, identity, then
  `kickOutboxSync` (`startOutboxSync` arms nothing while logged out). The wait on IndexedDB is bounded at
  2 s, because a wedged database must not hold a login, and the replay refuses foreign ops by itself.
- `e2e-offline/shared-phone.spec.ts` walks the whole thing in a production build against a real backend, and
  was checked to fail on the old code (a visitor on `/register` was thrown to `/login` by the replay).
- Every test that flushes now needs a session: `aSessionToken()` in `test/factories.ts`.

### P-74 — `pdfjs-dist` 6.2.108
The minimum fixed version, not the newest. The review also asked for `isEvalSupported: false`; **that option
no longer exists** in 6.2.108 (no reference in the build or the types, and the worker has no `new Function`),
so there is nothing to pass. Checked in real Chromium: a generated PDF's text comes back through the worker,
a corrupt one is refused in under half a second. Vitest cannot see this, since `import('pdfjs-dist')` throws
`DOMMatrix is not defined` under jsdom on either version.

**Worth knowing:** this is pdfjs's modern build, and it now touches the global `Iterator` when the module
loads. On a browser without it (Safari before 18.4) the import throws, which the import sheet already treats
as «no text layer»: the file is still listed and classified by its name. The legacy build is an open
follow-up, not part of this fix.

## Not changed
Nothing about signatures, estimates or acts. No schema change. `X-Forwarded-For` handling is as it was.
