# Open questions & deferred decisions

A living log of things we **noticed** but **chose not to do yet**. The
goal is that nothing important quietly disappears between iterations:
before each new step we skim this file and ask whether any item is in
scope for the work about to start.

Per-item shape:

```
### Short title
- **Status:** OPEN | IN_PROGRESS | DEFERRED | RESOLVED
- **Since:** step N (or date)
- **Context:** why this is a question
- **Notes / options:** thinking, links, paths considered
- **Resolution:** filled when closed
```

When you take an item, change its status to `IN_PROGRESS` and link the
commit / PR that resolves it. When you close it, set `RESOLVED` with a
one-line summary — keep the item in the file as a record.

---

## Architecture & operations

### Album takeoff has no entry point and no job runner to host it
- **Status:** OPEN
- **Since:** Catch-up review (2026-07-27)
- **Context:** `service/album/` is complete and tested — extractor, both calculators, both product
  flows (`SurfaceTakeoffService` / `ElectroTakeoffService`), a fixture harness over three real
  albums. **But no controller references either service, so the feature is unreachable from the
  product**, and `@Async` exists only for email/push. The services' own docs say a run is
  *minutes* of wall clock (several Opus passes) and must never sit on a request thread — which is
  true, and there is currently nowhere else to put it.
- **Notes / options:** Two things are needed, and the second is the real decision:
  1. An endpoint per flow (surfaces / electrical) on a project, plan-gated like the other
     recognition features.
  2. **Somewhere to run a minutes-long job**, with the master able to leave the screen and come
     back. Options: a DB-backed job row + `@Scheduled` poller (fits the current single-node,
     no-new-infra shape, and the cleanup/expiry services already establish the pattern); a
     `@Async` fire-and-forget with the result written to a table (simplest, but a restart loses
     the run with no trace); or a real queue (correct, new infrastructure, and multi-instance is
     already an open question below). Whichever wins, the run must be **resumable or at least
     visibly failed** — silently losing a paid multi-pass Opus run is the failure mode to avoid.
- **Related:** the electrical measurements UI is separately parked behind
  `ELECTRICAL_MEASUREMENTS_ENABLED=false`, so exposing the electrical flow is also a product
  decision, not only a plumbing one.

### Offline-first follow-ups (Phase 1 shipped; these are the deferred pieces)
- **Status:** IN_PROGRESS (2026-07-26) — the offline programme resumed. **O3 shipped** (see
  below and [iteration-offline-step1-nothing-is-lost.md](iteration-offline-step1-nothing-is-lost.md)):
  the outbox is owner-stamped and now survives a logout or a dead session, and the refresh
  rotation that was killing sessions got a grace window. Next, in order: the core-loop gaps
  found by reading the code rather than the docs — **estimate status/name/deposit and object
  status offline**, **deleting an object or estimate offline** (today these have no
  `networkMode: 'always'`, so they silently PAUSE and are lost on reload — and this strands a
  master who is over the FREE limit, since the gate tells them to delete something they cannot
  delete), then **adding catalog positions into an estimate offline** (item #4 below — reclassified
  as core, since it is how estimates are actually built), then **notes + economy expenses**
  (O4), then **photos** (O6).
- **Since:** Offline-first iteration (2026-07-22)
- **Context:** Offline authoring shipped for **clients / objects / estimates / line items /
  measurements / catalog / own templates** (outbox + client-UUID idempotent replay), plus
  read-offline (persisted query cache),
  a **prefetch** that downloads everything ahead ("Підготувати офлайн" + automatic background warming),
  Slice 3 sync UX (status indicator, over-limit **"PRO or delete"** gate, warn-before-logout), and the
  2026-07-22 prod fixes (no query/mutation may pause offline; cached data beats an error screen;
  online-only actions say «потрібен інтернет»). See
  [iteration-offline-first.md](iteration-offline-first.md). The following are still deferred:
- **Notes / options (each its own future chunk):**
  1. **Measurements offline.** ✅ **RESOLVED (2026-07-22).** `src/lib/measurementCalc.ts` mirrors the
     backend `MeasurementCalc` (all six types + `unitForType` + `recomputeTree` bucketed by unit);
     all six measurement mutations are offline-first via `offlineMutate` with optimistic tree edits;
     backend `addRoom`/`addItem` take `X-Entity-Uuid` (idempotent) and both deletes are idempotent
     no-ops. Tests pin the mirror against the backend's own cases. The remaining nuance: the two
     implementations must be changed together — a formula edit in Java has to be mirrored in TS.
  2. **Owner-tagged re-sync on re-login** — *now the highest-value remaining offline item: it is the
     only path left where a master's unsynced work is destroyed.* Today the outbox is wiped on **every** auth transition
     (logout / dead-session / login) — SAFE (no cross-account leak) but a master who logs out with
     unsynced work, or whose session dies, loses the queue. The agreed design was to **retain** the
     outbox tagged by owner and offer re-sync on the next login as the same user (feeding the same
     over-limit gate). Needs an ownerId stamp on ops + a login-time "you have N unsynced changes — sync?"
     prompt, and NOT wiping on dead-session.
  3. **LWW conflict UI ("моє / серверне").** Decision #1 was LWW + conflict surfacing. The current
     replay is plain LWW (last write wins) with no diff shown. Needs `updated_at`/version on **clients &
     objects** (estimate already has `@Version`) and a small chooser when the server changed under an
     offline edit. Rare for a solo master; low priority.
  4. **Catalog-add / batch item adds offline.** `addItemFromCatalog` / `addItemsFromCatalogBatch` stay
     online-only (they reference catalog items + run a server transaction). Manual add/update/remove IS
     offline. Wire these through the outbox if masters build estimates from the catalog offline.
  5. **Per-item blocked-op resolution.** `SyncReviewSheet` resolves blocked ops in bulk (retry all /
     delete all). Per-item keep/delete would be nicer if a mix of over-limit + other rejections occurs.
  6. **Photos offline** (progress + receipt photos) — a **blob outbox** + deferred multipart upload;
     the heaviest piece (binary storage in IndexedDB, dedup, upload-on-reconnect). Explicitly last.
  7. **Catalog + own templates offline.** ✅ **RESOLVED (2026-07-25, "O5").** Catalog create/update/
     delete and template rename / re-file / delete / add-position / remove-position all author offline
     through `offlineMutate` + `X-Entity-Uuid`; `CatalogService.create` checks the client id before its
     `(name,type,unit)` dedupe, `EstimateTemplateService.addItem` rejects an id from another template,
     and all deletes are idempotent no-ops. The genuinely server-side flows (starter set,
     add-new-from-library, save-as-template, apply-a-template, import-from-file) are disabled offline
     with a «потрібен інтернет» message rather than failing.
  8. **Offline e2e coverage.** ✅ **RESOLVED (2026-07-25, "O1").** `npm run test:e2e:offline` runs
     Playwright against a **production build** (the dev SW is disabled): a cold offline load renders
     the login page, a deep route renders the shell (verified to fail without the SW navigation
     fallback), and an offline authoring journey drains through the outbox on reconnect.
  9. **REMAINING WORK — the offline programme, in the order agreed with the user (2026-07-25).**
     Shipped so far: **O1** (offline e2e) and **O5** (catalog + templates). Left, each its own chunk:
     - **O2 — statuses & estimate fields offline (frontend-only, smallest).** Estimate `status` /
       `depositAmount` / `name` (`useUpdateEstimate`) and object `status` (`useUpdateProject` already
       partly wired — verify) still go straight to the network. All are plain field writes: route them
       through `offlineMutate` with an optimistic patch of the cached estimate/project, `deps` on the
       parent entity, `networkMode: 'always'`. **No backend change** — the endpoints are already
       idempotent updates. Watch out: `SIGNED` must stay unreachable from the client (the server
       rejects it), and the FREE/PRO gates are already client-side off the cached plan.
     - **O3 — SHIPPED 2026-07-26.** Owner-stamped ops; the outbox survives logout AND a dead
       session; `discardForeignOps` runs at login so another account can never replay it. Paired
       with the server-side rotation grace window that was causing the logouts in the first place.
       **One deviation from the design above:** the agreed «у вас N незбережених змін —
       синхронізувати?» modal was built as a non-blocking TOAST instead. Re-syncing a master's
       OWN work is what they already expect, so a modal asks a question with one sensible answer
       and adds friction at login; the toast still makes it visible, and anything the server
       refuses lands in the existing `SyncReviewSheet`. Say the word and it becomes a modal.
     - ~~**O3 (original note)**~~  See item 2
       above for the full design. Today `forceLogin` → `clearOutbox` destroys unsynced work when a
       session dies. Needs: an `ownerId` stamp on every op at enqueue time, NOT wiping on
       dead-session/logout, a login-time prompt («у вас N незбережених змін — синхронізувати?») that
       only offers ops whose `ownerId` matches the user who just logged in, and dropping ops belonging
       to a *different* owner at that moment (no cross-account leak). Feeds the existing over-limit gate.
     - **O4 — SHIPPED 2026-07-26.** Notes and economy expenses author offline (all six
       mutations), with `X-Entity-Uuid` idempotency and idempotent deletes on the backend. The
       expense LIST is patched optimistically but the **profit summary deliberately is not** —
       it mixes estimate income, deposits and a completed-object settlement rule owned by the
       server, and a locally re-derived figure could disagree with the real one; a wrong number
       about the master's own money is worse than a stale one. A test pins that.
       See [iteration-offline-step4-notes-expenses.md](iteration-offline-step4-notes-expenses.md).
     - **O6 — photos offline. BACKLOG** (user's call, 2026-07-26; previously "explicitly last").
       A **blob outbox** (binary in IndexedDB), deferred multipart upload on reconnect, dedup
       and a storage-quota story — by far the heaviest piece, and the only part of the daily
       loop still online-only. `PhotosSection` stays `useOnlineGuard`-disabled offline, which is
       honest rather than broken. Pick this up when photos become a real complaint.
     - **#4 — SHIPPED 2026-07-26** (offline step 3). Catalog single + batch adds now author
       offline, replaying through the FROM-CATALOG endpoint with client ids (the plain item add
       could not be reused: it validates `unitPrice >= 0.01`, while a catalog position may
       legally cost 0, so those lines would have queued and then been rejected on replay).
       Reclassified from "not scheduled" to core along the way — it is how estimates are
       actually built. See [iteration-offline-step3-catalog.md](iteration-offline-step3-catalog.md).
     Also still open from the list above and NOT scheduled: **#3** (LWW conflict UI),
     **#5** (per-item blocked-op resolution).

### Multi-instance support for in-memory state
- **Status:** OPEN
- **Since:** step 1 (login limiter), tightened in step 3 (portal limiter, lastActiveAt tracker)
- **Context:** `LoginRateLimitFilter`, `PublicPortalRateLimitFilter` and `LastActiveTracker` all keep state in process-local `ConcurrentHashMap`s. Single-node is fine. On a second pod, users could double their rate-limit budget by being load-balanced across nodes, and `lastActiveAt` would underreport.
- **Notes / options:** Backed by Redis (Bucket4j has a Redis backend, would also serve `lastActiveAt` as a TTL key).

### Background cleanup of expired refresh tokens
- **Status:** RESOLVED
- **Since:** step 1
- **Context:** `RefreshTokenRepository.deleteExpired` exists but nothing calls it. Table grows monotonically with revoked + expired rows.
- **Notes / options:** `@Scheduled` job, daily at quiet hour. Or piggy-back on user login. (Fix D added `email_verification_tokens`, which accumulates the same way — sweep both in one job.)
- **Resolution:** Fix G (refresh-token audit) — `TokenCleanupService.purgeDeadTokens` runs daily (`@Scheduled`, cron `${app.cleanup.tokens-cron:0 0 3 * * *}`, `@EnableScheduling` on the app). It sweeps refresh tokens that are expired **or** revoked (`deleteExpiredOrRevoked` — rotation leaves a revoked row per use) and expired `email_verification_tokens` in the same pass. Single-node; would need ShedLock on multiple instances (noted in code + the multi-instance open question).

### File storage migration to S3/R2
- **Status:** RESOLVED
- **Since:** step 3
- **Context:** `LocalStorageService` is the only `StorageService` impl. Production cloud deploys want object storage.
- **Notes / options:** Add `S3StorageService` behind `app.storage.kind` property. The interface should not change; if it does, refactor before adding the second impl.
- **Resolution:** S3/R2 iteration (docs/iteration-storage-r2.md) — `S3StorageService` (AWS SDK v2, sync client over `UrlConnectionHttpClient`) added alongside local. `StorageConfig` builds exactly one bean from `app.storage.kind` (`local`|`s3`, default local); neither impl is `@Service`-scanned. **`StorageService` interface unchanged** — no refactor needed. Keys are identical across backends (`logos/uuid.ext`), reads still stream through `FileController` so R2 needs no public-read. Creds via env (`R2_ENDPOINT`/`R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY`/`R2_BUCKET`). Tests: `S3StorageServiceTest` (mock client: store/open/contentType/delete), `StorageConfigTest` (switch picks the right impl).

### X-Forwarded-For trusted without proxy validation
- **Status:** RESOLVED
- **Since:** Fix I code review (2026-06-09)
- **Context:** The rate-limit filters key off the first `X-Forwarded-For` entry. With no trusted reverse proxy in front, a client can spoof that header and evade the per-IP limits.
- **Notes / options:** Deploy behind a trusted proxy and set `server.forward-headers-strategy` so the framework derives the client IP from the forwarded chain rather than a raw header.
- **Resolution:** Prod-profile iteration (docs/iteration-prod-profile.md) — `application-prod.yml` sets `server.forward-headers-strategy=framework`. Spring's `ForwardedHeaderFilter` applies the forwarded client IP to `getRemoteAddr()` and **strips** the `X-Forwarded-*` headers, so the filters' manual header read returns null and they fall back to the corrected `getRemoteAddr()` — the genuine client IP. Safe because prod is reachable only through Railway's proxy; dev stays on the default (`NONE`), so its direct `X-Forwarded-For` parsing is unchanged. Still per-pod (see multi-instance item).

### DB backup restore drill not yet performed
- **Status:** RESOLVED (2026-06-12) — first real backup
  (`majstr-db-2026-06-12-193550.sql.gz`) restored cleanly into a disposable
  `postgres:18` container: gzip intact, dump made by `pg_dump 18.4` against
  server 18.4, `psql -v ON_ERROR_STOP=1` exited 0, all 14 tables present, **all
  24 Flyway migrations `success` (0 failed)**, and data restored (2 users, 36
  catalog_items, 74 catalog_templates, projects/clients/estimates, 30
  refresh_tokens). The `docs/db-restore.md` procedure works as written. Repeat
  the drill periodically (especially after schema changes). Railway Pro + PITR
  remains a recommended complementary tier (SPEC §H).
- **Since:** DB-backup iteration (2026-06-12)
- **Context:** Daily backups run (`.github/workflows/db-backup.yml` →
  Cloudflare R2, 30-day rotation) with a restore procedure (`docs/db-restore.md`).
  A backup whose restore is untested can silently be unusable (wrong client
  version, truncated dump, role/ownership snags, missing extension) — so the
  procedure had to be proven once against a real artifact.
- **Note:** the PG 18 dump contains a `\restrict` directive, so restore needs a
  **psql client ≥ 18** — an older psql chokes on it. Captured in `docs/db-restore.md`.

### Landing prerender / SSR for full SEO indexation
- **Status:** RESOLVED (lightweight) — full SSG still optional
- **Since:** SEO iteration (2026-06-13)
- **Context:** The landing's `<head>` meta were already static; the **body text** was
  client-rendered (less reliable to index).
- **Resolution:** Instead of an SSG/prerender pipeline (risky in Vite + `vite-plugin-pwa`
  + React-Router), a **static first-paint shell** was placed inside `#root` in
  `index.html` (semantic `<h1>` + hero + feature list + trade keywords + CTA), which
  `createRoot` replaces on mount — crawlers/no-JS clients get real body copy, the same
  HTML is served to everyone (no cloaking). Added an `Organization` JSON-LD too.
  Verified: `dist/index.html` ships the `<h1>`/features; dev server renders the full
  React landing over it with no console errors. A true SSG remains possible later but
  is unnecessary for this weak channel.

### Audit log for sensitive actions
- **Status:** OPEN
- **Since:** step 4
- **Context:** Admin can change a user's plan via `/api/admin/users/{id}/plan` — nothing records who did it. Same for hypothetical future "suspend user", "delete user".
- **Notes / options:** Separate `audit_events` table with `actor_id`, `action`, `target_id`, `payload`, `created_at`. Write via interceptor or explicit service calls.
- **Update (admin catalog editor, 2026-07-01):** the new admin default-catalog /
  default-estimate-template CRUD (`AdminCatalogTemplateService` /
  `AdminEstimateTemplateService`) `log.info`s every mutation with the actor's email —
  a lightweight paper trail, but still logs, not a queryable `audit_events` table.
  The structured-audit want is unchanged; if it lands, fold these admin mutations
  (and the plan change) into it.

### Device / OS a master logs in from (admin)
- **Status:** RESOLVED
- **Since:** 2026-07-10
- **Context:** We never captured which device masters use. The only device signal today
  is `push_subscriptions.user_agent` (push opt-ins only — biased). Product wants to know
  phone vs desktop + OS to steer mobile-first decisions.
- **Notes / options:** Parse the `User-Agent` at token-issue time (login/register/refresh)
  into `deviceType` (MOBILE/TABLET/DESKTOP/UNKNOWN) + `os`; store the LAST one on `users`
  (refresh keeps it current for active masters). Browser deliberately NOT tracked (not
  useful). Surface in `AdminUserSummary`/`AdminUserDetail`. Adjacent: the "Audit log for
  sensitive actions" item (a fuller `login_events` history would subsume this) and the
  privacy policy "technical data" line (mention device type at the next policy review).
- **Resolution:** Login-device iteration ([iteration-login-device.md](iteration-login-device.md)) —
  `DeviceInfo.parse` + `LastActiveTracker` store `users.last_device_type` / `last_os` (V44), surfaced on
  `AdminUserSummary` / `AdminUserDetail`. The backend admin page now **renders** it: the user-detail modal
  shows a "Пристрій" line (📱 Телефон / Планшет / 💻 Комп'ютер + OS) next to last activity
  (`static/admin/index.html`). The PWA has no admin UI by design; the fields ride on the admin JSON.

### Admin metrics by trade after the multi-trade move
- **Status:** OPEN
- **Since:** Fix A (2026-05-30)
- **Context:** `User.trade` (single) became `User.trades` (a value set in `user_trades`). Any future admin metric that buckets users by trade now double-counts — a GENERAL+ELECTRICAL contractor lands in two buckets, so a "distribution by trade" would sum to more than 100% of users. Nothing is broken today: `MetricsService` has no per-trade breakdown, and `AdminUserSummary` just lists each user's trades.
- **Notes / options:** When a per-trade chart is added, decide the semantics up front — count distinct users (a user with N trades adds 1 to each bucket; bucket sum exceeds the user count by design) vs. report "trade mentions" explicitly. Document the choice on the endpoint.

### Metric month boundary is UTC, not the contractor's local month
- **Status:** OPEN
- **Since:** Fix B (2026-05-31)
- **Context:** `DashboardService` (and the admin `MetricsService`) compute "this month"/"today" as a calendar boundary in UTC. For a Kyiv-based contractor (UTC+2/+3) the dashboard's "completed this month" can differ from their local month for the first/last couple of hours of a month.
- **Notes / options:** Pick a single app timezone (e.g. `Europe/Kyiv`) for all reporting boundaries, or make it per-user once users span timezones. Low impact while single-region; revisit before launch.

### Production email delivery (Resend key + verified domain)
- **Status:** OPEN
- **Since:** Fix D (2026-06-02)
- **Context:** Email verification ships, but real sending needs `RESEND_API_KEY` (env) and — to email anyone other than the Resend account owner — a Resend-verified sending domain in `EMAIL_FROM`. In dev the key is blank, so emails are logged & skipped: the feature works end-to-end but no mail actually goes out.
- **Notes / options:** Sign up at Resend, add `RESEND_API_KEY`; for arbitrary recipients verify a domain (DNS records) and set `EMAIL_FROM=Majstr <noreply@domain>`. Until then only the account owner's own address receives mail (Resend sandbox via `onboarding@resend.dev`). Revisit before public launch and when wiring password reset + portal notifications (same transport). **Fix E sends estimate links to client emails (arbitrary third parties) — so a verified domain is a hard requirement for that feature to work at all in production.**

### PDF-download counter for the bypass metric
- **Status:** OPEN
- **Since:** Admin-activity iteration (2026-06-13)
- **Context:** The admin flags potential "PDF bypass" (a master uses the product
  but skips the portal) as **active + email-unverified** — exact today, because
  an unverified master can't share. But there's no direct counter for "generated
  / downloaded a PDF", so a *verified* master who only ever downloads PDFs (never
  shares) isn't caught.
- **Notes / options:** Add a lightweight counter — increment on
  `GET /api/estimates/{id}/pdf` (a column on `users` or estimate, or an events
  row). Then "has estimates + downloaded PDF + never shared" becomes a precise
  bypass signal. Low priority; the current proxy covers the common case.

### I/O inside @Transactional
- **Status:** OPEN
- **Since:** Fix I code review (2026-06-10)
- **Context:** Logo upload (storage write) and PDF generation run inside
  `@Transactional` methods, holding a DB connection from the Hikari pool (max 10)
  for the duration of the I/O. Fine at current traffic; a slow disk or big PDF
  under load could starve the pool.
- **Notes / options:** Move file I/O outside the transaction boundary (do the DB
  work first, then write the file), or make PDF rendering non-transactional —
  it only reads already-loaded data. **More pressing after the R2 work:** with
  `STORAGE_KIND=s3` the logo upload's `storage.store()` is a *network* round-trip
  to R2 held inside `ProfileService`'s `@Transactional`, tying up a Hikari
  connection for the upload's duration.

### Correlate limit-hit → upgrade click (funnel tie-in)
- **Status:** OPEN
- **Since:** Upgrade-intent iteration (2026-06-30)
- **Context:** The "Інтерес до PRO" block counts upgrade clicks by trigger, and the
  activation funnel counts masters, but the two aren't joined into a single
  "% of limit-hitters who then clicked upgrade" number. Deferred as ambiguous (which
  limit, over what window). The privacy policy now names anonymized usage analytics
  (the "technical data" section), keeping it consistent with this tracking.
- **Notes / options:** If wanted, join `upgrade_event` (type=CLICK, trigger=
  OBJECT_LIMIT/ESTIMATE_LIMIT, distinct user) against the over-limit cohort. Low
  priority — the raw by-trigger breakdown already answers "which ceiling drives it".

### MetricsService full table scans
- **Status:** OPEN
- **Since:** Fix I code review (2026-06-10)
- **Context:** Admin metrics call `userRepository.findAll()` (twice for churn).
  Fine for hundreds of users, not thousands.
- **Notes / options:** Replace with aggregate queries (`COUNT ... GROUP BY`)
  when the user table grows; admin-only endpoint so urgency is low.

### Catalog autocomplete ranking by usage frequency/recency
- **Status:** OPEN
- **Since:** Catalog-autocomplete iteration (2026-06-13)
- **Context:** `GET /api/catalog/search` ranks suggestions exact-prefix-first,
  then alphabetical. The prompt's ideal is "most-used / most-recent first", but
  `CatalogItem` tracks no usage stats, so frequency/recency ordering isn't
  possible yet.
- **Notes / options:** Add `use_count` / `last_used_at` to `CatalogItem`, bumped
  when an item is copied into an estimate (`addItemFromCatalog`); order search by
  those before the alphabetical fallback. Cheap, but a schema change + write on
  the hot add-item path — defer until the alphabetical/prefix ordering is shown
  to be insufficient in real use.

### Unread-question count performance on the project list
- **Status:** RESOLVED
- **Since:** Fix F (2026-06-04)
- **Context:** `GET /api/projects` returns an unread-question count per project (the card's 💬 indicator). A naive per-project count would be an N+1 over the project list.
- **Resolution:** Fix F — one grouped query `EstimateQuestionRepository.countUnreadByProjectIds` (a row per project that has unread, absent when zero) folded into the list, mirroring the latest-estimate-summary pattern; single-project views use the derived `countByEstimateProjectIdAndReadFalse`. Backed by a partial index `idx_estimate_questions_unread ON estimate_questions(estimate_id) WHERE is_read = FALSE` (V22). Revisit only at very large per-contractor question volumes.

---

## Security

### PWA query cache not partitioned by user (cross-account data bleed)
- **Status:** RESOLVED (2026-06-12) — `useLogin.onSuccess` now `qc.clear()`s the
  React Query cache before priming the new user (mirrors `useLogout`), so a login
  starts empty and no prior account's data can bleed across an account switch.
  Test `useLogin.test.tsx`; full PWA suite green. Per-user-scoped query keys
  remain optional future hardening, not required.
- **Since:** Fix J isolation audit (2026-06-12)
- **Context:** Reported as "master B sees master A's catalog." **The backend is
  correctly tenant-isolated** — `CatalogItem.owner` is a non-null FK, every read
  is owner-scoped (`findByOwnerId*`, `loadOwned` → `AccessDenied`), reset stamps
  the current owner, `CatalogTemplate` (shared) is separate from `CatalogItem`
  (per-user), and the JWT principal is always the authenticated user. A request
  with B's token returns B's data. The leak is the PWA's React Query cache:
  query keys (`['catalog','list',type]`, and likewise dashboard/projects/
  clients) are **not scoped to the user**, and `useLogin` does not `qc.clear()`
  (only `useLogout` does). Switching accounts without an explicit logout shows
  the previous user's warm cache (staleTime 30s) until a refetch.
- **Notes / options:** PWA fix — `useLogin.onSuccess` should `qc.clear()` before
  priming `ME_QUERY_KEY` (mirror `useLogout`), and/or include the authenticated
  user id in per-user query keys. Backend side: regression tests now lock the
  ownership guarantee (`CatalogServiceTest`, `CatalogTemplateServiceTest`).
  A future cookie/httpOnly auth migration wouldn't change this — it's a
  client-cache-partitioning concern.

### Localization scope: messages done, content documents still uk-only
- **Status:** OPEN
- **Since:** Localization iteration (2026-06-10)
- **Context:** All end-user *messages* (ErrorResponse bodies, filter 429s, push
  titles) now resolve through `MessageSource` (uk base + en bundle, served by
  `Accept-Language`). Three things stay hard-coded Ukrainian by design, as
  product-language *content* rather than messages: the generated estimate
  **PDF** (`EstimatePdfService` labels + "грн"), the **email HTML**
  (`ResendEmailService` templates), and the **vanilla portal page chrome**
  (`static/portal/index.html` button/section labels — only its error states
  were localized). Also: **jakarta-validation field errors** ("must be a
  well-formed email address") are still English — the PWA validates
  client-side with its own uk texts, so they rarely surface, but a direct API
  caller or the portal would see English.
- **Notes / options:** Revisit only if a second client-facing language is
  actually needed (e.g. EU market). Then: thread a locale through
  `EstimatePdfService`/`ResendEmailService`, externalize the portal strings,
  and add `{jakarta.validation.constraints.*.message}` keys to the bundle.
  Until there's a non-Ukrainian client, this is intentional, not a gap.

### Swagger / API docs exposed in all profiles
- **Status:** RESOLVED
- **Since:** Fix I code review (2026-06-09)
- **Context:** `springdoc` Swagger UI (`/swagger-ui.html`) and the OpenAPI doc (`/v3/api-docs`) are public in every profile — in production that hands anonymous users a full map of the API surface.
- **Notes / options:** Disable both in the prod profile (keep them in dev for convenience).
- **Resolution:** Prod-profile iteration (docs/iteration-prod-profile.md) — `application-prod.yml` sets `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false`, so both return 404 under `SPRING_PROFILES_ACTIVE=prod`. The whitelist entries in `SecurityConfig` are harmless (they just permit a 404). Dev/base are untouched, so Swagger stays available locally.

### JWT secret rotation strategy
- **Status:** OPEN
- **Since:** step 1
- **Context:** Today secret comes from env, no kid header, no key rollover. Rotating the secret invalidates every live access token at once. Acceptable for low traffic, painful at scale.
- **Notes / options:** Add `kid` claim, keep two keys in rotation, deprecate old after access TTL passes.

### Share-link tokens stored raw vs hashed
- **Status:** OPEN
- **Since:** step 3
- **Context:** `EstimateShareLink.token` stores the raw token so the contractor can re-copy the URL later. DB compromise reveals all live share URLs. Since the portal-multi-estimate iteration (2026-07-22) the same trade-off applies to `project_share_links.token`.
- **Notes / options:** Hash like refresh tokens; lose the "show URL again" feature, gain breach safety. Decide once we have real users.

### Refresh-token reuse detection (session-family revocation)
- **Status:** OPEN — but see the note below; the *first* half shipped 2026-07-26
- **Since:** Fix I code review (2026-06-10)
- **2026-07-26 — the other half of this item shipped first, deliberately.** This item's own
  note warned "needs care not to punish the PWA's legitimate single-flight races". That risk
  turned out to be a live production bug already: strict single-use rotation logged masters
  out whenever a rotation's RESPONSE was lost (bad signal) or two contexts raced — and the PWA
  then wiped their unsynced outbox. A **rotation grace window** (V69 `rotated_at`,
  `app.jwt.refresh-rotation-grace-seconds`, default 60s) now accepts a token replayed shortly
  after it was rotated. Logout is excluded from the grace. See
  [iteration-offline-step1-nothing-is-lost.md](iteration-offline-step1-nothing-is-lost.md).
  **What remains OPEN is the theft response:** presenting a token revoked *outside* the grace
  still just 401s instead of calling `revokeAllForUser`. Add that only on top of the grace —
  doing it without would have turned every lost packet into a full account logout.
- **Context:** Rotation revokes the old token on use, but presenting an
  *already-revoked* token (the classic stolen-token signal) just returns 401 —
  it doesn't revoke the user's other sessions. `revokeAllForUser` exists and is
  unused.
- **Notes / options:** On a revoked-token presentation, call `revokeAllForUser`
  (treat it as theft evidence). Cheap to add; needs care not to punish the
  PWA's legitimate single-flight races. Revisit before public launch.

### Multiple active share links per estimate
- **Status:** OPEN
- **Since:** Fix I code review (2026-06-10)
- **Context:** Every `POST /api/estimates/{id}/share` mints a new token; old
  ones stay valid until expiry. More live URLs than the contractor likely
  realizes.
- **Notes / options:** Either reuse the existing usable link (idempotent
  share), or revoke older links on re-share. Decide together with the
  raw-vs-hashed share-token question above.
- **Update (portal-multi-estimate iteration, 2026-07-22):** the PWA no longer mints
  per-estimate links at all — sharing goes through the **project portal link**, which IS
  idempotent (one live URL per object, reused on every publish). Legacy estimate links
  stay valid for already-sent URLs but stop multiplying. Candidate for RESOLVED once the
  iteration ships; the raw-vs-hashed question now covers `project_share_links` too.

### Public file serving needs auth once non-public assets exist
- **Status:** RESOLVED (audit batch B, 2026-07-26) — `FileController` is now locked to the
  `logos/` prefix, the only class of object that is public by design. Until then it resolved
  ANY key, so a private receipt photo was world-readable to anyone who learned it (the audit's
  M3); UUID unguessability was the only protection. The 404 is decided from the key alone, so
  timing can't reveal that a private object exists. Private assets keep their own authenticated
  / portal-token-gated endpoints. Signed URLs remain an option if a future asset class needs
  public-but-temporary access. See [iteration-audit-batch-b.md](iteration-audit-batch-b.md).
- **Since:** Fix I code review (2026-06-10)
- **Context:** `/api/files/**` is fully public. Today it only serves contractor
  logos, which are public by design (anonymous portal + PDF). The moment
  photo reports or other private uploads land, public serving becomes a leak.
- **Notes / options:** Signed URLs (time-limited) or authenticated streaming
  for non-logo assets; ties into the S3/R2 migration item.
- **In progress (consolidated/receipts/photos iteration, 2026-07-12):** the first private
  uploads (object photos, esp. receipt photos) land now. They deliberately do **not** go
  through `/api/files/**` — served via an **authenticated owner-only** endpoint
  (`GET /api/projects/{id}/photos/{photoId}/file`, `loadOwned`) and a **portal-token-gated**
  endpoint that only serves `SHARED` photos of the token's object. The storage key is never
  exposed to the client. `/api/files/**` stays public and logo-only. The broader "signed URLs
  for all private assets" idea remains open for future asset types.

### Email enumeration on register
- **Status:** OPEN
- **Since:** Fix I code review (2026-06-10)
- **Context:** Register returns 409 "email already registered" — confirms an
  account exists. Login is enumeration-safe; register inherently isn't unless
  the flow goes async ("check your inbox" for both outcomes).
- **Notes / options:** Async-confirmation register is a UX cost; the register
  rate limit (Fix I) already curbs bulk probing. Likely accept as-is for v1.

### Password reset flow
- **Status:** RESOLVED
- **Since:** step 1
- **Context:** No reset endpoint — lock yourself out, lose the account.
- **Notes / options:** Needs an email service first (see below). Email transport now exists (`EmailService` / Resend, Fix D) — unblocked; just add the reset endpoint + token + email template.
- **Resolution:** Password-reset iteration ([iteration-password-reset-plus.md](iteration-password-reset-plus.md)) — mirrors
  email verification. `PasswordResetToken` (V59, crypto-random, 45-min TTL, single-use `usedAt`). `POST /api/auth/forgot`
  is anti-enumeration (always neutral 200, IP+email rate-limited); `POST /api/auth/reset` validates the token
  (bad/expired/used → 400 `INVALID_OR_EXPIRED_TOKEN`), sets the BCrypt hash, consumes the token, and **revokes every
  refresh token** (`revokeAllForUser` — a reset logs out all sessions). Resend `sendPasswordResetEmail`; both routes
  public; PWA `/forgot-password` + `/reset-password?token=`. `PasswordResetServiceTest` covers it.

### Email verification on register
- **Status:** RESOLVED
- **Since:** step 1
- **Context:** Anyone can register with any email; no proof of ownership. Fine for closed beta, blocks real billing later (people use throwaway emails).
- **Notes / options:** Soft model (register works immediately; banner + only share-link creation gated behind a verified email) via Resend.
- **Resolution:** Fix D — `User.emailVerified` + `EmailVerificationToken`, Resend `EmailService`, verify/resend endpoints, soft 403 `EMAIL_NOT_VERIFIED` gate on share; existing users migrated verified (V19). Verified live end-to-end (12 checks). PWA banner/page is a separate frontend task.

### Privacy policy: lawyer review + law №8153 readiness
- **Status:** OPEN
- **Since:** Privacy-policy iteration (2026-06-30)
- **Context:** The published `/privacy` policy + consent texts (ЗГОДА A registration,
  ЗГОДА B client-data, portal note C) were written in-house, not vetted by a lawyer.
  Ukraine's draft data-protection law №8153 will tighten consent requirements (explicit
  checkbox — which we now have).
- **Notes / options:** Have a lawyer review the policy and consent wording before it
  carries real legal weight / before public launch. When №8153 takes effect, re-check
  the consent mechanics against it. The structure (explicit checkbox + stamps
  `consentedToPrivacyAt` / `acknowledgedClientDataAt`, controller/operator split) is
  already aligned; this is wording/coverage validation.

### English translation of the privacy policy texts
- **Status:** OPEN
- **Since:** Privacy-policy iteration (2026-06-30)
- **Context:** The `/privacy` page body is inline Ukrainian (product-language content,
  same pattern as the PDF/email/portal — see "Localization scope" above). The short
  consent UI strings are localized (uk+en), but the **policy document itself** has no
  English version.
- **Notes / options:** Translate the policy body when a non-Ukrainian audience is real
  (EU market). Ties into the broader "content documents still uk-only" item. Low
  priority until there's a non-uk user.

### Referral source in the privacy policy
- **Status:** OPEN
- **Since:** Referral-attribution iteration (2026-07-02)
- **Context:** `users.referral_source` now stores an anonymized first-touch attribution
  (DIRECT / a partner code). When the privacy policy is next revised/published, it should
  mention that a registration source is recorded (anonymized, for partner accounting) —
  same spirit as the existing "technical data / anonymized usage analytics" line added for
  the upgrade tracking.
- **Notes / options:** One sentence in the `/privacy` page (data collected → "джерело
  реєстрації, знеособлено, для партнерського обліку"). Fold into the lawyer-review pass
  (see "Privacy policy: lawyer review").

### Existing-user privacy consent (login modal)
- **Status:** RESOLVED
- **Since:** Privacy-policy iteration (2026-06-30)
- **Context:** Users who registered before the consent checkbox have
  `consented_to_privacy_at = NULL` (V32 is additive). Decided NOT to treat continued
  use as consent.
- **Resolution:** Privacy-policy iteration — `AppLayout` shows a one-time
  non-dismissable `PrivacyConsentModal` when `me.consentedToPrivacyAt == null`; agreeing
  calls `POST /api/profile/consent` and stamps it. New users are stamped at register.

### Multi-factor auth / OAuth providers
- **Status:** DEFERRED
- **Since:** step 1
- **Context:** Not needed for v1; B2C contractor audience won't expect it. Revisit if first paying customer asks.

---

## Business logic

### Version 1.0.0 — ships with the first paying user
- **Status:** OPEN
- **Since:** 2026-07-23 (user decision)
- **Context:** The product is functionally 1.0-ready (real masters on prod, self-serve PRO
  checkout, portal signing, offline, imports), but the user chose a business milestone over a
  technical one for the symbolic bump.
- **Notes / options:** Stay on `0.x` until the FIRST real payment lands; the next release after
  that becomes `1.0.0` (then minor = feature iteration, patch = fixes — same rhythm as now).
  The open-questions skill carries the same rule so it isn't forgotten at bump time.

### Exact FREE limit numbers + monetization model
- **Status:** OPEN
- **Since:** FREE-limits iteration (2026-06-13)
- **Context:** FREE is now capped at 2 projects + 3 estimates per project
  (`PlanConfig`); PRO/TEAM unlimited. The numbers are a first guess to close the
  unlimited-drafts abuse hole, not validated demand-side. Too tight frustrates
  trial users; too loose leaks the paid value.
- **Notes / options:** Validate with real contractors during the closed test;
  the numbers live in one place (`PlanConfig`) so they're cheap to retune.
  Revisit alongside billing/trial (a trial could lift the caps for N days
  instead of a hard FREE wall). Tie-in: plan-downgrade-with-over-limit-data.

### FREE estimate cap: delete→create loophole (concurrent vs lifetime)
- **Status:** OPEN
- **Since:** 2026-07-03
- **Context:** `LimitService.requireCanAddEstimate` counts **concurrent** estimates
  (`countByProjectId`), so a FREE user can delete an estimate to free a slot and
  create another — unbounded *throughput* per object (though they can never *hold*
  >3, and the 2-object cap — the real monetization gate — is untouched). By design
  today; flagged as a possible bypass.
- **Notes / options:** Severity is low (churn is mostly self-harm — you lose the old
  estimate to make a new one; the object cap still gates paid value). Options if we
  close it: a **lifetime** `estimates_created` counter per object (never decremented)
  with a slightly higher cap (~5) so honest deletes don't hurt but infinite churn is
  blocked; or a total per-account estimate cap; or accept the concurrent semantics.
  **Decided for now:** don't block — instead **monitor** it (admin shows per-object
  estimates created/deleted, so we can see if anyone actually churns) and revisit
  with the FREE-limit-numbers tuning above.

### Billing integration
- **Status:** RESOLVED — self-serve one-time PRO checkout (phase 1) + tokenized
  auto-renew (phase 2, V40) both shipped via monobank Acquiring. Real recurring charge
  works; grace + soft-downgrade job in place. Remaining follow-ups split into their own
  items below (card-update flow, T-3 push, offer wording) and the `subscription_status`
  machine stays deferred (the `plan` + `plan_expires_at` + `auto_renew` fields cover
  current needs).
- **Since:** step 4
- **Context:** Plan change today is admin-only manual via `PATCH /api/admin/users/{id}/plan`. Real customers need self-serve checkout + recurring billing.
- **Notes / options:** WayForPay or Fondy for UA market; Stripe if going international. Webhook-driven plan changes flowing through the same admin endpoint internally.
- **In progress (billing iteration, 2026-07-02):** Provider chosen **monobank Acquiring**
  after a fee/recurring comparison (1.3% vs WayForPay 2% vs LiqPay 2.75%; audience all
  bank with mono; monobank has a full recurring API for phase 2). **Phase 1 shipped
  (backend):** self-serve PRO checkout → monobank hosted page → signature-verified webhook
  grants **PRO for 30 days** (`plan_expires_at`), renew by a fresh checkout; a daily job
  soft-downgrades to FREE after a grace window. Admin-manual plan change still works and
  sets no expiry. Stripe ruled out (no UA-merchant payouts). See
  [iteration-billing-monobank.md](iteration-billing-monobank.md). **Still open:** tokenized
  **auto-renew** (phase 2), the PWA wiring (checkout button + return page), and an explicit
  `subscription_status` machine (ACTIVE/GRACE/EXPIRED, SPEC G1) — deferred with auto-renew.

### Auto-renew: change the saved card without a fresh checkout
- **Status:** OPEN
- **Since:** Auto-renew iteration (2026-07-05)
- **Context:** V40 stores one `card_token` per user, captured on the opt-in checkout. To
  swap a card today the master would disable auto-renew and go through checkout again with
  the box ticked (the success webhook then captures the new token). There's no in-app
  "update card" that keeps the subscription running.
- **Notes / options:** If monobank exposes a token-replace / re-verify flow (a zero/low
  amount verification invoice that only refreshes the wallet token), wire an "update card"
  button in the profile that reuses the existing `walletId` + success-webhook capture path
  without a real charge. The failed-payment email already links to `/profile`; that link
  would point here. Confirm the monobank capability before building; otherwise the
  checkout-again path is an acceptable fallback.
- **Update (2026-07-06):** the sibling case — a PRO master who upgraded **without** opting
  into auto-renew and later wants to enable it — now routes through checkout (auto-renew
  pre-checked; pays the next period, saves the card) in the profile "Підписка" section
  (`enableAutoRenewNoCardHint`). The **zero-charge verification invoice** is the shared
  future improvement for both this and the card-swap case — same monobank-capability
  question.

### Auto-renew: push notification on T-3 in addition to email
- **Status:** OPEN
- **Since:** Auto-renew iteration (2026-07-05)
- **Context:** The T-3 renewal reminder is email-only (`sendRenewReminderEmail`). We
  already have a working Web Push channel (`PushService`, VAPID) used for sign/question
  events. Some masters may not watch email closely.
- **Notes / options:** Add a fail-soft `pushService.sendToUser` alongside the reminder
  email (same `renewReminderSentAt` dedup so it fires once per cycle), click-through to
  `/profile`. Cheap to add, reuses the existing push plumbing; deferred to keep the V40
  surface small. Consider only after we see whether email alone is enough.

### Auto-renew: recurring-charge clause in the public offer
- **Status:** OPEN
- **Since:** Auto-renew iteration (2026-07-05)
- **Context:** There is no public offer / terms document yet (privacy policy exists). Once
  real recurring charges run against saved cards, the offer must state the recurring nature
  (amount, cadence, that the card is charged automatically, how to cancel) — a legal and
  card-scheme requirement for merchant-initiated payments.
- **Notes / options:** When the offer is drafted, add an auto-renewal clause: 299 ₴/month,
  charged automatically until cancelled, one-tap cancel in the profile, T-3 reminder. Link
  it from the checkout modal near the auto-renew checkbox. Tied to the broader
  "public launch legal docs" work, not standalone.

### Partner rev-share money math
- **Status:** OPEN
- **Since:** Referral-attribution iteration (2026-07-02)
- **Context:** `referral_source` attribution ships (first-touch DIRECT/LIGA/…), and the
  admin by-source report shows **counts** (registered / activated / PRO interest). The
  **money** layer is deliberately out of code until billing rev-share is decided.
- **Notes / options:** Once paid subscriptions are tracked (billing phase 1 shipped —
  `payments` + `plan_expires_at`), add a report joining paid LIGA users → revenue → the
  partner's share. **Format is a business decision, kept out of code:** recurring % per
  month of paying referred users vs a one-off bounty per first payment; and time-bounded
  (e.g. 12 months per user) vs lifetime. Write "приведений" (first-touch via link OR code)
  into the partner agreement. Decide the % *after* the survey + PRO tracking show
  conversion, not blind.

### Promo-code bonus (trial / discount)
- **Status:** OPEN
- **Since:** Referral-attribution iteration (2026-07-02)
- **Context:** A community promo code (e.g. LIGA) currently only **sets the referral
  source** — it grants no benefit to the master. There's deliberately no bonus yet
  because there are no tariffs/trial to discount.
- **Notes / options:** When billing has tariffs/trial, a valid code could grant a longer
  trial or a discount (place is already carved out — `partners` is data). That also makes
  masters actually type the code, sharpening LIGA attribution. Revisit with the trial-period
  item.

### Object economy: PLAN-margin (my price vs client price) on estimate positions
- **Status:** OPEN
- **Since:** Object-economy iteration (2026-07-06)
- **Context:** v1 object economy is **fact** — real spend logged after the fact. The natural
  next step is **plan-margin**: a second (cost/my) price per estimate position alongside the
  client price, so the master sees the built-in margin *before* work starts.
- **Notes / options:** Add a cost price to `EstimateItem` (nullable). **Critical isolation:**
  the cost/second price must NEVER leak to the portal/PDF/share — same rule as economy (guard
  it in `PublicEstimateView`, extend `PublicEstimateIsolationTest`). Plan-margin vs the
  fact-based economy are two lenses on the same object; decide how they combine in the UI.
  Build after the fact-based economy proves used.

### Object economy: "actually received from client" (payments/prepayments) line
- **Status:** IN_PROGRESS
- **Since:** Object-economy iteration (2026-07-06)
- **Context:** Economy today is income (estimates) − expenses. A third line — **what the
  client actually paid** (prepayments / staged payments) — would show real cash flow, not
  just the contracted total.
- **Notes / options:** A `client_payments` journal per object (amount, date, note), mirroring
  `object_expenses`; economy then shows contracted vs received vs spent. Owner-only, same
  isolation. Defer until masters ask for cash-flow tracking.
- **Update (economy-rework, 2026-07-13):** cash-flow now IS shown — `received` = Σ deposits of
  the counted estimates, and the economy reports **cashBalance = received − spent** (NOT clamped;
  negative = master out of pocket) + **dueFromClient = contracted − received**. The remaining
  open part is a **multi-payment journal** (staged payments beyond the single `deposit_amount`):
  today the master edits the estimate's `depositAmount` to reflect total received so far. Build a
  `client_payments` ledger when masters need more than one payment line.

### Object economy: income double-counted across estimates
- **Status:** RESOLVED
- **Since:** Object-economy iteration (2026-07-06)
- **Context:** Income summed ALL of an object's estimates (minus REJECTED), so variants
  (econom/premium), a consolidated estimate + its sources, and working drafts were all
  counted — 2–3× the real deal.
- **Resolution:** Economy-rework (2026-07-13) — `estimates.count_in_economy` flag (V51). Income =
  Σ flagged estimates only. Auto: sign → on; consolidate → consolidated on + sources off; drafts
  off; owner toggle `PATCH …/count-in-economy`. See [iteration-object-economy-rework.md](iteration-object-economy-rework.md).

### Estimate: deposit → balance (завдаток / залишок)
- **Status:** IN_PROGRESS
- **Since:** Excel-example review (2026-07-10)
- **Context:** A real stretch-ceiling master's Excel estimate ends with Загальна вартість /
  Завдаток / Залишок. Majstr estimates show only the total — no prepayment or balance-due,
  which is how most trade deals actually run (deposit up front, balance on completion).
- **Notes / options:** v1 = a single nullable `deposit_amount` on `Estimate`; balance =
  `max(0, total − deposit)`, computed server-side. Shown on the estimate, the client
  **portal**, and the PDF (client-facing — deliberately NOT isolated). Editable while the
  estimate is editable (locked once SIGNED, like other fields). Distinct from the owner-side
  "actually received from client (prepayments)" journal above — that's a cash-flow ledger;
  this is one client-facing figure on the estimate. Open: whether the deposit later becomes
  the first entry of that payments journal.

### Object economy: profit rollup across all objects (dashboard)
- **Status:** OPEN
- **Since:** Object-economy iteration (2026-07-06)
- **Context:** Per-object profit ships; a master will want a **total** — earnings across all
  objects for a month/year on the dashboard.
- **Notes / options:** Aggregate income−expenses over the owner's objects by period (watch the
  UTC month-boundary item). PRO-gated like the per-object view. Build once per-object economy
  is validated.

### Object economy: import expenses from Excel
- **Status:** OPEN
- **Since:** Object-economy iteration (2026-07-06)
- **Context:** Symmetry with the catalog price-list import — bulk-import an object's expenses
  from a spreadsheet, for a master who tracked them in Excel.
- **Notes / options:** Reuse the import parser (POI is already on the classpath), targeting
  `object_expenses` (amount/category/date columns) with the same review screen. Build on request.

### Object economy: photo of a receipt attached to an expense
- **Status:** OPEN
- **Since:** Object-economy iteration (2026-07-06)
- **Context:** Attaching a receipt photo to an expense is a common bookkeeping want.
- **Notes / options:** Reuse `StorageService` (a `receipt_url` on `object_expense`), owner-only
  read like logos-but-private (ties into the "public file serving needs auth" open question).
  Build on request; not needed for the core profit view.
- **Update (2026-07-12):** the consolidated/receipts/photos iteration adds `project_photo`
  (owner-only private storage with an authenticated stream) and a **receipt** photo source
  linked to an estimate. That's a different flow (photo of a receipt whose LINES were parsed
  into an estimate, not an attachment on an `object_expense` row) — but if this want is built,
  it can reuse `project_photo`'s private-storage + auth-stream pattern (a `MANUAL`/expense
  variant or a `receipt_url` on `object_expense`).

### Master referral reward when the referrer is on admin-granted (dateless) PRO
- **Status:** OPEN
- **Since:** Master-referral iteration (2026-07-05)
- **Context:** The master→master reward grants the referrer 30 days PRO on the referred
  user's first payment. Three referrer states are handled: FREE → PRO+30d, PRO-with-date
  → +30d. But an **admin-granted PRO has no `plan_expires_at`** (never auto-downgraded) —
  stacking 30 days would *set* an expiry on an unlimited plan, which is wrong.
- **Notes / options:** For now the reward is **recorded in `referral_rewards`** (audit +
  "months earned" stat still count) but the **plan is not touched** — the referrer already
  has unlimited PRO, so there's nothing to extend. Open: what to actually give a dateless-PRO
  referrer instead — a credit ledger (banked days applied when/if they drop to a dated plan),
  a payout, or nothing. Decide when admin-PRO referrers actually occur (rare — mostly staff).

### Two-sided referral bonus (perk for the referred master too)
- **Status:** OPEN
- **Since:** Master-referral iteration (2026-07-05)
- **Context:** v1 rewards only the **referrer** (30 days PRO for the invitee's first payment).
  A two-sided incentive — the **referred** master also gets something (e.g. −50 ₴ on the
  first month, or a few bonus days) — typically converts better (the invitee has a reason to
  act, not just the inviter).
- **Notes / options:** Adds pricing complexity (a per-user first-purchase discount → the
  server-side amount is no longer a flat constant; needs a discount/coupon concept). Deferred
  to keep v1's "amounts are a server constant" invariant. Revisit with the promo-code-bonus
  and trial items — they'd share a discount mechanism. Decide by conversion data.

### Recurring monobank charges vs the half-year tariff
- **Status:** OPEN
- **Since:** Master-referral + half-year iteration (2026-07-05)
- **Context:** The half-year tariff (1494 ₴ / 6 mo) was framed as the anti-churn weapon
  *while* there's no true recurring billing. Tokenized auto-renew (V40) already recharges the
  saved card, and this iteration makes it **period-matched** (a 6-month subscription
  auto-renews for 6 months), so the recurring gap is largely covered for opted-in users.
- **Notes / options:** Still open for masters who buy without auto-renew: the half-year
  invoice buys 6 months of "forget to renew" safety but eventually lapses. A monobank
  subscription/recurring product (vs our merchant-initiated token charge) could remove the
  scheduled-job machinery entirely; low urgency now that period-matched auto-renew + half-year
  both exist. Ties into the auto-renew "card-update flow" and "offer clause" items.
- **Update (annual tariff, 2026-07-26):** a **YEAR** tariff (2748 ₴ = 229 ₴/mo, V65) now sits
  above the half-year one and is period-matched by the same `AutoRenewService` path — so a
  no-auto-renew master can buy a full year of "forget to renew" safety. Urgency drops further;
  the open part is unchanged (a real monobank subscription product vs our token charge). See
  [iteration-annual-tariff.md](iteration-annual-tariff.md). **Note for the offer-clause item:**
  the public offer must now state THREE recurring cadences (monthly / 6-month / annual), not two.

### Plan downgrade with over-limit data
- **Status:** OPEN
- **Since:** step 4
- **Context:** PRO user with 7 active projects downgrades to FREE (limit 2). What happens? Today: nothing — limit only enforced on CREATE. They can edit / view existing 7 projects but can't make new ones until they delete down to 2.
- **Notes / options:** Either current "soft enforcement" is fine (UX-friendly), or block writes to over-limit resources too. Pick before billing lands.

### Trial period for PRO/TEAM
- **Status:** RESOLVED — a one-time self-serve PRO trial shipped. `User.trialStartedAt` records it
  (null = never taken), `BillingService` refuses a second claim with 409 `TRIAL_UNAVAILABLE`, and
  the daily `BillingExpiryService` downgrades the account when it ends while `trialStartedAt`
  stays set so it can't be claimed twice. The **grace period was later removed deliberately**
  (2026-07-26) — see the iteration doc; a trial that quietly kept working past its end was
  teaching the wrong thing about the paywall.
- **Since:** step 4
- **Context:** No trial concept. New user is FREE forever until manual upgrade.
- **Notes / options:** Add `trial_ends_at` to user; `FeatureGuard` / `LimitService` reads it before checking plan.

### Team plan: actual multi-user workspaces
- **Status:** OPEN
- **Since:** step 4
- **Context:** `Plan.TEAM` exists in the enum but unlocks the same per-user features as PRO plus `AI_ASSISTANT`. No notion of a workspace shared between several users.
- **Notes / options:** Workspaces would need new entities (`Workspace`, `WorkspaceMember`) and ownership semantics on existing tables would shift from `owner_id (User)` to `workspace_id`. Big change; do not start until customers ask.

### Material metrics per object (concrete / brick / rebar totals)
- **Status:** OPEN
- **Since:** Builder-trade iteration (2026-06-13)
- **Context:** A builder wants to see "how much concrete / brick / rebar went into
  an object". Idea only — needs the master to clarify the exact want before building.
- **Notes / options:** Open questions to resolve with the master: per-**estimate**
  or per-**object** (all its estimates)? **plan** (what's in the estimate) or
  **actual** (what was really used)? does the system compute the need (tech cards)
  or does the master enter it? Likely simplest first cut: sum MATERIAL items across
  an object's estimates, grouped by name+unit (e.g. "Бетон — 14 м³") — the data
  already exists, no new entry. But confirm the concrete want first.

### Market-price updates for existing catalog items
- **Status:** OPEN
- **Since:** Default-catalog iteration (2026-06-22)
- **Context:** The default-catalog versioning ("Add new from library") only ever
  **adds new** items — it deliberately never touches the price or name of an item
  the master already owns (their data is sacred). But the default catalog also
  carries orientative market `suggested_price` hints, and Ukrainian prices drift
  fast. A master might want to know "the reference price for X moved 1200→1500 —
  update mine?" without us silently overwriting what they set.
- **Notes / options:** This is **opt-in, per-item, with a clear diff** — never a
  bulk overwrite. Possible shape: a future catalog version bumps a template's
  `suggested_price`; the master sees a "prices changed for N of your items" review
  list (old→new) and ticks which to accept. Needs a way to tell "master set this
  price deliberately" from "still on the default" (e.g. a `priceFromTemplate` flag
  or compare-to-template-at-sync). Ties into the bigger **material-price feed**
  idea in SPEC G (pulling live prices from the master's supplier). Confirm the
  want before building — many masters price by gut and won't want nagging.
- **Update (V31, 2026-06-28):** The *empty*-price gap in the **default** catalog
  is now closed — V31 filled all 355 previously-zero `suggested_price` hints
  (rabotniki.ua market rates, fuzzy-matched within trade, estimated by unit where
  no direct match), so a fresh master now sees a real hint on every default
  position. This is **only the default catalog**; the open part here is unchanged:
  syncing a *moved* price into a master's **already-owned** `catalog_items`
  (still never touched — their edits are sacred). See
  [iteration-catalog-enrichment.md](iteration-catalog-enrichment.md).
- **Update (admin catalog editor, 2026-07-01):** an admin can now *edit* a default
  catalog position's price/name from the panel (`AdminCatalogTemplateService.update`).
  Confirmed with the user: this deliberately keeps the sacred-data model — an edit
  reaches only NEW registrations; masters who already copied the item keep their
  copy. A newly *created* default does reach everyone (stamped at the next version →
  "Add new from library"). So the open part is now precisely: pushing an *edited*
  price into masters who **already own** the item — still the opt-in "prices changed,
  accept?" review, unbuilt. See [iteration-admin-catalog-editor.md](iteration-admin-catalog-editor.md).

### Estimate templates (typical work sets per object type)
- **Status:** IN_PROGRESS
- **Since:** Default-catalog iteration (2026-06-22) — flagged as "next stage"
- **Context:** The catalog is a flat library of individual positions. The next
  level up is a **template estimate**: a ready set of works/materials for a typical
  job ("bathroom renovation 4 m²", "studio electrical rough-in") that the master
  drops into a project and tweaks, instead of assembling line-by-line every time.
- **Notes / options:** Distinct from `CatalogTemplate` (single positions) — this is
  a *bundle* (ordered items + default quantities, possibly parametrised by area).
  Open: global defaults vs master's own saved templates vs both? Parametrise by
  m²/units or fixed? Likely a new `EstimateTemplate` + `EstimateTemplateItem`
  (mirrors Estimate/EstimateItem) and a "create estimate from template" action.
- **In progress:** Estimate-templates iteration (docs/iteration-estimate-templates.md).
  Decided for v1: BOTH default (88 system templates, `is_default=true`, `owner=null`)
  AND master-owned ("save current estimate as template"). Quantities stored **empty**
  (the master fills per object). Prices **not** stored — substituted from the
  master's own catalog by name match at apply-time (empty if no match). Single
  `trade` per template (nullable = general). The two sub-decisions below are
  carved out as their own open questions.
- **Update (V31, 2026-06-28):** Defaults expanded 88→**102** templates: every
  existing bundle grew to ~5 positions (was ~3.8), plus ~14 new bundles
  (venetian plaster, premium boiler room, suspended ceiling, parquet sanding,
  PVC-membrane roof, …). Master-owned templates are now editable position-by-
  position (add/remove), not just renamable. See
  [iteration-catalog-enrichment.md](iteration-catalog-enrichment.md).

### Typical (pre-filled) quantities in default estimate templates
- **Status:** OPEN
- **Since:** Estimate-templates iteration (2026-06-22)
- **Context:** Default templates ship with **empty** quantities — the master fills
  them per object (every job has a different m²/count). But a "typical" quantity
  (e.g. a standard 4 m² bathroom) could speed the common case, at the cost of
  masters who'd forget to correct a wrong pre-fill and send a bad estimate.
- **Notes / options:** Empty is the safe v1 (no wrong number ever leaves). If
  masters ask for pre-fills: add an optional `default_quantity` to template items,
  possibly parametrised by a per-template "area" input (quantity = area × factor).
  Revisit after real use — empty-first avoids the silent-wrong-number risk.

### Measurement → quantity calculator on estimate lines
- **Status:** IN_PROGRESS
- **Since:** Excel-example review (2026-07-10)
- **Context:** The same master's Excel auto-computes area from side lengths
  (5.31 × 3.69 → 19.59 m²) and multiplies by the m² rate. Majstr requires the master to
  pre-compute the quantity and type it in — but masters measure sides, not areas.
- **Notes / options:** v1 = **frontend-only** helper on the quantity field: area
  (д×ш → м²), length/perimeter (→ м.пог), minus openings (прорізи: ш×в×к-ть); the result is
  written into the existing `quantity` field. Dimensions are **not persisted** (empty-first,
  no silent-wrong-number — same discipline as template quantities above). If masters later
  want the breakdown stored/editable, add dimension fields to `EstimateItem` then. No backend
  change for v1.
- **Update (Object-measurements iteration, 2026-07-11):** the "stored/editable breakdown" want
  is now met at the **object** level — Заміри (`measurement_room`/`measurement_item`, V46) persist
  the entered dimensions (payload) and are substituted into line quantities via "Вибрати з
  замірів". This single-line calculator stays as the quick per-line helper (unchanged, надбудова).

### Object measurements: complex shapes (mansard / triangle / cut corner) in SURFACE
- **Status:** IN_PROGRESS
- **Since:** Object-measurements iteration (2026-07-11)
- **Context:** SURFACE is Σ(д×ш) − прорізи (like the single-line calculator). Rooms with a
  mansard, triangular gable, or cut corner need a shape calculator with figures.
- **Notes / options:** Add a figures calculator (rectangle/triangle/trapezoid with a formula
  hint) into the SURFACE editor when demand is confirmed — most jobs are "периметр × висота −
  проєми". The standalone figure calculator built earlier can be grafted in then.
  (Duplicate copies of this item and the three below were merged into one each on 2026-07-16.)
- **In progress (Surface-shapes iteration, 2026-07-16):** demand confirmed — taken up in
  [iteration-surface-shapes.md](iteration-surface-shapes.md). A SURFACE plane becomes
  `{shape, mode?, unit, values}` (rectangle / trapezoid / mansard ×2 modes / triangle ×2 modes /
  cut corner), each with an SVG diagram whose letters are the input fields; surface = Σ planes −
  Σ openings. Geometry is grafted from the standalone reference calculator
  (`C:\Work\prompts\area-calculator.jsx`) into a shared module used by BOTH the single-line
  calculator and the measurements SURFACE editor. Area via the **shoelace formula** over built
  vertices (no per-shape formulas) — ported to the backend too, since the server stays the source
  of truth for `result`. Legacy `{l, w}` planes read as rectangles (no migration).

### Object measurements: LIVE link (re-measure → prompt to update the estimate)
- **Status:** DEFERRED
- **Since:** Object-measurements iteration (2026-07-11)
- **Context:** v1 is **selection memory** only — a line stores which elements it summed
  (`measurement_refs`), but changing a measurement does NOT auto-update lines that used it.
- **Notes / options:** A live link (re-measured a room → banner "N lines use this — update?")
  would be convenient but risks silently changing signed/sent sums. Keep memory-only until asked;
  if built, gate it behind reopen/re-sign like every other edit to a SIGNED estimate.

### Object measurements: rooms as templates (typical bathroom)
- **Status:** OPEN
- **Since:** Object-measurements iteration (2026-07-11)
- **Context:** A master measures similar rooms repeatedly (a "typical bathroom": ceiling +
  walls + reveal). A room template would seed the elements to re-measure.
- **Notes / options:** A saved room template (element skeleton, empty dimensions — same
  empty-first discipline as estimate templates) dropped into an object. Build on feedback.

### Object measurements: mixing different units into one line — forbidden
- **Status:** RESOLVED (by design)
- **Since:** Object-measurements iteration (2026-07-11)
- **Context:** Could a line sum m² AND м.пог elements? No.
- **Resolution:** Deliberately disallowed — the "Вибрати з замірів" picker filters to the line's
  unit, and `MeasurementService.sumForRefs` rejects a unit mismatch (400 `unit-mismatch`). A line
  has one unit; mixing metres and square-metres into one quantity is meaningless.

### Surface shapes: real camera photo of the wall with dimension labels on top
- **Status:** OPEN
- **Since:** Surface-shapes iteration (2026-07-16)
- **Context:** The shapes iteration draws **schematic SVG diagrams** (letters = input fields) —
  deliberately NOT a camera photo. A master might instead want to snap the actual wall and label
  the measured sides on the picture.
- **Notes / options:** Different feature entirely — camera access, image storage, per-photo
  annotation overlay. Would reuse the private-photo plumbing (`project_photo`, authenticated
  stream) from the consolidated/receipts/photos iteration. Build only if masters ask; the drawn
  schema is what removes the "which side is which" confusion, and it costs no storage.

### Surface shapes: L-shaped / arbitrary contours via vertex entry
- **Status:** OPEN
- **Since:** Surface-shapes iteration (2026-07-16)
- **Context:** The five shapes cover the common cases. An L-shaped room or a bay window still has
  to be split into several planes by hand (which the "surface = Σ planes" model supports).
- **Notes / options:** The shoelace engine already computes ANY polygon from vertices, so an
  "enter the contour" mode is cheap on the math side — the cost is UI (a vertex editor is hard on
  a phone) and it fights SPEC §G2's "don't model complex geometry automatically". Splitting into
  simple planes stays the recommended path. Revisit on feedback.

### Surface shapes: circular / arched forms
- **Status:** OPEN
- **Since:** Surface-shapes iteration (2026-07-16)
- **Context:** Arches, round windows and semicircular niches aren't expressible — shoelace works
  on straight-edged polygons only.
- **Notes / options:** Either add dedicated formulas (circle/segment/arch = rectangle + half-
  ellipse) outside the shoelace path, or approximate the curve with many vertices (shoelace then
  works unchanged, error negligible at ~64 segments). Rare in finishing work; wait for a real ask.

### Estimate templates spanning multiple trades
- **Status:** OPEN
- **Since:** Estimate-templates iteration (2026-06-22)
- **Context:** v1 ties each template to a single `trade` (nullable = general) for
  the relevance filter — matches the 88 defaults, each grouped under one trade. A
  full-flat "квартира під ключ" template would legitimately span tiling + plumbing
  + electrical + painting, which a single-trade field can't express.
- **Notes / options:** Either a `Set<Trade>` on `EstimateTemplate` (filter shows it
  if ANY trade matches the master) or a dedicated `GENERAL`/multi tag. Cheap to
  migrate later (single → set). Defer until a real cross-trade default is authored;
  single-trade covers every current default.
- **Update (tetris templates, V50, 2026-07-13):** the 23 tetris section-templates ship with
  **best-guess trades** (per section) — the master will fine-tune them (e.g. ГІДРОІЗОЛЯЦІЯ,
  ЗВУКОІЗОЛЯЦІЯ are debatable) via the admin catalog/template editor, or a follow-up migration.
  A single-trade tag still covers all 23. Not blocking.

### Tetris default catalog: punctuation-stripped names + market-price gap
- **Status:** OPEN — **but only the PRICE half now.** The names/duplicates half was fixed by
  V70–V73 (2026-07-26): the stripped-punctuation rows were exactly why V50's
  "punctuation-insensitive" dedupe could not see what it was duplicating, so one work ended up
  sold under two names with big and small templates pointing at different rows. V71 collapses
  those groups (highest price wins, as V49 did), V72 breaks up the four categories that only
  repeated the trade name, and V73 carries both into catalogs masters already hold. Created
  estimates were deliberately untouched — line items are snapshots with no FK. Guarded by
  `SeedCatalogInvariantsIntegrationTest` + `CatalogCleanupOnLegacyDataIntegrationTest`.
  **What remains open:** whether the pre-existing positions ever get the master's own market
  prices, which is still the opt-in-diff question below, never a silent overwrite.
- **Since:** Tetris-templates iteration (2026-07-13)
- **Context:** The existing default catalog (V27) was seeded from the same tetris source with
  **punctuation stripped** from names, so V50 had to reference those canonical (uglier) names in
  templates for price resolution and add only the 201 net-new positions. The 355 pre-existing
  positions keep their V31 prices, not the master's tetris prices (data-sacred).
- **Notes / options:** If we later want the nicer-punctuated names or the master's exact prices on
  the pre-existing 355, that's the broader "market-price updates for existing catalog items"
  opt-in-diff work (see that item) — never a silent overwrite. Low priority; templates resolve and
  read fine today.

### Bulk-assign trade to the "Інше" (OTHER) catalog pile
- **Status:** OPEN
- **Since:** Catalog-trade-filter iteration (2026-06-23)
- **Context:** `catalog_items.trade` (V30) is backfilled best-effort by category —
  only where a category maps to exactly one trade in `catalog_templates`. Shared
  categories, renamed/manual items, and anything the V24-era backup didn't match land
  in **OTHER** ("Інше"). A master with many such items still scrolls past "Інше". New
  items (copied from templates / created with a chosen trade) are always tagged.
- **Update (V33, 2026-06-30):** the old NULL-untagged bucket was collapsed into the
  single OTHER catch-all (there were two "Інше" before — `Trade.OTHER` + null). So the
  pile is now the OTHER trade, not null; the bulk-assign want is unchanged — let a
  master move a batch of OTHER items to a real trade.
- **Notes / options:** Cheap manual fix already exists — edit the item and pick a
  trade. If the tail is large in practice: a one-shot "assign trade to these N items"
  bulk action (select untagged → set trade), or a smarter backfill (fuzzy category
  match / per-item template-name match). Defer until a real master reports a painful
  "Інше" pile; the per-item edit + always-tagged-new-items covers the common case.

### Price-list import from a photo / handwriting (vision-LLM)
- **Status:** OPEN
- **Since:** Catalog price-import iteration (2026-07-06)
- **Context:** The Excel/CSV/paste import (this iteration) lands on a review screen built to
  be **modality-agnostic**. The natural next modality is a photo of a paper price list or a
  handwritten notebook page → OCR/vision-LLM → the *same* review screen. Deliberately out of
  this step: it needs an external vision API (cost, rate limits, latency) and a "don't store
  the photo" policy, unlike the fully-local deterministic parser.
- **Notes / options:** When the Excel import shows real usage, add a `POST /parse` variant
  that takes an image, calls a vision model to extract `{name, unit, price}` rows, and
  returns the **same** `CatalogImportParseResponse` — so the whole review/commit funnel is
  reused. Env-gated + fail-soft like the other external integrations; the image is parsed and
  discarded (never persisted). Decide the provider/prompt then.
- **Update (2026-07-11):** the sibling **estimate** import (see "Import an ESTIMATE from a
  file") now builds exactly this vision-LLM machinery — `ClaudeExtractionService` (raw HTTP to
  Anthropic, Opus 4.8, base64 `image` block, structured JSON out). To add photo/handwriting
  import for the **catalog price list**, reuse that service with a `{name, unit, price}` schema
  and return the existing `CatalogImportParseResponse`. Provider/prompt are now decided.

### Import an ESTIMATE (not a price list) from a file
- **Status:** RESOLVED
- **Since:** Catalog price-import iteration (2026-07-06)
- **Context:** This iteration imports a master's **price list into the catalog**. A different
  ask is importing a whole **estimate** (positions + quantities for one object) from a file —
  e.g. a master already priced a job in Excel and wants it as a Majstr estimate.
- **Notes / options:** Reuses the parser but targets `Estimate`/`EstimateItem` (with a
  quantity column) instead of `CatalogItem`, and needs a target project. Build only if asked;
  the catalog import is the higher-leverage onboarding unlock.
- **Decision (2026-07-10):** Chosen approach = **LLM extraction (Claude / AI_ASSISTANT)** —
  the only path that parses masters' arbitrary real Excels (each format differs; a
  deterministic parser can't handle the 2D room+ops+totals layouts). Reuses the price-import
  review/commit screen, targets `Estimate`/`EstimateItem`, env-gated + fail-soft, PRO-gated,
  file parsed then discarded (never persisted). Scheduled **after** the deposit/balance +
  measurement-calculator iteration (quick wins first).
- **In progress (Estimate-import-LLM iteration, 2026-07-11):** see
  [iteration-estimate-import-llm.md](iteration-estimate-import-llm.md). Backend: raw-HTTP
  `ClaudeExtractionService` → Anthropic `/v1/messages` (**Opus 4.8**, `output_config.format`
  JSON schema, no beta headers), two input branches — **Excel/CSV** via POI → text grid,
  **photo** (printed + handwritten) via base64 `image` block (vision). Returns a
  **review payload** (no auto-commit); commit creates `Estimate`+`EstimateItem` on a chosen
  object **and** upserts positions into the master's catalog. Also extracts a nullable
  `depositAmount` (ties to the deposit/balance item). Gated by a **new `Feature.ESTIMATE_IMPORT`
  granted to PRO+TEAM** (NOT the TEAM-only `AI_ASSISTANT`). Env-gated on `ANTHROPIC_API_KEY`
  (blank → feature 503, not a silent no-op — the import is synchronous, the master waits on it);
  the uploaded file is parsed then discarded, never persisted. Two PWA entry points agreed
  (object-create "тип кошторису: З файлу/фото" + the "+ Новий" picker on a project); catalog
  name-conflicts resolved **on the review screen** (per-item, master decides). "Import-append
  into an already-open estimate" (editor entry point) deliberately deferred — see below.
- **Resolution:** Estimate-import-LLM iteration ([iteration-estimate-import-llm.md](iteration-estimate-import-llm.md))
  — shipped. `POST /api/estimates/import/parse|commit`, `ClaudeEstimateExtractor` (Anthropic raw HTTP, Opus 4.8,
  vision + `output_config.format` JSON schema), POI text-grid for Excel/CSV, base64 image for photos (printed +
  hand-written). Review screen (units normalized, **0 qty/price allowed** — a master may know the price before the
  count), commit creates the estimate on the object + upserts the ticked positions into the catalog (reuses
  `CatalogImportService.commit`). PRO-gated (`Feature.ESTIMATE_IMPORT`, PRO+TEAM). Two PWA entry points
  (object-create tile + project "+ Новий" picker). Follow-up: new `KM` unit (V45) + `м.кв.`→м² recognition. PWA
  green (tsc / 84 tests / build); backend build on the user.

### Import-append into an already-open estimate (editor entry point)
- **Status:** DEFERRED
- **Since:** Estimate-import-LLM iteration (2026-07-11)
- **Context:** The estimate import (above) always **creates a new** estimate from a file/photo.
  A third possible entry point is "Додати позиції з файлу" **inside an open estimate editor** —
  appending parsed rows into the current item list rather than creating a new estimate.
- **Notes / options:** Deferred from v1 — different semantics (merge into an existing list vs
  create), a heavier UX (dedup against current rows, unit/price reconciliation), and the
  signed-estimate immutability rule would have to gate it. The extraction backend is the same
  `ClaudeExtractionService`; only a new "append" commit path + editor UI would be needed.
  Revisit if masters ask to grow an existing estimate from a file.

### Add items from a receipt photo into an OPEN estimate (LLM)
- **Status:** RESOLVED
- **Since:** Consolidated/receipts/photos iteration (2026-07-12)
- **Context:** Narrower than the deferred "import-append into an already-open estimate" — masters
  wanted to photograph a store/terminal/handwritten **receipt** and have its lines added to the
  estimate they're editing, with sums recomputed. Prices from receipts are NOT added to the catalog.
- **Resolution:** `POST /api/estimates/{id}/receipt-items/parse|commit` — reuses
  `ClaudeEstimateExtractor` (vision) with a receipt-tuned system prompt; parse returns a review
  payload, commit appends the reviewed lines into the estimate (SIGNED → 409), no catalog upsert.
  New `Feature.RECEIPT_IMPORT` (PRO+TEAM); FREE sees the fab item → upgrade painted-door. The
  general "append parsed rows into an open estimate from Excel" case stays DEFERRED (below) — this
  resolves only the receipt-photo path. See
  [iteration-consolidated-receipts-photos.md](iteration-consolidated-receipts-photos.md).

### Recognise a room SKETCH photo into measurements (LLM vision)
- **Status:** RESOLVED
- **Since:** Sketch-import iteration (2026-07-16)
- **Context:** Masters already draw field sketches (кроки) of rooms with sizes on paper. Reading
  the sketch beats a photo of the room itself: the numbers are *written* (the LLM reads, doesn't
  guess scale) and the drawing gives topology. The danger isn't "can't read" but "reads a real
  number and attaches it to the wrong side" → a plausible area → a silent money error.
- **Resolution:** `POST /api/projects/{id}/measurements/sketch/parse|commit` — the **third** prompt
  on `ClaudeEstimateExtractor` (estimate + receipt were the first two), reusing its ONE Anthropic
  client via the new `requestJson(content, systemPrompt, schema)`. Parse maps the model's output
  into the SAME payload the manual editor uses and computes each `result` with `MeasurementCalc`
  (the model never calculates area, never invents an unreadable size — blank + low confidence). The
  **guard against the misassigned-number error** is the review screen: the sketch photo sits above
  OUR redrawn `ShapeDiagram` for each element, so the master compares two drawings at a glance; a
  low-confidence element is highlighted and blocks commit until fixed or removed. New
  `Feature.SKETCH_IMPORT` (PRO+TEAM). The image is discarded after parse; the master may optionally
  keep it as a PRIVATE object photo. See [iteration-sketch-import.md](iteration-sketch-import.md).

### Recognise ARCHITECTURAL drawings (PDF floor plans) into measurements
- **Status:** RESOLVED (project-import iteration, 2026-07-23)
- **Since:** Sketch-import iteration (2026-07-16)
- **Context:** A step beyond hand sketches — a real architect's PDF/printed floor plan (labelled room
  areas, wall runs). Harder: scale bars, axes, wall thickness, section heights on separate views.
- **Resolution:** shipped as the **project-documentation import** (docs/iteration-project-import.md):
  filename classification client-side, TEXT-LAYER extraction first (pdfbox — exact figures, no
  vision) with the native document block as the scan fallback, rooms created as a PACKAGE
  (підлога/стеля/стіни/плінтус/відкоси) with floors (`measurement_room.floor`, V63). No PDF→image
  rendering was ever needed — Anthropic renders PDFs natively. Follow-ups tracked below
  (sections/facades for heights, drawings-as-documents storage, 7z, wall accuracy).

### Project import: sections/facades (розрізи) for ceiling heights
- **Status:** OPEN
- **Since:** Project-import iteration (2026-07-23)
- **Context:** The real archive had NO absolute ceiling height (only relative drops «від нуля
  стелі»), so the import asks the master per floor. Heights DO live on section/facade sheets —
  recognising those could remove the question.
- **Notes / options:** A SECTIONS kind in the classifier («розріз», «фасад») + a prompt reading
  only absolute floor-to-ceiling figures. Low value until masters hit the height question often.

### Project import: store the drawings on the object as documents
- **Status:** OPEN
- **Since:** Project-import iteration (2026-07-23)
- **Context:** Files are parsed and discarded (policy). Masters may want the drawings kept on the
  object («зберегти креслення до обʼєкта») for later reference.
- **Notes / options:** PRIVATE `project_photo`-like storage or a new document type; needs caps and
  a viewer. Decide when asked for — storage cost and viewer complexity are non-trivial.

### Project import: 7z archives
- **Status:** RESOLVED (0.26.1 fix pass, 2026-07-23)
- **Since:** Project-import iteration (2026-07-23)
- **Context:** Client-side unzip is fflate (zip only). A 7z from a designer got a
  friendly «розпакуйте і надішліть zip» message — but designers send 7z as the norm.
- **Resolution:** 7z-wasm in the browser, imported LAZILY only when a .7z is dropped
  (~1.5 MB chunk), 100 MB input cap + the same entry filters as zip; extraction verified by a
  real in-test round-trip. Unpack failure falls back to the clear «розпакуйте і надішліть PDF
  або zip» message.

### Project import: auto floor detection for documents with no floor markers at all
- **Status:** OPEN
- **Since:** Project-import fixes (2026-07-23)
- **Context:** Floor now resolves room name → sheet stamp → filename. A document with NONE of
  those (an unnamed scan, rooms without floor words) lands in «Без поверху» and the master
  moves rooms by hand (the mass-move action helps, but it's still manual).
- **Notes / options:** Heuristics (room-number ranges per floor: 1–10 vs 11–20), or asking the
  LLM to infer the floor from context with low confidence, or simply a per-file «це який
  поверх?» question when nothing was detected. Wait for real frequency data.

### Project import: dimension chains with no room number to attach them to
- **Status:** OPEN
- **Since:** Project-import working version (2026-07-23)
- **Context:** Gabarits are only accepted when the CHECKSUM proves them against the table
  area. On a plan where a room's chains can't be tied to its number (dense drawing, chains
  shared between rooms), the model returns 0 and the master types a width instead.
- **Notes / options:** Could try a second pass asking only about the unresolved rooms with
  the expected area as a hint («which chain pair multiplies to 17,69?»), or leave it to the
  manual field. Measure how often it actually happens before adding a call.

### Project import: wall thickness (90/195/320 mm) from the plan
- **Status:** OPEN
- **Since:** Project-import working version (2026-07-23)
- **Context:** The prompt suggested extracting wall thickness for reveal DEPTH. Deliberately
  skipped: reveals are computed in running metres, so depth takes no part in any formula —
  an extra schema field would only add recognition noise.
- **Notes / options:** Revisit if reveals ever become m² (depth × run) or if a price
  depends on the depth.

### Project import: coverings spec → estimate as a bill of quantities
- **Status:** OPEN
- **Since:** Project-import working version (2026-07-23)
- **Context:** «Специфікація покриттів» (плитка 94,5 м², плінтус 60,4 м.п.) is now recognised
  but creates NOTHING — it isn't per-room geometry, so it doesn't belong in Заміри.
- **Notes / options:** Its natural home is the ESTIMATE: a bill of quantities that pre-fills
  material lines. Would reuse the estimate-import commit path. Wait until masters ask.

### Project import: wall accuracy — perimeter vs per-wall segments
- **Status:** RESOLVED (measurement editor v2, 0.29.0 + surface-takeoff-merge, 0.30.0)
- **Since:** Project-import iteration (2026-07-23)
- **Context:** Walls were computed as one perimeter × height − openings figure; real rooms have
  niches/ledges — a per-wall breakdown is more precise (and can name each wall).
- **Notes / options:** Keep per-segment data through review and create one SURFACE per wall on
  demand; or leave to the master's manual edit.
- **Resolution:** editor v2 ([iteration-measurement-editor-v2.md](iteration-measurement-editor-v2.md))
  replaced the single «Стіни» with **FOUR named walls** (Стіна 1…4), each its own editable
  width×height rect (gabarits seed 2×w, 2×l; else empty-to-measure). The 0.30.0 surface pass
  ([iteration-surface-takeoff-merge.md](iteration-surface-takeoff-merge.md)) added `toFloor`
  plinth interruption, a Підвіконня element, and shared interior doors deducted from both rooms.
  A room with more/fewer than 4 walls is handled by the master enabling/adding elements in the
  editor; a true per-segment contour is still out of scope (see «L-shaped / arbitrary contours»).

### Album takeoff pipeline (from the archived second-agent electro-feature)
- **Status:** RESOLVED (2026-07-27) — adopted and then **split into two products**. `service/album/`
  now holds `ClaudeAlbumExtractor`, `AlbumExtraction`, `AlbumSchemas`, `RoomSurfaceCalc`,
  `ElectroTakeoffCalc` and the 3 real-album fixtures from the archive, plus our own addition on
  top: `SurfaceTakeoffService` («площі», for painters/plasterers/tilers) and
  `ElectroTakeoffService` («електрика») run only their own LLM passes, so a master who needs
  areas never pays for electrical recognition and vice versa. Prompt caching means a second flow
  on the same album reads the document from cache. `AlbumFixtureHarnessTest` replays the fixtures
  through the calculators, so formulas are guarded without spending an LLM call. The
  whole-album auto-run and our cheap per-page pick now coexist rather than compete.
- **Since:** Archive review (2026-07-24)
- **Context:** A second agent delivered (in `C:\Users\AndriyKushka\Downloads\majstr.7z`) a whole
  server-side "design-album → takeoff" feature: `ClaudeAlbumExtractor` (5-pass Opus, Files API,
  prompt caching, structured outputs), `RoomSurfaceCalc` (площі) + `ElectroTakeoffCalc` (cable/
  chase/back-box BOM), `AlbumExtraction` model, JSON schemas, prompts, 34 tests + 3 real-album
  fixtures, and a validated methodology (`PROMPT-takeoff-electro.md`: coefficients, cable-journal
  algorithm, UA drawing conventions). It's the *whole album, one expensive auto-run* model —
  distinct from our cheap per-page pick + client-compute merge.
- **Notes / options:** We took only the площі-relevant refinements into our flow (0.30.0:
  `toFloor`, sills, shared doors, height conventions, honesty block — see
  [iteration-surface-takeoff-merge.md](iteration-surface-takeoff-merge.md)). Still on the table
  as a **separate big feature**: (a) an album auto-run mode (async job, merge multi-file PDFs,
  cross-checks) over our existing editor; (b) the **cable journal** deliverable (not in the Java
  `ElectroTakeoffCalc` yet) for electricians; (c) the `ElectroTakeoffCalc` coefficients/formulas as
  the base when the parked electrical measurements are unfrozen. Open product question: album
  auto-run as a PRO "dear auto mode" on top of the manual editor, or converge with the parked
  electrical flow. The methodology + prompts are the durable IP to keep even if the Java is rewritten.

### Code-quality audit backlog (from the archive)
- **Status:** OPEN
- **Since:** Archive review (2026-07-24)
- **Context:** The same archive included a professional audit of both repos (at `a405e55` /
  `7d4117c`, i.e. BEFORE the 0.24–0.30 work — some items may already be closed). Real findings
  worth triaging into fixes, not one-offs.
- **Notes / options:** Highest-signal: backend **H1/H2** (billing webhook check-then-act with no
  lock → duplicate webhook double-extends PRO; amount check no-ops on missing/non-numeric amount),
  **H3** (no connect/read timeouts on Claude/monobank/Resend clients — a hung upstream starves the
  Tomcat pool; note the archived `ClaudeAlbumExtractor` already sets timeouts), **M1** (password-
  reset + email-verification tokens stored raw — hash like refresh tokens), **M3** (private
  receipt/progress photos servable unauth via `/api/files/**` — restrict to `logos/`); PWA **H1/H2**
  (outbox op that exhausts MAX_ATTEMPTS becomes a permanent "syncing" phantom; dropping a blocked
  parent orphans queued children). Verify each against current HEAD before acting — the audit
  predates this session's work. Full text in the archive's `majstr-code-review/`.

### Paper LIST of measurements (columns of numbers, not a drawing)
- **Status:** OPEN
- **Since:** Sketch-import iteration (2026-07-16)
- **Context:** Some masters write measurements as a table (room / element / size), not a drawing.
  That's the receipt/estimate-import shape (rows of values), not the sketch shape (topology).
- **Notes / options:** The same `ClaudeEstimateExtractor` transport with a list-tuned prompt → the
  sketch review screen (or a simpler table review). Low effort once demand is shown; the sketch flow
  already handles the drawing case which is the harder, higher-value one.

### Sketch-import accuracy metric (edits per review)
- **Status:** OPEN
- **Since:** Sketch-import iteration (2026-07-16)
- **Context:** The feature earns its keep only if the master makes FEW corrections on the review
  screen. Many edits = it creates work instead of saving it — the metric of whether it's worth it.
- **Notes / options:** Instrument the review → commit: count fields edited / elements deleted / a
  wrong unit switched, per parse. Feed it back into the prompt. Deferred until the feature has real
  usage to measure.

### Export the catalog back to xlsx
- **Status:** OPEN
- **Since:** Catalog price-import iteration (2026-07-06)
- **Context:** Symmetry with import — let a master export their catalog to .xlsx (backup, or
  editing in Excel then re-importing).
- **Notes / options:** POI is now on the classpath, so a `GET /api/catalog/export` streaming
  an xlsx is cheap. Build on request — no demonstrated need yet.

### Email notifications
- **Status:** RESOLVED
- **Since:** step 3
- **Context:** Client signs an estimate or asks a question via portal — contractor learns about it only by refreshing the API.
- **Notes / options:** Need an email transport (Postmark, Resend, SES). Once it exists, wire it into `PublicEstimateService.sign` and `askQuestion`. Transport now exists (`EmailService` / Resend, Fix D) — unblocked; just add the notification calls + templates.
- **Resolution:** Крок 8 (web push) — instead of email, real-time browser push (VAPID / Web Push) notifies the contractor when a client signs an estimate or leaves a question. `PushService.sendToUser` is wired into `PublicEstimateService.sign` and `askQuestion`, fail-soft. An email channel for the same events remains a possible future addition, but the "contractor only learns by refreshing" gap is closed.

### Production web push (VAPID keys + iOS installed-PWA requirement)
- **Status:** OPEN
- **Since:** Крок 8 (2026-06-04)
- **Context:** Web push ships behind VAPID keys supplied via env (`VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`). In dev the keys may be blank — `PushService` then logs & skips, mirroring the email transport. For production a stable VAPID keypair must be generated once and kept (rotating it invalidates every existing browser subscription, forcing all clients to re-subscribe).
- **Notes / options:** Generate the keypair once (any web-push tool / the README snippet), store the private key as a secret, expose the public key via `GET /api/push/vapid-public-key`. iOS only delivers web push to a PWA **added to the Home Screen** (installed / standalone) on iOS 16.4+ — a plain Safari tab gets nothing; the frontend must detect this and hint the user to install. Also: subscriptions accumulate in `push_subscriptions`; dead ones are pruned lazily on 404/410 from the push service, but a periodic sweep could join the refresh-token / verification-token cleanup job.

### FREE gates the landing's headline features (measurements / recognition / economy)
- **Status:** OPEN
- **Since:** Landing-copy-v2 iteration (2026-07-16)
- **Context:** The landing's four headline benefits are measurements, recognition (sketch/receipt/
  estimate), the client portal, and object economy — **three of the four are PRO-only**
  (`PlanConfig` FREE = `CLIENT_PORTAL` + `ONLINE_SIGNATURE` + `PHOTO_REPORTS`, 2 projects). A master
  landing on "Заміряй об'єкт, склади кошторис…" meets a paywall on step one. For now the landing is
  **honest about it** (a `PRO` badge on those benefits + a free-tier micro-line) rather than papering
  over it.
- **Notes / options:** The open product question is whether **MEASUREMENTS should move to FREE** — it's
  the top-of-funnel hook and the thing the whole page leads with; gating it may be suppressing signup→
  activation. A one-line `PlanConfig` edit. Counter-argument: measuring pays off on big jobs (crews =
  PRO), and recognition/economy/logo already carry the paid value. Decide with real conversion data
  (the by-trigger upgrade breakdown already tracks which ceiling drives clicks). Offered to the user
  during the copy iteration and **declined for now** — honesty first, re-gate later if the data says so.

### Stale PlanConfig comment: "Only BRANDED_PDF and AI_ASSISTANT stay paid"
- **Status:** OPEN
- **Since:** Landing-copy-v2 iteration (2026-07-16)
- **Context:** `PlanConfig`'s FREE block comment states the plan is "capped on quantity, not features"
  and that "Only BRANDED_PDF and AI_ASSISTANT stay paid". That stopped being true once MEASUREMENTS,
  OBJECT_ECONOMY, ESTIMATE_IMPORT, RECEIPT_IMPORT and SKETCH_IMPORT landed as PRO — FREE is now capped
  on **both** quantity and features.
- **Notes / options:** A comment-only fix (no behaviour change), deliberately not made inside a copy
  iteration. Fold it into whichever iteration next touches `PlanConfig` — and re-word it to state the
  actual rule, since this comment is what a future reader will trust.

### Landing og:image is the app icon, not a promo image
- **Status:** OPEN
- **Since:** Landing-copy-v2 iteration (2026-07-16)
- **Context:** `og:image`/`twitter:image` point at `/icons/icon-512.png` — a plain square logo. Link
  previews (Viber/Telegram/Facebook, where masters actually share) would convert better with a real
  promo image (phone + estimate + the headline).
- **Notes / options:** Needs a designed 1200×630 asset, then swap the two meta tags and set
  `twitter:card` to `summary_large_image`. Content/design task, not code. Same batch as the onboarding
  deck refresh (it still shows neither measurements nor the LLM features).

### "Зміни / додаткові роботи" on an object (the freed «Зміни» tab idea)
- **Status:** OPEN
- **Since:** Project-notes iteration (2026-07-16)
- **Context:** The object screen had a placeholder «Зміни» tab from the original vision that **no
  master ever asked for**; the Notes iteration reused that tab SLOT for «Нотатки» (a real request).
  The *idea* behind «Зміни» is kept here, not discarded — it may resurface via SIGNED re-signing:
  recording **additional works** agreed mid-job as a separate change-order, without breaking the
  original signed deal.
- **Notes / options:** A change-order would likely be its own record (agreed extra items + price +
  a client acknowledgement), distinct from a note. Ties into the SIGNED-estimate reopen/re-sign flow
  and the "what changed" highlighting item. Build only when a master actually needs to formalise
  extras; until then a note ("+ вивіз сміття, 500 ₴, узгоджено 12.07") covers the informal case.

### Notes at the CLIENT level (not just per-object)
- **Status:** OPEN
- **Since:** Project-notes iteration (2026-07-16)
- **Context:** Object notes ship. A master may also want notes tied to a **client** (spanning that
  client's several objects) — "prefers calls after 18:00", "always pays in cash".
- **Notes / options:** Mirror `project_note` as `client_note` (owner-scoped via the client). Small,
  same pattern; build on feedback — per-object covers the on-site case first.

### Checklist / to-do notes
- **Status:** OPEN
- **Since:** Project-notes iteration (2026-07-16)
- **Context:** A note is free text today. A checklist variant (tickable items — "замовити плитку",
  "викликати електрика") is a natural extension for job prep.
- **Notes / options:** Either a note `kind` (TEXT | CHECKLIST) with a structured body, or a separate
  entity. Keep the plain note as the default; add checklists only if masters ask — empty-first, no
  premature structure (the whole point of notes is "write what you want").

### Share a single note with the client (like a shared photo)
- **Status:** OPEN
- **Since:** Project-notes iteration (2026-07-16)
- **Context:** Notes are PRIVATE by design (they may hold a subcontractor's phone or "client doesn't
  pick up"). But a master might want to share ONE specific note with the client on the portal — e.g.
  "access code 1234", "we start Monday 8:00".
- **Notes / options:** A per-note `SHARED` toggle (mirror the photo visibility model) surfacing
  shared notes on the portal. Requires care — the default must stay PRIVATE and the share must be
  explicit and per-note. Build only if asked; the privacy default is the safe v1.

---

## Features in the catalog enum but not implemented

### PHOTO_REPORTS
- **Status:** IN_PROGRESS
- **Since:** step 3
- **Context:** Enum value exists in `Feature` and grants to **all plans incl. FREE** (part of
  the "show the client the product" workflow). No code path used it — dead until now.
- **Notes / options:** Likely a per-project gallery of contractor-uploaded photos with timestamped notes; reuses `StorageService`.
- **In progress (consolidated/receipts/photos iteration, 2026-07-12):** revived as the object
  «Фото» tab — one `project_photo` table (V47), source RECEIPT|MANUAL, visibility PRIVATE|SHARED.
  Gated by `PHOTO_REPORTS` (routed through `FeatureGuard`, so all plans see it today; flip to PRO
  is a one-line matrix edit). MANUAL progress photos can be shared to the client via the portal
  token (SHARED); RECEIPT photos are always PRIVATE. See
  [iteration-consolidated-receipts-photos.md](iteration-consolidated-receipts-photos.md).
- **Follow-ups (2026-07-13):** per-object photo caps (FREE 5 / PRO 50 progress, receipts 50) +
  8 MB server cap + client downscale; a **fullscreen lightbox** (tap-to-view, prev/next, Esc) —
  functionally complete now. Deferred: **swipe** gesture in the lightbox (arrows/chevrons only for
  now); a future move to PRO-only would be a one-line `PlanConfig` edit.

### AI_ASSISTANT
- **Status:** OPEN
- **Since:** step 4
- **Context:** Only TEAM has it. No code path.
- **Notes / options:** "Draft estimate from project description" feels like the highest-value first cut. Anthropic Claude API integration; gated by `Feature.AI_ASSISTANT`.

---

## Testing & quality

### Register rate-limit conflicts with e2e (false 429 on repeated runs)
- **Status:** RESOLVED
- **Since:** Password-reset iteration (2026-07-17)
- **Context:** The register limiter (5/hour/IP, Fix I) gives repeated Playwright e2e runs a false 429 —
  many registrations from one IP look like abuse. Red tests you learn to ignore are dangerous.
- **Resolution:** The register (and the new `/forgot`) IP limits are lifted in `application-dev.yml`
  (max-attempts 100000/1min). The default profile is `dev` (`SPRING_PROFILES_ACTIVE:dev`), so this
  covers local dev AND the e2e backend; prod runs under the `prod` profile and inherits the real base
  caps (5/hour/IP) unchanged. Property-level merge, so other `app.*` config is untouched.

### Service worker update UX (silent reload can drop form input)
- **Status:** RESOLVED
- **Since:** Password-reset iteration (2026-07-17)
- **Context:** `registerSW`'s `onNeedRefresh` was a no-op with autoUpdate — a new build would swap in
  on the next navigation, potentially dropping unsaved form input (a 30-line estimate in progress).
- **Resolution:** `onNeedRefresh` now signals `lib/swUpdate.ts` (captures the returned `updateSW`); a
  React `<UpdateBanner>` at the app root shows a non-intrusive "нова версія — Оновити" banner. The
  reload happens only on the master's click (`updateSW(true)`) — never silently. `onOfflineReady` stays
  quiet. Web push untouched. `UpdateBanner.test` covers show + apply.

### Multi-sheet project PDFs: which page(s) to send for recognition
- **Status:** OPEN
- **Since:** Electrical-core iteration (2026-07-19)
- **Context:** Real input is a whole project set (Belgradska_1405.pdf — tens of pages: plans,
  sections, visualisations), not a single sheet. Sending the entire PDF to the model is
  expensive and risky: it may count symbols across the wrong sheet (mixing floors, or reading
  a furniture plan as an electrical one). The 4-sheet sample set was one plan per file, which
  hid this.
- **Notes / options:** Ask the master which page(s) (a page picker with thumbnails), or have a
  cheap first pass classify pages and propose the electrical ones, or accept a page range in
  the parse request. Until then the prompt must at least be told a set may contain several
  plans and to report which sheet it counted (a warning).
- **Update (2026-07-21):** the **page-range** option was built — `pdf-lib` reads the page count
  client-side and a multi-page PDF prompts for pages («3» / «3-4» / «1,3,5»), extracting only those
  before upload, so the model never counts across the wrong sheet. Closes the common case; a
  thumbnail picker / auto-classify is a later nicety. Note: this rides on the electrical feature,
  which is currently **UI-disabled** (see the item above), so it's dormant until that unparks.

### Electricians ask LLMs for chase/cable METRES — the demand we deliberately refuse
- **Status:** OPEN — **feature built but PARKED (UI-disabled) 2026-07-21, pending a design rethink**
- **Since:** Electrical-core iteration (2026-07-19)
- **Context:** A real electrician fed his project PDF to ChatGPT and Gemini asking for chase
  lengths and how much cable he needs. Both answer confidently; the number cannot be trusted
  (geometry at scale, silent error, straight into a quote). We answer the same need the safe
  way: count points with the model, compute the run with visible arithmetic. But the DEMAND is
  for the one-shot answer, so masters will keep trying the chatbots.
- **Notes / options:** Make the refusal a feature, not a gap — show WHY (bus + drops, drawn),
  and be fast enough that the honest path beats the confident-wrong one. Watch whether masters
  accept entering points, or whether we need a rough estimate mode («points × coefficient»,
  clearly labelled as an estimate) as a bridge. Decide from real usage, not theory.
- **Update (2026-07-21):** the full flow was built after a real-plan test — points off a plan
  (flat list, variant 2), **cable ≠ chase split** (two estimate entities: `CABLE` unit м / material,
  `SHTROBA` м.пог / work, from one shared payload), an explicit bus length with per-drop «штробити»,
  the plan seeding the calculator directly (no separate чернетка), and a **2D room plan editor**
  (`PlanEditor`) where the master draws the bus and its length is measured off the drawing. All
  green (backend + PWA + tests). **Then deliberately DISABLED in the UI** behind
  `ELECTRICAL_MEASUREMENTS_ENABLED = false` (`MeasurementsSection.tsx`) — the plumbing is right, but
  the **product shape isn't settled**: how points/rooms/cable/chase should combine for a real
  electrician's workflow (per-room vs one bus, distribution, whether to persist the drawn plan)
  needs more thought, and a higher-priority task came first. Re-enabling is a one-line flag flip.
  The open decision is now **the shape, not the maths** — the deterministic calc + drawn-bus honesty
  are proven; what's unresolved is the UX/model that makes it worth a master's time. Details:
  [iteration-electrical-core.md](iteration-electrical-core.md).
### Smart Sentry filter for client 4xx (mute external/bot, keep our own front-end)
- **Status:** OPEN
- **Since:** Multipart-415 fix (2026-07-19)
- **Context:** Client 4xx look like noise, so the tempting move is to drop them from Sentry.
  This fix is the counter-example: a 415 (`HttpMediaTypeNotSupportedException`) was a REAL
  front-end bug — every file upload was sent as application/json and the new sketch feature
  was dead in production for real masters. A blanket 4xx filter would have hidden it.
- **Notes / options:** If a filter is ever added, mute only clearly external/bot traffic
  (unknown paths, scanner probes, missing/invalid origin) and always keep 4xx that arrive
  from our own PWA origin. Deliberately NOT done in the fix itself — the safer default is
  noisy-but-honest reporting.

### Integration tests with Testcontainers
- **Status:** RESOLVED (audit M12, 2026-07-26) — a `@SpringBootTest` + `PostgreSQLContainer`
  slice now runs the real Flyway chain, the native money queries, and the security URL matrix
  through the actual filter chain. It found two real bugs on its first run: the untested
  `AND e.status <> 'REJECTED'` income guard, and every unauthenticated request answering 403
  instead of 401 (no `AuthenticationEntryPoint` was ever wired), which had been silently
  breaking the PWA's refresh-on-401. See
  [iteration-integration-slice.md](iteration-integration-slice.md).
- **Since:** step 1
- **Context:** All current tests are pure-Mockito unit tests. Nothing covers Flyway migrations actually running, real Hibernate mapping, or the security filter chain end-to-end. **Concrete miss:** Fix J — a `LazyInitializationException` on `User.trades` (open-in-view off, detached entity) shipped to prod because no test exercises a real Hibernate session/lazy-loading; the Mockito test could only pin the load-method choice, not the actual lazy behaviour. **Second concrete miss:** Fix K — admin user search 500'd in prod (`function lower(bytea) does not exist`) because no test executes the `@Query` SQL against a real Postgres; the unit test can only check the Java-side pattern building, not the generated `lower()/LIKE`.
- **Notes / options:** Spring Boot 4 removed `@DataJpaTest` etc — see CLAUDE.md *Testing* section. Use `@SpringBootTest` + Testcontainers `PostgreSQLContainer`. A lazy-loading regression slice (load user, detach, map to DTO) would catch the Fix-J class of bug; a repository slice that runs `searchAdmin` against Postgres would catch the Fix-K class.

### "What changed" highlighting on re-sign
- **Status:** OPEN
- **Since:** Estimate-UX iteration (2026-06-13)
- **Context:** Reopen (owner) → edit → client signs again. Today the client
  re-approves the **actual current** estimate but isn't shown a diff of what
  changed since the version they previously signed. Important for trust — it
  guards against a contractor quietly altering items between signatures.
- **Notes / options:** Snapshot the item set at each SIGN; on the portal re-sign,
  show added/removed/changed lines vs the last signed snapshot. Depends on the
  versioning item below. Until then the portal shows the current estimate in full.

### Estimate versioning / history
- **Status:** DEFERRED
- **Since:** step 2
- **Context:** Edit a sent estimate — old version is gone. Clients may want to see what they originally signed if there's a dispute. **Reinforced by the Estimate-UX iteration:** reopen now intentionally clears the signature and returns to DRAFT, so the previously-signed item set is not retained anywhere — a dispute ("what did I originally sign?") has no record.
- **Notes / options:** Snapshot on `SIGN`, immutable thereafter (a `signed_estimate_versions` table or JSON snapshot). Lower priority until a customer hits it; pairs with the "what changed" highlighting above.

### Soft delete
- **Status:** DEFERRED
- **Since:** step 2
- **Context:** All deletes are hard. No "trash" / undo.
- **Notes / options:** Add `deleted_at` columns + repository scoping. Defer until someone deletes the wrong thing in anger.

### METAL trade default prices are orientative
- **Status:** OPEN
- **Since:** metal-trade iteration
- **Context:** V54 seeds 66 METAL catalog positions with market-hint prices from domain knowledge (WebSearch was rate-limited at authoring time). They're placeholders a fabricator refines, same as every other default catalog — but no real market pass was done.
- **Notes / options:** Do a proper price pass once a metalworker uses it, or tune via the admin catalog editor (`AdminCatalogTemplatePage`). Non-blocking — masters set their own prices.

### PRO trial: "ending soon" reminder
- **Status:** OPEN
- **Since:** pro-trial iteration
- **Context:** The 5-day self-serve trial reverts to FREE silently via `BillingExpiryService`; the master gets no "trial ends tomorrow" nudge (a conversion moment).
- **Notes / options:** Reuse the auto-renew T-N reminder machinery (`findAutoRenewReminderDue` pattern) for a trial-ending email.

### Multi-account abuse: AI-call daily quota + blocklist upkeep
- **Status:** OPEN
- **Since:** anti-abuse-email iteration
- **Context:** The anti-abuse iteration closed the sharp edges — trial + client PDF now require a verified email, registration blocks disposable/no-MX domains and dedupes gmail aliases (`email_canonical`, V55). Not taken: option **E**, a hard per-user/day cap on LLM extraction calls (estimate/receipt import) — the strongest ceiling on live API cost if a determined abuser still verifies throwaway inboxes. Also: the disposable-domain blocklist is curated, not exhaustive; the SQL backfill only canonicalizes gmail (legacy non-gmail plus-aliases aren't retro-deduped).
- **Notes / options:** Add a daily AI-call quota keyed by user (even for PRO/trial) if trial abuse persists. Periodically refresh the blocklist from a maintained public list. Consider device/IP signals only if email-level guards prove insufficient.

### Seeders miss referral_code (NOT NULL since V41)
- **Status:** RESOLVED
- **Since:** anti-abuse-email iteration
- **Context:** `AdminSeeder` and `DevDataSeeder` built a `User` without `referralCode`, which is `NOT NULL UNIQUE` (V41) — a fresh seed (empty DB) failed on that column. Dormant today because existing DBs were backfilled by V41; only bit a brand-new deploy/dev DB.
- **Resolution:** Both seeders now inject `ReferralService` and set `.referralCode(referralService.generateUniqueCode())` (plus `.emailCanonical(...)` from V55). `AdminSeederTest` asserts the saved admin has non-null `referralCode`/`emailCanonical`. (DevDataSeeder is `@Profile("dev")`, untested.)

---

## Resolved

(nothing yet — when items close, move them here with a one-line resolution and the commit SHA)
