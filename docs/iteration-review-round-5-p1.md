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
backend, and is not in CI's shell-only offline job.

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
