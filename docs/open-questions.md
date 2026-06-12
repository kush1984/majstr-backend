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
- **Status:** OPEN
- **Since:** DB-backup iteration (2026-06-12)
- **Context:** Daily backups now run (`.github/workflows/db-backup.yml` →
  Cloudflare R2, 30-day rotation) and a restore procedure is written
  (`docs/db-restore.md`). But no actual restore has been executed — a backup
  whose restore is untested can silently be unusable (wrong client version,
  truncated dump, role/ownership snags, missing extension).
- **Notes / options:** Once secrets are set and the first backup lands, download
  it and restore into a throwaway DB per `docs/db-restore.md`; confirm Flyway
  history + row counts + a `ddl-auto: validate` app startup. Repeat
  periodically. Until this passes once, do not rely on backups for real users.
  Railway Pro + PITR remains a recommended complementary tier (SPEC §H).

### Audit log for sensitive actions
- **Status:** OPEN
- **Since:** step 4
- **Context:** Admin can change a user's plan via `/api/admin/users/{id}/plan` — nothing records who did it. Same for hypothetical future "suspend user", "delete user".
- **Notes / options:** Separate `audit_events` table with `actor_id`, `action`, `target_id`, `payload`, `created_at`. Write via interceptor or explicit service calls.

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

### MetricsService full table scans
- **Status:** OPEN
- **Since:** Fix I code review (2026-06-10)
- **Context:** Admin metrics call `userRepository.findAll()` (twice for churn).
  Fine for hundreds of users, not thousands.
- **Notes / options:** Replace with aggregate queries (`COUNT ... GROUP BY`)
  when the user table grows; admin-only endpoint so urgency is low.

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
- **Context:** `EstimateShareLink.token` stores the raw token so the contractor can re-copy the URL later. DB compromise reveals all live share URLs.
- **Notes / options:** Hash like refresh tokens; lose the "show URL again" feature, gain breach safety. Decide once we have real users.

### Refresh-token reuse detection (session-family revocation)
- **Status:** OPEN
- **Since:** Fix I code review (2026-06-10)
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

### Public file serving needs auth once non-public assets exist
- **Status:** OPEN
- **Since:** Fix I code review (2026-06-10)
- **Context:** `/api/files/**` is fully public. Today it only serves contractor
  logos, which are public by design (anonymous portal + PDF). The moment
  photo reports or other private uploads land, public serving becomes a leak.
- **Notes / options:** Signed URLs (time-limited) or authenticated streaming
  for non-logo assets; ties into the S3/R2 migration item.

### Email enumeration on register
- **Status:** OPEN
- **Since:** Fix I code review (2026-06-10)
- **Context:** Register returns 409 "email already registered" — confirms an
  account exists. Login is enumeration-safe; register inherently isn't unless
  the flow goes async ("check your inbox" for both outcomes).
- **Notes / options:** Async-confirmation register is a UX cost; the register
  rate limit (Fix I) already curbs bulk probing. Likely accept as-is for v1.

### Password reset flow
- **Status:** OPEN
- **Since:** step 1
- **Context:** No reset endpoint — lock yourself out, lose the account.
- **Notes / options:** Needs an email service first (see below). Email transport now exists (`EmailService` / Resend, Fix D) — unblocked; just add the reset endpoint + token + email template.

### Email verification on register
- **Status:** RESOLVED
- **Since:** step 1
- **Context:** Anyone can register with any email; no proof of ownership. Fine for closed beta, blocks real billing later (people use throwaway emails).
- **Notes / options:** Soft model (register works immediately; banner + only share-link creation gated behind a verified email) via Resend.
- **Resolution:** Fix D — `User.emailVerified` + `EmailVerificationToken`, Resend `EmailService`, verify/resend endpoints, soft 403 `EMAIL_NOT_VERIFIED` gate on share; existing users migrated verified (V19). Verified live end-to-end (12 checks). PWA banner/page is a separate frontend task.

### Multi-factor auth / OAuth providers
- **Status:** DEFERRED
- **Since:** step 1
- **Context:** Not needed for v1; B2C contractor audience won't expect it. Revisit if first paying customer asks.

---

## Business logic

### Billing integration
- **Status:** OPEN
- **Since:** step 4
- **Context:** Plan change today is admin-only manual via `PATCH /api/admin/users/{id}/plan`. Real customers need self-serve checkout + recurring billing.
- **Notes / options:** WayForPay or Fondy for UA market; Stripe if going international. Webhook-driven plan changes flowing through the same admin endpoint internally.

### Plan downgrade with over-limit data
- **Status:** OPEN
- **Since:** step 4
- **Context:** PRO user with 7 active projects downgrades to FREE (limit 2). What happens? Today: nothing — limit only enforced on CREATE. They can edit / view existing 7 projects but can't make new ones until they delete down to 2.
- **Notes / options:** Either current "soft enforcement" is fine (UX-friendly), or block writes to over-limit resources too. Pick before billing lands.

### Trial period for PRO/TEAM
- **Status:** OPEN
- **Since:** step 4
- **Context:** No trial concept. New user is FREE forever until manual upgrade.
- **Notes / options:** Add `trial_ends_at` to user; `FeatureGuard` / `LimitService` reads it before checking plan.

### Team plan: actual multi-user workspaces
- **Status:** OPEN
- **Since:** step 4
- **Context:** `Plan.TEAM` exists in the enum but unlocks the same per-user features as PRO plus `AI_ASSISTANT`. No notion of a workspace shared between several users.
- **Notes / options:** Workspaces would need new entities (`Workspace`, `WorkspaceMember`) and ownership semantics on existing tables would shift from `owner_id (User)` to `workspace_id`. Big change; do not start until customers ask.

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

---

## Features in the catalog enum but not implemented

### PHOTO_REPORTS
- **Status:** OPEN
- **Since:** step 3
- **Context:** Enum value exists in `Feature` and grants in PRO/TEAM. No code path uses it.
- **Notes / options:** Likely a per-project gallery of contractor-uploaded photos with timestamped notes; reuses `StorageService`.

### AI_ASSISTANT
- **Status:** OPEN
- **Since:** step 4
- **Context:** Only TEAM has it. No code path.
- **Notes / options:** "Draft estimate from project description" feels like the highest-value first cut. Anthropic Claude API integration; gated by `Feature.AI_ASSISTANT`.

---

## Testing & quality

### Integration tests with Testcontainers
- **Status:** OPEN
- **Since:** step 1
- **Context:** All current tests are pure-Mockito unit tests. Nothing covers Flyway migrations actually running, real Hibernate mapping, or the security filter chain end-to-end. **Concrete miss:** Fix J — a `LazyInitializationException` on `User.trades` (open-in-view off, detached entity) shipped to prod because no test exercises a real Hibernate session/lazy-loading; the Mockito test could only pin the load-method choice, not the actual lazy behaviour.
- **Notes / options:** Spring Boot 4 removed `@DataJpaTest` etc — see CLAUDE.md *Testing* section. Use `@SpringBootTest` + Testcontainers `PostgreSQLContainer`. A lazy-loading regression slice (load user, detach, map to DTO) would catch the Fix-J class of bug.

### Estimate versioning / history
- **Status:** DEFERRED
- **Since:** step 2
- **Context:** Edit a sent estimate — old version is gone. Clients may want to see what they originally signed if there's a dispute.
- **Notes / options:** Snapshot on `SIGN`, immutable thereafter. Lower priority until a customer hits it.

### Soft delete
- **Status:** DEFERRED
- **Since:** step 2
- **Context:** All deletes are hard. No "trash" / undo.
- **Notes / options:** Add `deleted_at` columns + repository scoping. Defer until someone deletes the wrong thing in anger.

---

## Resolved

(nothing yet — when items close, move them here with a one-line resolution and the commit SHA)
