# Review round 5, P1 — a stalled dependency may not take the app with it, 2026-11-01

**Status:** built, `./gradlew clean build --no-build-cache` green (Docker up, 2253 tests, 97 integration suites, none
skipped); PWA gate green from a clean `npm ci` (lint → `tsc -b` → `typecheck:tests` → vitest 1709 → `vite build`),
`npm run test:e2e` green (5) and `npm run test:e2e:offline` green (4), both against a real backend started with plain
`./gradlew bootRun`.
**No migration.** PWA 1.68.3. No UI changed, so there is no mobile layout to check.
Source: the round 5 review, items B-120 to B-123 and P-75, P-76. The rest of its P1 list and the bot's B-124
are not in this round. Details of the findings live with the review, outside this repository; this file
records what changed and what must stay true.

## Backend

### B-120 — nothing dev-like happens unless asked
- `application.yml` no longer defaults the profile. `SPRING_PROFILES_ACTIVE` binds by itself.
- `ActiveProfileGuard` refuses to start unless the profile is `prod`, `dev` or `test`. It catches a misspelled
  value as well as a missing one, and it is a `BeanFactoryPostProcessor`, so it runs before Flyway can touch a
  database. Without it a lost variable would boot with localhost CORS, Swagger open, and a client IP read from
  a header the caller controls, because all of the hardening lives in `application-prod.yml`.
- `./gradlew bootRun` assumes `dev` (`build.gradle.kts`), so local runs and the e2e backend are unchanged. A
  packaged jar has no such default. An IDE run needs the variable; the message says which.
- `allow-dev-simulation` is `false` in the base file, and `application-dev.yml` alone turns it on.
- `DevDataSeeder` refuses a database that is not on localhost. It seeds known passwords.

### B-121 — nothing waits on storage while holding a pooled connection
Pattern: resolve and authorize in a short transaction, close it, do the slow part, then a second short
transaction for the write. `Transactions.read/write` is the helper, because self-invocation never reaches
`@Transactional`. `StorageOutsideTransactionIntegrationTest` holds it: a spied storage records whether a
transaction was active at every call.

| Path | Now |
|---|---|
| Photo and receipt files, owner and all portals | key read in a short transaction, file opened after it |
| Estimate and act PDFs, owner and all portals | `pdfJob` in the transaction, render outside it |
| `ProjectPhotoService.upload` | store first; the cap's `FOR UPDATE` and the row in one short transaction |
| `MessageLinkService.submit` | every file checked, then stored, then the message and rows |
| `ProfileService.uploadLogo`, `deleteLogo` | the row commits, then the old file goes |
| `AuthService.register`, `login` | DNS and bcrypt before the transaction |
| `MessageFileRetentionService.deleteWarnedFiles` | no transaction around the storage calls; rows go in one batch |

- **The trap of rendering detached:** `open-in-view` is off, so a lazy association first touched by the renderer
  is a `LazyInitializationException`. `PdfModel.loaded()` initializes what the renderers read, inside the
  transaction. A new read in a renderer must be covered there. The integration test above runs on real proxies.
- **A signed document is served as the file it stored** (`signed_document.pdf_key`), byte for byte what the
  client was sent, instead of being rendered again per anonymous GET. A signed estimate with chosen receipt photos
  still renders, since the stored file has none. A missing file falls back to rendering from the snapshot.
- **`PhotoFile` is an open stream.** Controllers answer `InputStreamResource`, written and closed on the request
  thread, so a slow client costs a socket and a small buffer rather than the photo on the heap. Not
  `StreamingResponseBody`: that moves the write to the MVC async executor. Internal readers call `readAll()`.
- **Pool:** `connection-timeout` 5 s (Tomcat allows 200 threads against ten connections, and 30 s only stacks the
  queue), `leak-detection-threshold` 20 s, `statement_timeout` 30 s through
  `hikari.data-source-properties.options`. **Flyway connects outside the pool** (`FlywayConnectionConfig`), so a
  long migration cannot be cancelled mid-deploy by that limit.
- **R2:** the S3 client gets a 30 s attempt and 60 s total ceiling. Unset, a stalled call was bounded only by the
  socket timeout times the SDK's retries.

**Deliberately left:**
- **A signature still does its storage work inside the transaction, under the row lock** (`SignedDocumentService`:
  logo copy, render, PDF upload). It has to be atomic with the signature, and moving it means redesigning a flow
  with legal weight and its own rollback rules (B-81, B-76). It happens once per signature and touches one row;
  the R2 ceiling above bounds it. This is a decision, not an oversight.
- `MessageFileService.open` (owner-only) still reads inside its transaction, because the same call stamps
  `last_opened_at`.

### B-122 — how much heavy work may run at once
- `HeavyWork`: two gates. **PDFs**, 4 permits, wait up to 10 s. **Imports**, 3 permits, no wait, because one can sit
  on a model call for minutes; `HeavyImportConfig` holds that gate around the handler, so `file.getBytes()` inside
  cannot run first. A full gate answers 503 `SERVER_BUSY` with `Retry-After`, and no Sentry event.
- **A signature does not pass through a gate.** `SignedDocumentService.freeze*` calls the renderer directly: a
  client's tap may not fail because somebody else is downloading.
- `ImageBounds`: reads headers only. JPEG up to 60 MP (OpenPDF embeds it undecoded, and a phone's 50 MP photo must
  pass), PNG up to 16 MP (OpenPDF decodes one with alpha in full). Refused at upload on all four image doors, and
  checked again at render for rows stored before it existed. A header it cannot read is not its verdict to give.
- An act PDF stops adding receipt photos at 32 MB and prints how many were left out. An estimate PDF stops at the
  same budget and logs it. This also applies at signing, so a frozen PDF can carry that line.

### B-123 — a pool per kind of background work
- `AsyncExecutorsConfig`: `mailExecutor` and `pushExecutor` (4 threads, queue 500, drained 20 s on shutdown),
  `broadcastExecutor` (1 thread, not drained: letters commit one by one and a broadcast resumes on startup). A full
  queue drops the task and reports it to Sentry, rather than throwing into a request.
- **`spring.task.execution.mode: force`.** Declaring any `Executor` makes Boot back off from its default, and a
  plain `@Async` would then fall back to an unbounded `SimpleAsyncTaskExecutor`. Still no `AsyncConfigurer`.
- `ConnectionAndExecutorSettingsIntegrationTest` requires every `@Async` to name an existing executor. **The bot
  branch's `TelegramBot.handleAsync` is a plain `@Async`, so it will fail this at merge: give it its own bounded
  pool then.** That is the point of the guard.
- `spring.task.scheduling.pool.size: 3`, so one stuck job does not delay billing behind it.

## PWA

### P-75 — nothing waits for `/refresh` for ever
`REFRESH_TIMEOUT_MS` 15 s, treated as transient, and `refreshInFlight` clears so the next request tries again.
`rawApi` has a 30 s default and registration 60 s, since it seeds a catalog. The five bearer `fetch` calls take
`fetchTimeoutSignal()`, 60 s (`AbortSignal.timeout` arrived in Safari 16; an older phone keeps today's behaviour).
`toAppError` reads that timeout as «no answer» instead of showing the DOMException's raw English.
`e2e-offline/refresh-stall.spec.ts` watches a real browser give up, keep the session and try again. It needs no
backend, and CI runs it beside the shell spec since the second part of this round.

### P-76 — only the backend can end a session
`refreshRefused`: 401, or 400 in the backend's own error shape. A 403 from a WAF, a 404 during a deploy, 408, 413,
429 or an edge's bare 400 keep the tokens. So does a **200 with no tokens in it**, which used to log the master out
and is somebody else's page, not a verdict on the token.

## Checked by hand
- The real jar with no profile, and with `production`: both refuse, naming the variable, with no pool started.
- `bootRun` with no variable: profile `dev`, Flyway on its own connection, seeder on the local database.
- `refresh-stall.spec.ts` with the timeout removed: fails waiting for the request to give up.
- Production answers 404 on `/v3/api-docs`, so it runs `prod` and the guard binds nothing there.

**Not measured:** real R2 latency, a real load on the pool, and OpenPDF's allocation for an oversized PNG (the guard
does not depend on that figure).

## Not changed
Nothing about money, signatures' content, estimates or acts. No schema change. `application-prod.yml` is
untouched: the review also asked for Hikari pool stats in the logs, which is one line there and is the owner's to add.

---

# Second part: the rest of P1, 2026-11-02

**Status:** built. Backend `clean build --no-build-cache` green (2309 tests, 100 integration suites, none skipped); PWA
lint, both type checks, vitest (1739), build, e2e 5 and offline e2e 8 green on a real backend. **Migration V181.** PWA 1.68.4.
B-125 to B-130, P-77 to P-81 and P-83. B-124 still waits for the bot.

## Backend

### B-125 — a ceiling on model calls
- **Global:** `AiFlowBudget`, 200 calls per flow per hour (`AI_HOURLY_CALLS_PER_FLOW`). Past it the flow fails closed
  with AI_UNAVAILABLE, which the PWA already answers with «enter it by hand», and Sentry hears once an hour. In memory,
  so a deploy resets it: a safety net, not an account.
- **Per account:** 60 an hour across the import endpoints and the title pass (`AI_IMPORT_MAX_PER_HOUR`), checked in
  `HeavyImportConfig` ahead of the gate, so a flood cannot hold a permit.
- **FREE needs a confirmed address** for dictation and receipt recognition (`AiAccess`), the rule the trial and the
  client PDF already had. **This is a product decision:** a new master who has not confirmed sees «Підтвердіть email»
  there. A per-account limit only means something when an account costs something to make.
- **Retries:** `AiHttp` retries only a failure to connect, a 429 or a 5xx, and only within 9 s. A read timeout may
  already have billed, and three of them held a Tomcat thread for 8.5 minutes.

### B-126 — sessions (V181)
- One **family** per login. A rotated token replayed within the window is allowed twice, for tabs racing on one wake-up;
  after that it is refused and nothing else happens. **Past the window it ends the whole family**: a thief who rotated
  first no longer keeps a session for 30 days. Someone whose reply was lost and who came back minutes later was being
  logged out there anyway.
- The revocation runs in `RefreshTokenFamilies`, its own transaction, because the refusal rolls `rotate` back.
- The token cannot be handed back as the review suggested, since only its hash is stored, so a replay mints a new child.
- Logout ends the family. Logout and a reset clear `rotated_at`, so neither leaves a grace window behind.
- Rotated rows stay 7 days: deleted, a replay is just an unknown token.
- `users.tokens_valid_after`: a reset ends access tokens at once. Whole seconds, like `iat`, so the login right after
  the reset passes.

### B-127, B-128 — limits
- `AbuseLimits` holds them by name, each throwing `TooManyRequestsException`. Test and dev profiles lift the IP-wide ones.
- **Login:** 20 failures per account per hour from anywhere, 100 attempts per IP per 15 minutes. Only a failure spends
  the account limit. An unknown address uses the address as its key and spends a dummy bcrypt, so neither the clock nor a
  429 says which addresses have accounts.
- **Forgot:** 5 per address per hour from anywhere, 20 per IP per hour. Keys fold «ſ» and «ı» the way the database does.
- `AnonymousRateLimitFilter`: 300 a minute per IP on refresh, reset, verify-email, logout, the webhook and `/api/files/**`.
- **Email change:** the MX check runs before the transaction and is cached for an hour, 3 changes a day, and the change
  takes `lockCanonicalEmail` like registration does.

### B-129, B-130 — errors and logs
- Wrong method 405 with `Allow`, a bad path variable or a missing parameter or part 400, wrong content type 415,
  `ResponseStatusException` its own status. **Only a signed-in caller's reach Sentry, as warnings:** a 415 on every upload
  is how a real bug was once found, and an anonymous one is a scanner. See the open question on a Sentry filter.
- `SafePath` masks the token in logs and the `endpoint` tag, with `SentryInitializer.scrub` as `beforeSend` behind it.
  The error body keeps the path: the caller already holds that token.
- Mail links are debug-only, so production's log no longer holds live reset links.

### P-78, server half — `IdempotentWriteFilter`
One filter over the eight writes that have no id of their own, rather than the same check in eight services. It runs
after Spring Security, because it needs the principal. One row per (user, key, path): new runs, done replays the stored
answer, still running answers 409 `REQUEST_IN_FLIGHT` with `Retry-After`, a failure is forgotten so the retry really runs,
and a row older than 5 minutes is taken over, since only a crash leaves one. Answers over 1 MB are not stored. Rows go
after a day, because they hold a master's data.

## PWA

- **P-77:** `maskTextSelector: '*'`, with `maskTextFn` reading only what sits in `.ph-unmask`, which is the navigation. A
  new screen is masked without anyone remembering to. Replays show layout and flow, not words. `.ph-mask` stays as a second
  layer. **No real recording has been watched:** see the open question.
- **P-78:** `sendOnce` keys the id on the content, so a retry repeats it with no change to the screen, and forgets it once
  answered, so doing the same thing on purpose is a new submission. A reload loses the map, so a retry after one is a new
  submission. Commits wait 30 s, uploads scale with size at 32 KB/s between 30 s and 180 s.
- **P-79:** the API origin in `connect-src` and `img-src`, `X-Frame-Options: DENY`, reports to Sentry (the address is
  filled in at build from `VITE_SENTRY_DSN`). Still report-only. `cspHeaders.test.ts` checks the inline script's hash
  against `index.html` and **fails after 2026-11-30 if the policy is still report-only**; `e2e-offline/csp.spec.ts`, also in
  CI, serves the production build under it, enforced. **To check before enforcing:** a PDF opened from a `blob:` URL under
  `object-src 'none'`, which that spec does not reach.
- **P-80:** logout drops the device's subscription using a token captured first, because the tokens are gone by the time an
  interceptor runs. Whose it was is remembered, so the same master gets it back at the next app open and nobody else does.
- **P-81:** a forced logout resets analytics and Sentry and drops the subscription before its redirect, within 2 s.
- **P-83:** `npm audit fix`, zod 4 and `@hookform/resolvers` 5 (the review had zod as a low-priority decision, but it was the
  last high advisory, and only `customTrades` needed changing). An `audit` CI job at high, on its own, so an advisory published
  overnight is a red mark to act on rather than a blocked fix. **zod 4 probes `new Function` while a schema is built, and the
  CSP reports that on every page**, which `csp.spec.ts` caught on its first run: `src/lib/zod.ts` sets `jitless`, the app
  imports `z` from there, and eslint refuses a direct import.

## Worth knowing
- **V181 and the bot:** `majster-bot` has its own V181. At the merge it must be renumbered: two V181 fail at once in tests,
  which is why this is V181 and not a later number that would pass CI and fail on production.
- `family_id` keeps its database default, so an instance of the previous version can still insert during the deploy overlap.
- `e2e/contractor-journey.spec.ts` timed out once in 15 local runs, waiting on `/catalog` for a button that page always
  renders, so the browser was somewhere else. Not reproduced, and the next run overwrote the screenshot. Unproven suspect: the
  `history.go(-1)` of the logo dialog closing, racing the spec's immediate `page.goto`. CI does not run this spec.
