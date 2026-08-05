# Majstr Backend — Claude guide

SaaS backend for Ukrainian contractors. Current scope: auth, projects,
clients, catalog, estimates (PDF + public client portal with online signing),
subscriptions/admin, email verification, web push, monitoring. See
[README.md](README.md) for end-user setup and the public REST contract; this
file is for Claude / contributors working *in* the codebase.

## Stack (pinned)

- **Spring Boot 4.0.6** (Spring Framework 7, Jakarta EE 11) on **Java 21 (LTS)**
- **Gradle Kotlin DSL** — toolchain pinned to JDK 21 in `build.gradle.kts`
  (foojay resolver in `settings.gradle.kts` as a download fallback)
- **PostgreSQL 17** via `docker-compose.yml`, schema owned by **Flyway**
- **Spring Security 7**, stateless, JWT via **jjwt 0.12.x** (HS256)
- **Bucket4j 8.x** (`bucket4j_jdk17-core`) — login rate limiting
- **Jackson 3** — **note the package change** (see *Gotchas*)
- **Lombok**, **springdoc-openapi 2.8.x**, **JUnit 5 + MockMvc**

Don't bump these without a clear reason — the combo is chosen for Spring
Boot 4 / Spring 7 / Jakarta EE 11 / Java 21 compatibility. (Java 21 over 25:
Spring Boot 4's baseline is Java 17 and the code uses no 22-25-only feature,
so 21 LTS builds on the ubiquitous stable images — Java 25 had no reliable
build image on Railway.)

## Common commands

```bash
# bring Postgres up (requires .env with POSTGRES_PASSWORD)
docker compose up -d

# run the app (env vars from .env must be exported)
./gradlew bootRun

# tests
./gradlew test

# full build with verification
./gradlew build
```

JWT secret and DB credentials come from **env vars only** — never hardcode.
The base `application.yml` references `${JWT_SECRET}` with no default;
startup fails fast if it isn't set.

## Package layout

```
com.majstr.backend
├── MajstrApplication.java     — @SpringBootApplication, registers @ConfigurationProperties
├── config/                    — SecurityConfig, OpenApiConfig, *Properties records
├── controller/                — REST endpoints (thin, delegate to services)
├── service/                   — business logic, @Transactional boundaries
│   ├── ai/                    — LLM provider plumbing (see the recognition section)
│   ├── album/                 — full-album takeoff: surfaces + electrical, two flows
│   ├── importer/              — estimate / receipt / catalog import from file or photo
│   └── measurement/           — measurement domain + project-document import
├── repository/                — Spring Data JPA interfaces
├── entity/                    — JPA entities (Lombok-annotated)
├── dto/                       — request/response **records**, validated with jakarta.validation
├── security/                  — JwtService, filters, UserPrincipal, body-cache wrapper
├── feature/                   — plan gates: Feature/Limit enums, PlanConfig, guards
├── billing/                   — monobank checkout, signatures, auto-renew
├── storage/                   — pluggable file storage (local / S3-R2)
├── email/                     — Resend HTTP client + templates
├── push/                      — Web Push (VAPID)
├── bootstrap/                 — startup seeding
├── dev/                       — @Profile("dev") only
└── exception/                 — typed exceptions + GlobalExceptionHandler
```

Layering rule: `controller → service → repository`. Entities never leave
the service layer — controllers return DTOs. `passwordHash` never appears
in any response (`UserResponse` excludes it; `User#toString` excludes it).

## Architecture notes (non-obvious)

### Auth flow

1. `POST /api/auth/register` — `AuthService.register` hashes via BCrypt(12),
   persists the user, issues access + refresh tokens.
2. `POST /api/auth/login` — `LoginRateLimitFilter` runs first
   (`addFilterBefore(UsernamePasswordAuthenticationFilter)`), then the
   controller delegates to `AuthService.login`.
3. `POST /api/auth/refresh` — `RefreshTokenService.rotate` revokes the old
   refresh token and issues a new pair (rotation pattern).
4. `POST /api/auth/logout` — public (takes the refresh token in the body),
   `RefreshTokenService.revoke` marks that token revoked. Idempotent: an
   unknown/blank token is a silent no-op so logout always succeeds even with
   an expired access token. The PWA must call this, not just clear storage.
5. `GET /api/auth/me` — `JwtAuthenticationFilter` parses the Bearer token,
   loads the user, sets `SecurityContextHolder` with `UserPrincipal`.

### Refresh tokens are hashed at rest, rotated, and swept

Raw refresh token = 48 random bytes, base64url-encoded, returned to the
client **only once** on issue. The DB stores only its SHA-256 hash
(`refresh_tokens.token_hash`, UNIQUE). On `/refresh`, the incoming raw
token is re-hashed and looked up by hash. `revoked = true` is set on the
old row before issuing the new one. Don't change this to store raw tokens.

**Rotation has a client consequence:** because the old token is revoked the
instant it's used, several requests that 401 at once must **not** each call
`/refresh` with the same token — only the first would succeed, the rest hit a
revoked token and 401 → spurious logout. The PWA interceptor must single-flight
`/refresh` (one in-flight promise, queue the rest). The backend is correct;
this race is a frontend concern.

Session length is `app.jwt.refresh-token-expiration-days` (env
`REFRESH_TOKEN_TTL_DAYS`, default **30** — long is fine because tokens rotate).
`TokenCleanupService` (`@Scheduled`, daily 3am, cron
`app.cleanup.tokens-cron`) sweeps expired-or-revoked refresh tokens and expired
email-verification tokens so neither table grows unbounded (`@EnableScheduling`
on the app; single-node — needs ShedLock if scaled out).

### Email verification is soft

`register` issues an `EmailVerificationToken` (24h) and emails it via
`EmailService` → `ResendEmailService` (Resend HTTP API). The send is
`@Async` and **fail-soft** — a mail error is logged, never breaks
registration. Existing users were set verified in V19, so the gate can't
lock anyone out retroactively. Login and general use are **not** gated;
only `POST /api/estimates/{id}/share` requires `emailVerified` (else 403
`code: EMAIL_NOT_VERIFIED`). `POST /api/auth/verify-email` is public;
`POST /api/auth/resend-verification` is authenticated and rate-limited
(1/60s/user). Resend env vars: `RESEND_API_KEY` (blank in dev → email is
logged & skipped), `EMAIL_FROM`, `APP_URL` (verify-link base). Production
needs a Resend-verified sending domain in `EMAIL_FROM`.

`PUT /api/profile` (`ProfileService.updateProfile`) edits `fullName`, `phone`,
`companyName`, `trades` and — **conditionally** — `email`. Email is editable
**only while `emailVerified == false`** (fix a registration typo): the change
re-checks uniqueness (409 if taken), keeps the account unverified, and
`EmailVerificationService.replaceForNewEmail` drops the user's old tokens
(`deleteByUserId`) and sends a fresh verification to the new address. A
**verified** email is locked — a different value is silently ignored and the
rest of the profile still saves. Replacing `trades` never touches the
contractor's catalog (it's independent once seeded at register).

### Web push is fail-soft and env-gated

`PushService.sendToUser` (in `push/`) notifies the contractor via Web Push
(VAPID / RFC 8291, `nl.martijndwars:web-push`) when a client **signs an
estimate** or **leaves a question** — wired into `PublicEstimateService.sign`
/ `askQuestion`. Same env-gated, `@Async`, fail-soft pattern as email: when
`VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` are blank (dev) it logs & skips; a
push failure never breaks the portal action. Subscriptions live in
`push_subscriptions` (one row per browser `endpoint`, UNIQUE → `subscribe`
upserts). On HTTP 404/410 from the push service the dead subscription is
deleted. The click-through `url` is a **relative** path (`/projects/{id}`) so
the PWA service worker resolves it against its own origin. `GET
/api/push/vapid-public-key` is public (in `PUBLIC_PATHS`); subscribe/
unsubscribe require auth. BouncyCastle is registered once in a `PushService`
static block. **Note:** web-push adds transitive deps (BouncyCastle pinned to
1.78.1, Apache HttpClient, jose4j) — `send()` returns
`org.apache.http.HttpResponse`. Two non-obvious gotchas already burned us:
(1) httpcore + jose4j are `runtime`-scope in web-push's POM but their types
(`HttpResponse`, `JoseException`) appear at *compile* time where we call
`send()` — both are pinned as explicit `implementation` deps in
`build.gradle.kts`. (2) `PushService.send(Notification)` defaults to the legacy
`aesgcm` encoding, which **current FCM rejects with HTTP 403** — `deliver()`
must pass `Encoding.AES128GCM` explicitly (the modern `vapid t=,k=` header).

### Client portal is project-level (estimate links are legacy)

One portal per **object**: `project_share_links` holds the (idempotently-minted, single
live) token, `estimates.portal_visible` says which estimates the portal shows — the master
ticks them explicitly in the share sheet, nothing is shared by default. The page
(`static/portal/index.html`) renders a section per visible estimate; sign / question / PDF
are addressed per estimate under the project token
(`/api/public/portal/{token}/estimates/{id}/...`), and an estimate is reachable only while
`portal_visible` (else neutral 404). Legacy per-estimate links (`?t=`, `EstimateShareLink`)
**stay valid** for URLs already sent — same page, one render path via a client-side adapter
— but the PWA no longer creates them. Both token families share one core in
`PublicEstimateService` (`doSign`/`doAsk`) so sign semantics exist once. See
[docs/iteration-portal-multi-estimate.md](docs/iteration-portal-multi-estimate.md).

### Client messages are a read-only inbox, but clients can attach files

**Renamed from "questions" — the old names are gone.** `EstimateQuestion` → `ProjectMessage`
(V74), `QuestionService` → `MessageService`, `ProjectQuestionController` →
`ProjectMessageController`, `/api/projects/{id}/questions` → `/api/projects/{id}/messages`.
Don't reintroduce the question wording in new code.

**Direction is still one-way.** Clients send; the contractor reads, marks read, deletes, and
follows up out-of-band. There is no in-app reply thread. The master side is
list / `PATCH .../{id}/read` / delete / download-a-file, all scoped through
`ProjectService.loadOwned`.

**Messages are project-level with an OPTIONAL estimate link.** `ProjectMessage.project` is
mandatory, `estimate` is nullable — a message about the object as a whole no longer has to be
pinned to a variant, while one left from an estimate still is (the inbox shows which). Also
carries `authorName` / `authorPhone` / `authorIp` and `read` (column `is_read`).

**Two ways in, and they are different tokens.** The estimate portal still accepts a message
alongside a signature; separately, V75 added a **`kind` to `project_share_links`** so a master
can hand out a **message link** — `MessageLinkService` + `MessageLinkController`
(`/api/projects/{id}/message-link` to mint/revoke/inspect, public
`GET|POST /api/public/message-link/{token}`) — that lets a client send a message **with file
attachments** (multipart) without seeing any estimate. `MessageLinkRateLimiter` guards the
public POST.

**Attachments have a retention policy, deliberately in two passes.**
`ProjectMessageFile` (V76) holds the files; `MessageFileRetentionService` expires them after
**six months** — first a pass that *warns* the master which object's file is going and when
(V77 stores the warning), then a second pass that deletes what was ignored. Silent deletion
would be data loss dressed up as housekeeping. The grace window is
`app.message-files.grace-days` (default 14) and `MessageView` reads it so the app can show a
real date rather than making the client infer the server's schedule.

`GET /api/projects` still carries an unread count per card from **one grouped query** folded
into the list — same no-N+1 pattern as the latest-estimate summary, backed by a partial index
on unread rows. **Naming gotcha survives the rename:** the entity field is `read` (not
`isRead`) so the JPQL path and derived `...AndReadFalse` queries share one property name,
while the view record component is `isRead` — that is the JSON key the PWA sees.

### Login rate limit relies on a custom request wrapper

`LoginRateLimitFilter` needs to read the JSON body to extract `email` for
the rate-limit key, **and** Spring still needs to read it for `@RequestBody`.
Servlet input streams aren't re-readable, and `ContentCachingRequestWrapper`
doesn't replay reads — so we use a custom
[CachedBodyHttpServletRequest](src/main/java/com/majstr/backend/security/CachedBodyHttpServletRequest.java)
that buffers the body once and yields a fresh `ServletInputStream` per
`getInputStream()` call. The filter passes the wrapped request downstream.

Bucket key is `lowercased-email + "|" + clientIp`, where `clientIp`
respects the first entry of `X-Forwarded-For` if present. Buckets live in
a `ConcurrentHashMap` — fine for single-instance dev/prod but a known
limitation for multi-node deployments (would need a shared store).

`RegisterRateLimitFilter` does the same for `POST /api/auth/register`, but
keyed by **IP only** (no account exists yet) and without the body wrapper
(nothing to parse). Config: `app.rate-limit.register` (5/hour default).

### Signed estimates are immutable (state machine)

Once a client signs via the portal (`status == SIGNED`), the estimate is
locked: `EstimateService.update`/`addItem`/`addItemFromCatalog`/`updateItem`/
`deleteItem` all throw `EstimateSignedException` → **409 `ESTIMATE_SIGNED`**.
The signature certifies exact items and totals. Setting `SIGNED` manually
through `PUT /api/estimates/{id}` is also rejected (400) — a signature only
comes from the portal, so signer metadata is real. **Deleting** a signed
estimate is **forbidden** too (409 `ESTIMATE_SIGNED`) — it's legally
significant and work is underway; DRAFT/SENT delete freely.

To revise a signed deal, the **owner** `reopen`s it (`POST
/api/estimates/{id}/reopen`, owner-only — the public portal has no path to it):
status → DRAFT, signature fields cleared, `reopenedAt`/`reopenedBy` stamped for
audit. The contractor then edits and the client **signs again** (transparency —
they re-approve the actual current estimate; "what changed" highlighting is a
future feature, see open-questions). The project's own status is left as-is on
reopen (work already started). `Estimate` carries a JPA `@Version` column (V23)
so two concurrent portal sign requests can't both win — the loser gets 409 via
the `OptimisticLockingFailureException` handler. Estimates also have an optional
`name` (V25) to tell variants apart (econom/premium), editable while not signed.

### Spring Security 7 wiring

`SecurityConfig.filterChain` uses the lambda DSL only (Spring 7 removed
the deprecated chained forms). Both custom filters are added with
`addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)` —
order matters: `LoginRateLimitFilter` must run before
`JwtAuthenticationFilter` so login attempts are rate-limited even when no
JWT is presented. Public paths are listed in `SecurityConfig.PUBLIC_PATHS`;
everything else requires authentication.

CORS is configured via `CorsConfigurationSource` bean fed by
`CorsProperties.allowedOrigins` (comma-separated env var).

### Localization of user-facing messages

Every message an end user can see (ErrorResponse bodies, the filters' 429
bodies, push notification titles) resolves through a `MessageSource`
(`LocalizationConfig`, explicit beans — don't rely on Boot auto-config).
**Bundle layout is deliberate:** `messages.properties` (the base file) is
**Ukrainian** — the product language — so every unknown `Accept-Language`
falls back to Ukrainian, never to English internals; `messages_en.properties`
is served only on an explicit `Accept-Language: en`
(`fallbackToSystemLocale=false` keeps the JVM locale out of it).

Conventions:
- **Exception messages stay English** (log detail). The advice maps the
  exception *type* to a bundle key, or — for exceptions thrown with a
  context-specific text (`EmailNotVerifiedException`,
  `ClientEmailMissingException`, `InvalidVerificationTokenException`,
  `UnsupportedMediaTypeException`, `TooManyRequestsException`,
  `InvalidEstimateStatusException`) — the **throw site passes the bundle key
  as the exception message** and the advice resolves it (`msg(ex.getMessage())`,
  falling back to the raw text for unknown keys, so a stray literal can't 500).
- Filters run before the `DispatcherServlet` sets the locale context — they
  use `LocalizationConfig.requestLocale(request)` (header → locale, no header
  → Ukrainian; never the JVM default).
- Push titles always use `LocalizationConfig.UKRAINIAN` (the contractor's
  request context is the *client's*, not theirs).
- Values used **with arguments** go through `MessageFormat` — double the
  apostrophes (`об''єктів`); values without arguments don't.
- Plural forms: `GlobalExceptionHandler.projectsPluralKey` picks
  `plural.projects.one/few/many` with Ukrainian mod-10/mod-100 rules.
- **Not localized (deliberate):** jakarta-validation field errors (the PWA
  validates client-side with its own uk texts; see open-questions), Swagger
  descriptions, log lines, email HTML (already Ukrainian, lives in
  `ResendEmailService` as templates, not bundle messages).
- The standalone-MockMvc tests pin the locale with an explicit
  `Accept-Language` header — without it the default resolver falls back to
  the JVM locale and assertions go nondeterministic.

### Error response shape

All errors flow through `GlobalExceptionHandler` and use
[ErrorResponse](src/main/java/com/majstr/backend/dto/ErrorResponse.java):

```
{ timestamp, status, error, message, path, retryAfterSeconds?, code? }
```

`retryAfterSeconds` is set only by `ErrorResponse.rateLimited(...)` (the
login filter, the resend-verification 429 and the estimate-email 429).
`code` is an optional machine-readable code (`EMAIL_NOT_VERIFIED` on the
share gate; `CLIENT_EMAIL_MISSING` when emailing an estimate to a client
who has no address; `ESTIMATE_SIGNED` when mutating a signed estimate) so
clients can branch without parsing the message.
Null fields are stripped globally via
`spring.jackson.default-property-inclusion: non_null`.

Status mapping:
- 400 — `MethodArgumentNotValidException`, `ConstraintViolationException`,
  `HttpMessageNotReadableException`, `InvalidEstimateStatusException`
- 401 — `BadCredentialsException`, `UsernameNotFoundException`,
  `InvalidTokenException`, any other `AuthenticationException`; **also every
  request that never reaches a controller** because it carries no usable token
  — `RestAuthenticationEntryPoint`. Without that bean Spring defaults to
  `Http403ForbiddenEntryPoint` and answers **403**, which silently broke this
  contract: the PWA refreshes on 401 only, and 403 is already spoken for by
  plan limits / `EMAIL_NOT_VERIFIED` / ownership, so the two were
  indistinguishable. **Keep 401 and 403 disjoint** — 401 means "re-authenticate",
  403 means "this account may not".
- 403 — `AccessDeniedException` (from a service/controller, via the advice) and
  authenticated-but-unauthorized requests (`RestAccessDeniedHandler`, e.g. a
  non-admin on `/api/admin/**`)
- 404 — `ResourceNotFoundException`; `NoResourceFoundException` (unknown
  path / bot-scanner probes like `/admin/phpinfo.php`) — handled explicitly so
  it's a quiet 404, **not** the 500 fallback, and is **not** reported to Sentry
- 409 — `EmailAlreadyExistsException`, `EstimateSignedException`
  (`ESTIMATE_SIGNED`), `OptimisticLockingFailureException` (concurrent edit)
- 429 — emitted directly by `LoginRateLimitFilter` / `RegisterRateLimitFilter`
  (does **not** go through the advice — the filters write the response
  themselves, bypassing Spring MVC)
- **no response at all** — the CLIENT hung up mid-write (`Broken pipe` / `Connection reset by peer`).
  `GlobalExceptionHandler.isClientDisconnect` walks the WHOLE cause chain (it arrives four wrappers
  deep: `HttpMessageNotWritableException → JacksonIOException → AsyncRequestNotUsableException →
  ClientAbortException → IOException`) and returns `null` — debug log, **no Sentry**, no body,
  because there is no socket left to write to. Same rationale as the quiet 404 for scanner probes.
  The walk is depth-bounded: a cyclic cause chain would otherwise hang the request thread. Do NOT
  widen it to "any IOException" — a disk or upstream failing mid-write is ours and must stay a 500.
- 500 — fallback, with a logged stack trace; also reported to Sentry
  (env-gated on `SENTRY_DSN`, endpoint tag + opaque user id, no PII)

### Offline-first: client-UUID idempotent creates (`X-Entity-Uuid`)

The PWA authors offline (an outbox replays queued writes on reconnect —
[docs/iteration-offline-first.md](docs/iteration-offline-first.md)), so a replayed create must
**not duplicate**. The convention: a create endpoint accepts an **optional client-generated UUID in
the `X-Entity-Uuid` header** and the service makes the create **idempotent** — if an entity with
that id already exists and the caller owns it, return it; if it belongs to someone else, `403`
(`AccessDeniedException`); otherwise create with that id (the entity's `@PrePersist` only generates
an id when null, so a supplied id is honoured). The idempotency check runs **before** any limit
check / churn counter, so a replay can't spuriously trip the FREE cap. Already wired on `clients`,
`projects`, `estimates` (`createForProject`), and estimate **items** (`addItem`); deletes are
idempotent no-ops. A 2-arg overload keeps existing callers/tests. **When you add a new
offline-authorable entity, follow this pattern** (header + `create(req, ownerId, requestedId)`
overload + idempotency-first). The over-limit case surfaces to the PWA's "PRO or delete" screen via
the normal `403 *_LIMIT_REACHED` code.

### Schema is owned by Flyway

`hibernate.ddl-auto: validate`. Never put schema changes in entity
annotations expecting Hibernate to apply them. Add a new
`V<N>__<desc>.sql` under `src/main/resources/db/migration/` — **check the highest existing
number first** (`ls` the directory and sort numerically; the latest is **V88**). Never edit an
applied migration: Flyway checksums it and a changed file fails startup. Trades live
in the `user_trades` collection table (one row per `(user_id, trade)`,
mapped via `User.trades` `@ElementCollection`); it has a `CHECK`
constraint enumerating the allowed values — if you add a `Trade` enum
constant, write a migration to extend that CHECK. (The old single-valued
`users.trade` column was dropped in V16.)

### File storage is pluggable (local / S3-R2)

`StorageService` (`storage/`) abstracts file persistence with an opaque object
key (`logos/uuid.png`). Two impls: `LocalStorageService` (filesystem, dev
default; content type parked in a `.meta` sidecar) and `S3StorageService` (any
S3-compatible store, built for **Cloudflare R2**; content type is native object
metadata). **Neither is `@Service`-scanned** — `StorageConfig` builds exactly
one bean from `app.storage.kind` (`local` | `s3`, env `STORAGE_KIND`, default
`local`), so the two never collide. The interface is unchanged from when local
was the only impl. AWS SDK v2, sync `S3Client` over `UrlConnectionHttpClient`
(no Netty); `forcePathStyle(true)` + region `auto` for R2; creds via env
(`R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`).

**Reads always go through the backend** — `FileController` (`/api/files/**`)
streams via `storage.open(key)`, and the PDF reads the logo the same way. So the
bucket needs **no public-read policy**; keys are identical across backends, so a
stored `logoUrl` survives a local→R2 switch. A direct-public / CDN read path is a
possible future optimization, not needed now.

### Which model reads what: `service/ai/` (per-flow provider + model)

**There is no single "the LLM" any more.** Recognition jobs are genuinely different tasks: a
receipt is a small printed table read many times a day (cheap and fast wins); an A3 measure plan
is dense line-work read in several passes per project (the strongest vision pays for itself).
One model for both means overpaying on every receipt or under-reading every drawing.

- **`AiFlow`** enumerates the jobs: `ESTIMATE`, `RECEIPT`, `SKETCH`, `ELECTRICAL`,
  `PROJECT_DOCS`. `flow.key()` is its config key (`PROJECT_DOCS` → `project-docs`).
- **`JsonExtractor`** is the seam (`requestJson(input, systemPrompt, schema)` +
  `providerName()`), implemented by `AnthropicJsonExtractor`, `OpenAiJsonExtractor`, and
  `MisconfiguredJsonExtractor` (see below).
- **`AiExtractors`** resolves flow → extractor **once, at startup**, and logs the whole mapping.
  Services call `extractors.forFlow(AiFlow.X)` — a lookup, not a decision. Adding a third vendor
  is one `JsonExtractor` implementation plus one branch in `build`; **no service changes**.
- **Config** is `AiFlowsProperties` (`app.ai.*`): `provider` (default vendor), `model` (override
  that vendor's default), and `flows.<key>` = `vendor:model` | just a model | just a vendor.
  **With nothing set, behaviour is exactly what it was** — the default extractor everywhere.

Three decisions worth not undoing:

- **Resolution is at startup and logged**, because "which model produced this reading" is the
  first question about a bad result, and it must be answerable from the log rather than by
  re-reading config. It also keeps a comparison honest: one model per flow per run.
- **A typo disables that ONE flow and says so** (`MisconfiguredJsonExtractor` → the usual 503),
  instead of silently falling back to the default. Results attributed to a model nobody chose
  are worse than results that never came. Same for a flow naming a vendor whose key is unset.
- **An empty config value is treated as absent.** `${AI_FLOW_RECEIPT:}` with nobody setting the
  variable arrives as a present-but-blank key — the exact trap that once turned an unset
  provider into 25 failed integration tests.

**The album extractor is deliberately outside this registry.** `ClaudeAlbumExtractor` keeps its
own HTTP client with much longer timeouts, because a whole-album pass runs for minutes; it joins
`AiFlow` when the seam learns to carry a timeout.

### Estimate import from Excel/photo is LLM extraction (per-flow provider, raw HTTP)

`POST /api/estimates/import/parse` (multipart) and `/commit` (JSON) import a ready
estimate **onto an object** from an Excel/CSV file or a **photo** (printed or
hand-written). PRO-gated via `Feature.ESTIMATE_IMPORT` (PRO+TEAM — deliberately
**not** the TEAM-only `AI_ASSISTANT`, which stays reserved for "draft from a
description"). **`EstimateExtractor`** (in `service/importer/`) owns the prompt and the schema and
delegates the call to `extractors.forFlow(AiFlow.ESTIMATE)` — it used to BE the Anthropic client
too, and that is exactly the split described in *Which model reads what* above. The transport
(`AnthropicJsonExtractor` / `OpenAiJsonExtractor`) is **raw HTTP** via Spring `RestClient` — same
no-SDK precedent as `ResendEmailService`/`MonobankClient` — with structured output via
`output_config.format` (a JSON schema; **no beta header** — structured outputs + vision are GA on
Opus). Two input branches, one extractor: Excel/CSV → POI text grid → text input; photo → base64
image input (vision). Keys are env-only (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`).
**Not fire-and-forget:** unlike
email/push, a blank key or a call/parse failure throws `AiExtractionException` →
**503 `AI_UNAVAILABLE`** (the import is synchronous, the master is waiting), so the
PWA can offer "enter manually". Before giving up, the shared call (`requestJson`)
**retries transient failures** — HTTP 429, any 5xx incl. Anthropic's **529
"Overloaded"**, or a dropped connection — up to 3 quick attempts with a short linear
backoff (`isTransient` / `postForMap`); a permanent 4xx (bad request, bad key, 413) is
not retried. So a momentary overload no longer drops the master to manual on the first
blip; on exhaustion it's the same 503. The uploaded file is parsed then **discarded**
(never stored). `parse` returns a review proposal (no auto-commit; units normalized
via `UnitNormalizer`, unreadable values flagged in `issues`); `commit` creates the
estimate through `EstimateService.createFromImport` (respects the FREE estimate cap
+ ownership) **and** upserts the ticked positions into the catalog by reusing
`CatalogImportService.commit` — one transaction, no external I/O inside it. The
`SYSTEM_PROMPT` tells the model to use sentinels (0 / empty string) for unreadable
values rather than guessing — mapped back to null + a review flag server-side.

### A photographed sheet is CLASSIFIED before it is read (`sheetKind`)

`SketchParseResponse.sheetKind` is `HAND_DRAWN` or `PRINTED_PLAN`, and it decides **whether the
reading is the answer at all**. `POST /api/projects/{id}/measurements/sketch/parse` takes an ARRAY
of sheets (parameter still named `file`, so one photo is a one-element list), and:

- **`HAND_DRAWN`** — кроки, read fully as before. That path was built for them and handles them well.
- **`PRINTED_PLAN`** — a designer's sheet or a технічний паспорт. **Named and nothing more:** `rooms`
  comes back empty *by design* and the PWA hands the same files to the project-import flow.

Why: the sketch schema is rooms → items → planes with **no field for a room's area**, so a printed
plan read there loses the printed areas and has to multiply chains instead — which produced areas
wrong by 8 % and 16 % in production, plus rooms with no walls and duplicated rooms across two photos
of one sheet. The import conveyor reconciles each printed area against its gabarits, merges sheets,
and guarantees every room a floor + ceiling + four walls. None of that exists on the sketch path.

**The default leans to `PRINTED_PLAN`, including when the field is missing entirely** — a model that
forgets it must not quietly get the кроки treatment, which is the exact failure the field exists to
stop. Deploy consequence: the backend shipping AHEAD of the PWA makes an old client show «Не вдалося
нічого прочитати» on a printed plan, so ship together or PWA first.

Passport conventions (Постанова КМУ № 488) are gated behind **positive evidence** — at least two of:
number-over-area fraction, two-decimal sizes, «h=2,50», «Масштаб 1:100» with «ПОВЕРХ», a БТІ title
block. A passport is the ONE sheet in metres; applying its rules to a designer's plan turns «3500»
millimetres into 3500 metres. See [docs/iteration-import-quality.md](docs/iteration-import-quality.md).

### An estimate can be duplicated with a markup (the бригадир's two prices)

`POST /api/estimates/{id}/duplicate` copies an estimate and raises SELECTED lines by a percent —
works ticked by default, materials not. The **parent** holds master prices, the **duplicate** holds
client prices, and the object economy counts only the difference:

```sql
CASE WHEN e.duplicated_from_id IS NULL THEN i.unit_price
     ELSE i.unit_price - COALESCE(i.source_unit_price, 0) END
```

**The source price lives on the LINE (`estimate_items.source_unit_price`), not as one percent on the
estimate** (V85). A single percent breaks on all four things that actually happen afterwards: the
markup applies to selected lines only, the parent can be deleted, a price can be edited in the copy,
and a line can be added that the crew is never paid for. NULL source on an added line correctly means
"all of it is margin". `estimates.markup_percent` is kept as a **label only** — nothing computes from
it. Duplicating sets `countInEconomy = false` on the parent.

`DELETE /api/estimates/{id}/items` deletes many lines at once and **cascades parent → duplicate,
never the reverse** (a line the crew is not doing cannot survive in the client copy; SIGNED copies
are skipped). See [docs/iteration-duplicate-with-markup.md](docs/iteration-duplicate-with-markup.md).

### Consolidated estimate, receipt import, and object photos

Three related capabilities added together (docs/iteration-consolidated-receipts-photos.md):

- **Consolidated estimate** — `POST /api/projects/{projectId}/estimates/consolidate`
  (`EstimateService.consolidate`) folds the line items of several of the object's
  estimates into one **new DRAFT** estimate (plain concat, no dedup;
  `measurementRefs` not carried over). It's an ordinary estimate — counts against the
  FREE per-project cap, goes through the normal editor. Each source estimate is
  ownership- and same-project-checked.
- **Receipt import** — `POST /api/estimates/{id}/receipt-items/parse|commit`
  (`ReceiptImportService`, `service/importer/`) adds lines to an **open** estimate from
  a **receipt photo** (store/terminal/hand-written) via `EstimateExtractor` with a
  **receipt-specific system prompt**, on its own `AiFlow.RECEIPT` (so a receipt can run on a
  cheaper model than a drawing — that is the whole reason flows exist). Image-only, no Excel branch;
  `commit` calls `EstimateService.appendItems` (SIGNED → 409) — **no catalog upsert**
  (unlike the estimate import). Gated by a **new `Feature.RECEIPT_IMPORT` (PRO+TEAM)**,
  distinct from `ESTIMATE_IMPORT`. Same 503 `AI_UNAVAILABLE` / discard-the-file behaviour.
- **Object photos** — `project_photo` (V47), served by `ProjectPhotoController`
  (`/api/projects/{id}/photos`). Gated by `Feature.PHOTO_REPORTS` (**granted to all
  plans incl. FREE** in `PlanConfig` — "show the client the product"; routed through
  `FeatureGuard` so flipping to PRO is a one-line matrix edit). Two `source`s: `RECEIPT`
  (always `PRIVATE`, linked to its estimate via `estimate_id` + a durable
  `estimate_name_snapshot`) and `MANUAL` (progress photo, `PRIVATE` by default, toggle to
  `SHARED`). **Privacy is the key design point:** photos are **never** served from the
  public `/api/files/**` — an **authenticated owner** stream
  (`GET /api/projects/{id}/photos/{photoId}/file`) and a **portal-token-gated** stream
  (`GET /api/public/estimates/{token}/photos/{photoId}/file`, `SHARED`-only, same-object
  re-checked) are the only read paths, and the storage key never leaves the server.
  `PublicEstimateView.sharedPhotos` lists the object's SHARED photos for the portal
  gallery (`static/portal/index.html`). This is the first private upload type — it closes
  the "public file serving needs auth" open question for this asset class.

### The default catalog is reference data; an estimate line is a snapshot

Two rules that make the catalog safe to rewrite, and that every catalog migration leans on:

1. **Default catalog data is copied BY VALUE.** `catalog_templates` is what a new master's
   `catalog_items` are seeded from. Changing a template only affects future copies — which is
   why V70–V72 could redo the seed data at all.
2. **`estimate_items` hold their own name, unit and price**, copied when the line was added,
   with **no foreign key** to `catalog_items`. An estimate written last month keeps every
   figure it was written with, no matter what happens to the catalog. Never "fix" this by
   adding a FK — the snapshot is the feature (the client signed those numbers).

**V70–V73 cleaned up after the V50 "tetris" import**, which had claimed a
punctuation-insensitive dedupe but could not see the older rows: those had been stored with
punctuation *and connecting words* stripped, so the comparison saw two different strings. One
work ended up sold under two names, and default templates referenced positions **by name**, so
big and small bundles pointed at different rows for the same job.

- **V70** — the fixes with a single correct answer; duplicate positions and placeholder prices
  were deliberately left for an owner pricing decision.
- **V71** — collapses the duplicate groups in two passes (provable string-equivalence first,
  then stripped-word matches). Within a group **the highest price wins** — the same rule V49
  used — so a price a master raised themselves survives.
- **V72** — breaks up the four categories that only repeated the trade name («ЕЛЕКТРИКА» inside
  the electrical trade is not a grouping), moving 107 positions into real buckets and adding
  «Штроблення». Statements are per position, not per keyword, so the mapping is reviewable.
  **Category is display-only — nothing matches on it**, which is what makes this redoable.
- **V73** — carries the same cleanup into catalogs masters ALREADY hold (V71/V72 only changed
  new copies). Created estimates are untouched, per rule 2.

Guarded by `SeedCatalogInvariantsIntegrationTest` and
`CatalogCleanupOnLegacyDataIntegrationTest` — these run the migrations against real Postgres,
which is the only place this class of SQL can be verified.

**V81–V84 removed materials and rebuilt the tiling catalog** — see
[docs/iteration-tiling-catalog-rebuild.md](docs/iteration-tiling-catalog-rebuild.md). Three facts
that outlive that iteration:

- **The default catalog is works-only, in every trade** (V81). `ItemType.MATERIAL` still exists
  and estimates still split works/materials — only *picking* a material from our list is gone,
  because receipt-photo import supplies the real price and a stale guess competing with a real
  number is worse than no guess. Don't reintroduce default materials without deciding that again.
- **V83 is the first migration to delete rows from a live master's own catalog.** Anything of
  that kind must be fenced the same four ways: never touch `estimate_items`; delete only
  `source = 'LIBRARY'`; keep a row whose price the master changed (compare against the price we
  shipped, captured *before* the templates are deleted); and never touch a master-built template.
- **A migration that rewrites a master's catalog must tell them.** `catalog_update_notices` holds
  one pending notice per master, read once on app entry via `GET /api/catalog/update-notice`.
  Silent rewriting of the thing a master quotes from reads as data loss.

Both are covered by `MaterialRemovalOnLiveDataIntegrationTest` and
`TilingCatalogRebuildOnLiveDataIntegrationTest`, which use the "second database migrated to the
version before the change" pattern — the only way to test a DATA migration, since the normal
slice runs every migration against an empty schema before any test row exists.

### «%» is a share OF something, and the line amount is STORED (V88)

A `PERCENT` line used to multiply like any other unit: 10 × 500 = 5 000 ₴, printed as
«10 % · 500 ₴/%» — five hundred hryvnia for one percent. It is now a percentage of a **base**, and
`quantity` holds the percent (10 = 10 %).

| base | where it comes from |
|---|---|
| `MANUAL` | a sum typed by hand — lives in `unitPrice` |
| `POSITION` | another line of the same estimate (`percent_base_item_id`) |
| `TOTAL` | the estimate's own subtotal, before any TOTAL line |

**A percent of a percent is forbidden**, and that is the whole cycle protection: the base picker
offers ordinary lines only, so no chain can close on itself. There is no graph to walk and no cycle
detection to get wrong.

**`estimate_items.line_total` is stored, written by the server on every write, never accepted from a
request.** Six native aggregates (dashboard, object economy, project cards) compute a total with one
`SUM`, and a percentage OF THE SUBTOTAL cannot be expressed that way — the row depends on the total
and the total on the row. Two consequences are features rather than side effects: a **read never
recomputes**, so a SIGNED estimate cannot drift behind the client's back; and «backend = frontend»
became a comparison of one number instead of two formulas.

**The three-step pass** lives in `EstimateMath.recalculate` and nowhere else: ordinary lines →
percentages of a line / of a hand-typed sum → percentages of the TOTAL, all measured against the
same base (compounding them would make the answer depend on entry order).

⚠️ **`useEstimate.recomputeLines` in the PWA mirrors THAT PASS — change the two together.** This is
the second mirrored formula in the project (the first is `MeasurementCalc` ↔ `measurementCalc.ts`),
and the same rule applies. The client computes for DISPLAY only.

**A deleted base does not zero the line.** The FK is `ON DELETE SET NULL`; the service also sets
`base_detached`, the amount stays, and the row says «база видалена». Manual wins over automatic —
the rule `quantityManual` already follows in measurements.

**A PERCENT position's catalog price IS the percent.** V82 shipped nine of them into the live tiling
catalog («…(плюс % до м.кв.)» at 88, 33, 50, 76, 45…), so adding one from the catalog or through a
template maps `defaultPrice` → `quantity` and defaults the base to `POSITION`. Copying it into
`unitPrice` would produce «база 33 ₴», which is nonsense.

**In a duplicate the markup lands exactly once.** Base is a material or MANUAL (never marked up) →
the PERCENT itself is raised (20 % → 26 % at +30 %); base is a marked-up work, or TOTAL → the percent
is left alone because the base already grew. `source_unit_price` on such a row holds the ORIGINAL
PERCENT, and the economy query reaches the base to work out what the crew's sheet charged for it.
See [docs/iteration-percent-base.md](docs/iteration-percent-base.md).

### Estimate line order is explicit, and categories are a grouping of it

`PATCH /api/estimates/{id}/items/order` (`EstimateItemsOrderRequest` →
`EstimateService.reorderItems`) persists the master's chosen order, so positions can be dragged
inside a category **and between categories**. Signed estimates are rejected like every other
item write. `EstimatePdfService` renders the same grouping, so what the master arranged is what
the client receives — if you change ordering on one side, change it on the other or the PDF
silently disagrees with the app.

### One estimate from SEVERAL templates

`POST /api/projects/{id}/estimates/from-templates?ids=a,b,c` (`applyToProject` with a `List<UUID>`;
the single-id endpoint and overload stay). A real job is rarely one bundle — a bathroom is
«Санвузол» plus «Підлога плиткою».

Positions are concatenated in the order the templates were picked, then **de-duplicated by
lowercased name** — the same key the catalog price lookup uses, so a position that resolves to one
catalog row can only produce one line. First occurrence wins (keeps the earliest bundle's unit and
wording), `sortOrder` is renumbered across the whole result because each template counts its own
from 0. Every template is ownership-checked individually; the estimate limit is checked **once**,
because this creates one estimate however many bundles feed it.

Bundles overlap by design (every tiling bundle carries «Ґрунтівка поверхні» and the always-billed
four), so the dedup is not a nicety — without it the client sees the same work billed three times.
`useApplyTemplate` replays the identical rule offline; one uncached bundle out of several is a
refusal, not a partial apply.

### Album takeoff (`service/album/`) — built and tested, NOT yet exposed

**Read this first: no controller calls these services, and there is no job runner to host them.**
The calculators and the extractor are complete and covered by a fixture harness, but the feature
is unreachable from the product — don't describe it to the user as available, and don't wire it
to a request thread when you do expose it (see the timing note below). Tracked in
[docs/open-questions.md](docs/open-questions.md).

Recognition of a full project **album** (the designer's multi-sheet PDF set), split into two
INDEPENDENT product flows so a master only pays for what their trade needs:

- **`SurfaceTakeoffService`** — «площі по кімнатах», for painters / plasterers / tilers. Runs
  only the surface-relevant LLM passes (inventory + rooms/openings) then the deterministic
  **`RoomSurfaceCalc`**.
- **`ElectroTakeoffService`** — «електрика», for electricians. Runs only the electrical passes
  (inventory + points per floor + lighting/groups + heating/panel) then **`ElectroTakeoffCalc`**.

- **`CableJournalBuilder`** — a КАБЕЛЬНИЙ ЖУРНАЛ (ДСТУ Б А.2.4-24 Форма 6) built from the SAME
  device list the electrical takeoff counts: the data was already there, only the reading along the
  cable was missing. Pure Java, no LLM. **Both length columns are left empty on purpose** —
  ДСТУ Б А.2.4-21 §5.13 requires a «надбавка на вигини, повороти і відходи» and no Ukrainian norm
  quantifies it, so an invented figure would be copied straight into an order. Also unreachable:
  nothing calls it either.

Neither pays for the other's passes. Thanks to prompt caching, if both run on the same album
within the cache TTL the second reads the document from cache (~10% of input price).

**These are minutes-long (multiple Opus passes) — run them on an async job, never on a request
thread.** That is the one rule most likely to be broken by accident.

`RoomSurfaceCalc` deliberately mirrors the existing measurement domain (`MeasurementCalc`):
walls = Σ planes − Σ openings (w×h); reveals use the LINEAR default sides (left + right + top,
no bottom) i.e. `2·H + W` per opening. **A door shared by two rooms is deducted from both** —
each side of the wall loses the hole.

**The honesty contract is the point, not a nicety.** Nothing is guessed: a missing ceiling
height or an underivable perimeter leaves dependent values `null` with a note, and an opening
without both dimensions is skipped from the deduction and *reported*. The master sees WHAT is
missing instead of a confidently wrong number — the same principle as the import review's
«Джерела / Відсутнє» block.

Regression net: `AlbumFixtureHarnessTest` replays three real project albums from
`src/test/resources/album-fixtures/` through the calculators, so a formula change is caught
against known-good output without spending a single LLM call.

### Entities vs. records

- **Entities** (`User`, `RefreshToken`) — mutable JPA, Lombok
  `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`,
  `@EqualsAndHashCode(of = "id")` to avoid the lazy-loading pitfall.
  `@ToString(exclude = "passwordHash")` on `User`.
- **DTOs** — `record`s with `jakarta.validation` constraints on
  components. Don't mix Lombok with records.

## Gotchas

- **Spring Boot 4 split many auto-configs into per-feature modules** that
  starters do *not* pull transitively. So far we've hit:
  - **`spring-boot-flyway`** — required for Flyway auto-config. Without it
    Flyway silently does nothing, Hibernate then fails schema validation.
    Already declared in `build.gradle.kts`; don't remove it.
  - Test-slice annotations (`@WebMvcTest` etc.) are also gone — see
    *Testing* below.
  - **`spring-boot-testcontainers`** — required for `@ServiceConnection`; the
    starter does not pull it. Declared for the integration slice.
  - **`TestRestTemplate` is gone entirely** (not just moved). Spring 7 offers
    `org.springframework.test.web.servlet.client.RestTestClient`, but the
    integration slice deliberately uses the plain JDK `HttpClient` plus the
    `local.server.port` property instead — core API that survives the next
    reshuffle. `@LocalServerPort` moved packages too; `Environment` avoids it.
  Expect similar surprises for other auto-configs (`spring-boot-liquibase`,
  `spring-boot-jpa-test`, `spring-boot-jdbc-test`, ...). Symptom is
  usually "feature X silently doesn't run" or a NoClassDefFound.
- **Testcontainers 2.x renamed every module** to a `testcontainers-` prefix:
  `org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`.
  The old coordinates resolve to *no version at all* under Boot 4's BOM (the
  error shows an empty version, e.g. `Could not find …:junit-jupiter:`). The
  authoritative list is `testcontainers-bom` — read it from the Gradle cache
  rather than guessing.
- **Jackson 3 package**: Spring Boot 4 ships Jackson 3, whose package is
  `tools.jackson.*` (not `com.fasterxml.jackson.*`). When injecting
  `ObjectMapper`, use `import tools.jackson.databind.ObjectMapper;`. The
  `com.fasterxml.jackson.*` classes may still be on the classpath
  (transitively via `jjwt-jackson`) — they're for jjwt's internal use,
  don't pull them into application code.
- **Lombok + Java 21**: works via the Spring Boot–managed Lombok version.
  If you bump Java further, verify Lombok supports it.
- **JWT secret length**: HS256 requires ≥ 32 bytes (256 bits). Validated
  by `JwtProperties` (`@Size(min = 32)`). Generate with
  `openssl rand -base64 48`.
- **`-parameters` compile flag** is enabled — required for
  `@PathVariable`/`@RequestParam` without explicit names and for Spring 6+
  parameter-name discovery. Don't remove it from `build.gradle.kts`.
- **`open-in-view: false`** — JPA sessions don't extend into the view
  layer. If you need a lazy association in a controller, fetch it
  explicitly in the service.

## Testing

**Spring Boot 4 removed all test-slice annotations** — `@WebMvcTest`,
`@AutoConfigureMockMvc`, `@DataJpaTest`, etc. are gone from every jar.
Tests therefore use one of two patterns:

1. **Controller tests** (`AuthControllerTest`) — pure Mockito with
   `@ExtendWith(MockitoExtension.class)` + `@Mock` / `@InjectMocks` +
   `MockMvcBuilders.standaloneSetup(controller)`. No Spring context, no
   DB, instant startup. `GlobalExceptionHandler` is registered manually
   via `.setControllerAdvice(...)` so the error mapping is exercised too.
2. **Integration tests** — full `@SpringBootTest` against a **Testcontainers**
   PostgreSQL, via `IntegrationTestBase` (one shared container for the whole run;
   `@ActiveProfiles("test")`). Don't try H2; the Flyway migrations are PostgreSQL-specific.
   **Docker is therefore required to build** — these fail rather than skip, deliberately, so a
   green build means the migrations really ran.

   Use this slice for anything Mockito structurally cannot see: a migration actually applying,
   a `nativeQuery`/`@Query` running as real SQL, a CHECK constraint, lazy loading on a detached
   entity, or the security filter chain end to end. It exists because that blindness shipped
   real bugs (a `LazyInitializationException`, `lower(bytea)` in admin search, income that
   counted REJECTED estimates, and every unauthenticated request answering 403 instead of 401).

   Naming: `…IntegrationTest`, **not** `…IT` — discovery is name-based, and a class that
   silently never runs is worse than no test.

`application-test.yml` holds the test JWT secret and the settings that slice loads.

## Open-question log

[docs/open-questions.md](docs/open-questions.md) keeps deferred decisions
and known gaps across iterations — multi-instance state, billing,
PHOTO_REPORTS / AI_ASSISTANT, JWT key rotation, etc. There is a skill
at `.claude/skills/open-questions/SKILL.md` whose description says "use
at the start of every new iteration"; Claude should self-trigger it via
the Skill tool whenever the user kicks off a new step / feature, before
writing any code. It reads the file, classifies every `OPEN` item against
the upcoming work (in scope / adjacent / out of scope), and offers to
promote, close, or add items. Update statuses inline, don't rewrite the
file. When an item is closed, leave it in the file with `RESOLVED` +
a one-line note so the history is preserved.

## Conventions

- **Language**: all code, comments, log messages, SQL, YAML, and
  Markdown — **English only**. The user prefers chatting in Ukrainian but
  artifacts stay English.
- **Comments**: default to none. Write a one-liner only when the *why* is
  non-obvious (e.g. the body-cache wrapper rationale). Don't restate code.
- **Boundaries of change**: keep changes scoped. Bug fix ≠ refactor.
  Don't add abstractions for hypothetical future needs.
- **No backwards-compat shims** for code that hasn't shipped — this is a
  greenfield project; just change it.
- **Tests + green build before push (hard gate)**: every fix/change updates
  or adds tests, and the build must be green **before any push** — keep
  `master` green. **Claude cannot run Gradle in its sandbox** (the loopback
  socket it needs is blocked — `./gradlew` fails with "Unable to establish
  loopback connection", even with the sandbox disabled). So the gate is:
  Claude writes the tests, the **user runs `./gradlew build` locally and
  confirms green**, and only then does Claude push. If it's red, the user
  pastes the output and Claude fixes to green first. Never push on an
  unverified build.
- **PWA gate: run `npm run build`, not `tsc --noEmit`.** The PWA's real type
  check is `tsc -b` (project references, inside `npm run build`); a bare
  `tsc --noEmit` against the root config checks a *different* (looser) program
  and has already let two type errors through to a "green" report. `npx vite
  build` alone is not enough either — it does not type-check at all. Gate =
  `npm run build` + `npx vitest run`.
- **Offline/service-worker changes need `npm run test:e2e:offline`.** The normal
  e2e runs on the Vite dev server, where the service worker is DISABLED, so no
  dev-based test can see an SW regression (that is how the "Ви не в мережі"
  bug reached a master). `playwright.offline.config.ts` builds and serves the
  real production bundle; `e2e-offline/shell.spec.ts` needs no backend,
  `journey.spec.ts` does. When adding such a test, **verify it fails without the
  fix** — check the SOURCE, not `dist/sw.js` (minification renames class names,
  so grepping the bundle for `NavigationRoute` gives a false negative).

## Not implemented yet

These are intentional gaps to be aware of (don't claim they exist):

- No multi-instance rate-limit store (in-memory `ConcurrentHashMap`).
- No in-app reply to a client message — the inbox is one-way by design (see
  *Client messages* above), the master follows up out-of-band.
- No offline photo upload — every other daily flow authors offline, photos do not
  (a blob outbox is backlogged).
- No TEAM multi-user workspaces; TEAM is a plan, not a shared workspace yet.
- See [docs/open-questions.md](docs/open-questions.md) for the full list of
  deferred decisions.

**Recently shipped — do NOT describe these as missing** (this list has been wrong before):
integration tests (Testcontainers slice, see *Testing*), billing/payments (monobank checkout,
auto-renew, yearly tariff, PRO trial), password reset, client messages with attachments +
retention, album takeoff, offline-first authoring for everything except photos, estimate
duplication with a per-line markup, bulk deletion of estimate lines, the 15-day PRO trial with its
daily T-3…T-1 reminder, and multi-sheet photo recognition with `sheetKind` classification.
