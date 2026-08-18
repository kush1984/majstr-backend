# Majstr Backend — Claude guide

SaaS backend for Ukrainian contractors: auth, projects, clients, catalog, estimates (PDF + public
client portal with online signing), subscriptions/admin, email verification, web push, monitoring.
[README.md](README.md) has end-user setup and the public REST contract; this file is for contributors.

**This file is deliberately short (it loads every session).** The non-obvious architecture notes and
gotchas live in **[docs/architecture.md](docs/architecture.md)** — the index at the bottom links into
it. Read the relevant section there before working in that area. Per-feature history is in
`docs/iteration-*.md`; deferred decisions in [docs/open-questions.md](docs/open-questions.md).

## Stack (pinned)

- **Spring Boot 4.0.6** (Spring Framework 7, Jakarta EE 11) on **Java 21 (LTS)**
- **Gradle Kotlin DSL** — toolchain pinned to JDK 21 in `build.gradle.kts`
- **PostgreSQL 17** via `docker-compose.yml`, schema owned by **Flyway**
- **Spring Security 7**, stateless, JWT via **jjwt 0.12.x** (HS256)
- **Bucket4j 8.x** — login rate limiting
- **Jackson 3** — package is `tools.jackson.*`, not `com.fasterxml.jackson.*` (see index → Gotchas)
- **Lombok**, **springdoc-openapi 2.8.x**, **JUnit 5 + MockMvc**

Don't bump these without a clear reason — the combo is chosen for Spring Boot 4 / Spring 7 / Jakarta
EE 11 / Java 21 compatibility. (Java 21 over 25: Spring Boot 4's baseline is Java 17, the code uses no
22-25-only feature, and Java 25 had no reliable build image on Railway.)

## Common commands

```bash
docker compose up -d      # Postgres (needs .env with POSTGRES_PASSWORD)
./gradlew bootRun         # run the app (env vars from .env must be exported)
./gradlew test            # tests
./gradlew build           # full build with verification
```

JWT secret and DB credentials come from **env vars only** — never hardcode. The base
`application.yml` references `${JWT_SECRET}` with no default; startup fails fast if it isn't set.

## Package layout

```
com.majstr.backend
├── MajstrApplication.java     — @SpringBootApplication, registers @ConfigurationProperties
├── config/                    — SecurityConfig, OpenApiConfig, *Properties records
├── controller/                — REST endpoints (thin, delegate to services)
├── service/                   — business logic, @Transactional boundaries
│   ├── ai/                    — LLM provider plumbing (per-flow provider/model)
│   ├── album/                 — full-album takeoff: surfaces + electrical (built, NOT exposed)
│   ├── importer/              — estimate / receipt / catalog import from file or photo
│   └── measurement/           — measurement domain + project-document import
├── repository/                — Spring Data JPA interfaces
├── entity/                    — JPA entities (Lombok-annotated)
├── dto/                       — request/response records, validated with jakarta.validation
├── security/                  — JwtService, filters, UserPrincipal, body-cache wrapper
├── feature/                   — plan gates: Feature/Limit enums, PlanConfig, guards
├── billing/                   — monobank checkout, signatures, auto-renew
├── storage/                   — pluggable file storage (local / S3-R2)
├── email/  push/              — Resend HTTP client + templates / Web Push (VAPID)
├── bootstrap/  dev/           — startup seeding / @Profile("dev") only
└── exception/                 — typed exceptions + GlobalExceptionHandler
```

Layering rule: `controller → service → repository`. Entities never leave the service layer —
controllers return DTOs. `passwordHash` never appears in any response (`UserResponse` excludes it;
`User#toString` excludes it).

## Schema (Flyway)

`hibernate.ddl-auto: validate` — never express schema changes in entity annotations. Add a new
`V<N>__<desc>.sql` under `src/main/resources/db/migration/`; **check the highest number first** (`ls`
+ sort numerically — latest is **V107**). **Never edit an applied migration** — Flyway checksums it and
a changed file fails startup. New `Trade` enum constant → migration to extend the `user_trades` CHECK
(a master-invented trade instead goes in `user_trade` — no migration needed, see index below).
(Detail + the "testing a DATA migration" pattern: index → Flyway / catalog.)

## Testing

Spring Boot 4 removed all test-slice annotations (`@WebMvcTest`, `@DataJpaTest`, …). Two patterns:

1. **Controller tests** — pure Mockito (`@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`
   + `MockMvcBuilders.standaloneSetup(controller)`, register `GlobalExceptionHandler` via
   `.setControllerAdvice(...)`). No Spring context, no DB.
2. **Integration tests** — full `@SpringBootTest` against a **Testcontainers** Postgres via
   `IntegrationTestBase` (`@ActiveProfiles("test")`). **Docker is required to build** (they fail, not
   skip). Use for anything Mockito can't see: a migration applying, a `nativeQuery`/`@Query`, a CHECK
   constraint, lazy loading on a detached entity, the security filter chain end to end. Name
   `…IntegrationTest`, **not** `…IT` — discovery is name-based.

`application-test.yml` holds the test JWT secret and the settings that slice loads.

## Conventions

- **Language**: all code, comments, logs, SQL, YAML, Markdown — **English only**. The user chats in
  Ukrainian but artifacts stay English.
- **Comments**: default to none; a one-liner only when the *why* is non-obvious. Don't restate code.
- **Boundaries of change**: keep changes scoped. Bug fix ≠ refactor. No abstractions for hypothetical
  future needs. **No backwards-compat shims** for code that hasn't shipped — greenfield, just change it.
- **Tests + green build before push (hard gate)**: every change updates/adds tests, and the build must
  be green **before any push** — keep `master` green. **Claude cannot run Gradle in its sandbox**
  (loopback socket blocked). So: Claude writes the tests, **the user runs `./gradlew build` locally and
  confirms green**, then Claude pushes. Red → user pastes output, Claude fixes to green first.
- **PWA gate = MIRROR CI's `verify` job, in order** (`majstr-pwa/.github/workflows/ci.yml`):
  `npm run lint` → `npx tsc -b` → `npm run typecheck:tests` → `npx vitest run` → `npx vite build`.
  Two steps are easy to forget and each has reddened CI on its own: **`npm run lint`**
  (`eslint --max-warnings 0` — a stray `!`/`as` is a HARD error) and **`npm run typecheck:tests`**
  (a SEPARATE, stricter tsconfig that type-checks the TEST files — `npm run build`'s `tsc -b` does
  **not**). `tsc --noEmit` checks a looser program and `vite build` doesn't type-check at all —
  neither substitutes. "build + vitest were green" is NOT the gate; lint + test-typecheck are.
- **Offline/service-worker changes also need `npm run test:e2e:offline`** (CI runs the shell spec).
  The normal e2e runs on the Vite dev server where the SW is DISABLED, so no dev-based test sees an
  SW regression. When adding one, verify it fails without the fix — check the SOURCE, not the
  minified `dist/sw.js`.

## Not implemented yet (don't claim these exist)

- No multi-instance rate-limit store (in-memory `ConcurrentHashMap`).
- No in-app reply to a client message — the inbox is one-way by design; the master follows up out-of-band.
- No offline photo upload — every other daily flow authors offline, photos do not (blob outbox backlogged).
- No TEAM multi-user workspaces; TEAM is a plan, not a shared workspace yet.
- Full list of deferred decisions: [docs/open-questions.md](docs/open-questions.md).

**Recently shipped — do NOT describe these as missing** (this list has been wrong before): integration
tests (Testcontainers slice), billing/payments (monobank checkout, auto-renew, yearly tariff, PRO
trial), password reset, client messages with attachments + retention, album takeoff (built, not
exposed), offline-first authoring (everything except photos), estimate duplication with a per-line
markup, bulk line deletion, the 30-day PRO trial (was 15, then 5) with T-3…T-1 reminders, multi-sheet photo recognition
with `sheetKind`, custom (master-invented) trades alongside the system `Trade` enum, object-level
payments (`project_payment`, V93 — replaced the old per-estimate deposit) with a FREE-visible
payment schedule + PRO-gated profit/expense internals, a client-portal payments card, shared
(client-visible) receipt photos, community prices (`price_insight_candidate`, V94 — a weekly
job crowd-medians masters' own estimate lines into admin-reviewed price-drift/new-position
candidates, notice queue on accept), and the economy-rework iteration (V95 —
`superseded_by_estimate_id`; signed estimates live only in the Економіка tab now, are clickable
into the read-only estimate view, get a Σ summary panel; the PRO internals block simplified to
`Прибуток`/`Витрати`; the payment dialog split into plan/mark-received/quick-received flows).

## Open-question log

[docs/open-questions.md](docs/open-questions.md) keeps deferred decisions and known gaps. There is a
skill at `.claude/skills/open-questions/SKILL.md` ("use at the start of every new iteration") — trigger
it via the Skill tool whenever the user kicks off a new step/feature, **before writing code**. It reads
the file, classifies every `OPEN` item against the upcoming work, and offers to promote/close/add.
Update statuses inline; a closed item stays with `RESOLVED` + a one-line note. The skill also reminds
you to keep the step's own `docs/iteration-*.md` updated.

## Architecture index

One line per non-obvious fact; **full detail in [docs/architecture.md](docs/architecture.md)** — read
the section before working in that area.

- **Auth flow** — register/login/refresh/logout/me; `LoginRateLimitFilter` runs before `JwtAuthenticationFilter`.
- **FREE object cap counts LIFETIME creations, not live rows (V107)** — `users.lifetime_project_count` (seeded from the current object count) is the basis, so **deleting a completed/cancelled object can't slip past the cap** (anti-abuse). `LimitService.reserveProjectSlot` locks the user, checks `lifetime >= MAX_PROJECTS`, and increments in the same tx (a delete never decrements); the idempotency check in `ProjectService.create` runs first so an offline replay never re-reserves. `PlanLimitsResponse.projectsUsed` (= lifetime) is what the PWA gate/banner read — NOT the live list length. Permanent delete (`useDeleteProject`, cascade) is offered from the object-row ⋮ **only on a terminal object**, behind a strong destructive `ConfirmDialog`.
- **Refresh tokens** — hashed at rest (SHA-256), rotated (old revoked on use), swept daily; **the PWA must single-flight `/refresh`** or a burst of 401s self-logs-out.
- **Email verification is soft** — `@Async` fail-soft; only `POST /estimates/{id}/share` is gated (403 `EMAIL_NOT_VERIFIED`); email editable only while unverified.
- **Web push** — VAPID, `@Async`, env-gated fail-soft; `deliver()` MUST pass `Encoding.AES128GCM` (legacy `aesgcm` → FCM 403).
- **Client portal is project-level** — `project_share_links` token + `estimates.portal_visible`; legacy per-estimate `?t=` links stay valid. A second visibility toggle, `payments_visible` (V93, default false), independently gates the portal's payments card.
- **Client messages** — one-way inbox (renamed from "questions", V74); entity field `read` vs view JSON key `isRead`; message-link + file attachments + 6-month retention (V75-77).
- **Login rate limit** — `CachedBodyHttpServletRequest` (re-readable body); key `email|ip`; in-memory (single-node only).
- **Signed estimates are immutable** — mutate/delete → 409 `ESTIMATE_SIGNED`; owner-only `reopen`; `@Version` optimistic lock (V23). `reopen` is fully live server-side but **hidden from the UI** (`REOPEN_ENABLED = false`, independently declared in both PWA locations that used to show it) — the master's call, code kept for a possible return. **A signed estimate lives only in the Економіка tab** (economy-rework iteration) — the Кошторис tab shows DRAFT/SENT/REJECTED only (`ProjectDetailPage`'s `activeList`); `EstimateService.reopen`'s mechanics are factored into a shared `applyReopen(estimate, reopenedBy)` (owner-facing `reopen` still uses it). **A signed parent is NOT auto-reopened when its duplicate is signed** (acts iteration, was the old economy-rework behavior): `PublicEstimateService.doSign` now sets `parent.countInEconomy = false` (keeps the signature — it's a historical fact, and once work acts reference a SIGNED estimate, reopening it could pull it out from under an act already sent) and stamps `estimates.superseded_by_estimate_id` (V95) with the duplicate's id. The parent stays a SIGNED panel in Економіка, just uncounted in the summary; the supersede banner is suppressed for DRAFT rows in the PWA (only legacy already-reopened drafts still carry the flag there). The flag clears on edit (via `requireNotSigned`, the one guard all 8 write paths share), re-sign, or an explicit dismiss endpoint.
- **Work acts (`work_act`/`work_act_item`, V104)** — a document built from a signed estimate's positions, signed separately (acts iteration). All line fields are **frozen copies** (name/category/unit/price + `cumulative_before`); `estimate_item_id`/`estimate_id` are `ON DELETE SET NULL` so a sent act reads identically after the estimate is edited/deleted. Invariants in `WorkActService`: **one open (DRAFT/SENT) act per object** (409 `WORK_ACT_OPEN`), a **FINAL** act closes it (409 `WORK_ACT_FINAL_EXISTS`), **signed = immutable** (`requireNotSigned`, 409 `WORK_ACT_SIGNED`), delete only DRAFT/REJECTED. **Numbering is continuous per master** (never reset per year — PLAIN «7» would else collide under `UNIQUE(user_id, number)`); `create()` is non-`@Transactional` and retries `WorkActCreator.attempt` (public, own tx) on a lost number race; `WorkActResponseFactory` builds the response inside that tx. **Progress is never denormalized** — Σ quantity over SIGNED acts (`sumSignedQuantitiesByEstimateItem`) feeds both the progress endpoint and each new act's frozen `cumulative_before`; `exceedsEstimate` is computed live, the master decides. **Signing with additional (off-estimate) lines** creates a SIGNED, counted, non-shared `Estimate.kind = ADDENDUM` («Додаткові роботи до акта № N», amounts via `EstimateMath`) in the same tx so «Прийнято актами» never exceeds «За договором»; ADDENDUM is filtered out of `listForProject` (Кошториси tab + pickers). `Feature.WORK_ACTS` (temp-FREE, like OBJECT_ECONOMY/MEASUREMENTS). **The estimate read path surfaces progress too**: `EstimateItemResponse.closedByActs` (nullable; second `from(item, closed)` factory, plain `from(item)`→null so no fan-out) is a per-line Σ over SIGNED acts, built in `EstimateService.toResponse` **only for a SIGNED estimate** (one `sumSignedQuantitiesByEstimateItem`, no N+1) — drives the estimate board's «✓ закрито»/«done/total» chip; a DRAFT act never colours a line (same rule as the running total). PWA act UI is the `/acts/:id` `ActEditorPage` (tick = full remainder, exceeds→convert-to-additional), `ActsSection` (blocked when an act is open or a FINAL exists — `actCreateBlock` mirrors `WorkActCreator`), and «Згенерувати акт» on the economy panel ⋮.
- **Act portal (`ShareLinkKind.ACT`, `?a=`, V105)** — a **fourth** share-link kind, deliberately NOT folded into read-only ECONOMY (which must never let the client sign). One link = one act: `project_share_links.work_act_id` (FK, invariant CHECK `(kind='ACT')=(work_act_id IS NOT NULL)`). Owner side is `ProjectPortalService` act trio (`actState`/`updateAct`/`sendActEmail`; publish flips DRAFT→SENT + mints the act's link) on `WorkActController` `.../{id}/share`; public side is `PublicActPortalController` → `PublicActPortalService` (`/api/public/act/{token}` view/sign/question/pdf). **Defense-in-depth: only SENT/SIGNED served** (a DRAFT is 404 even with a valid token — the `economyVisible` lesson). Sign records signer name/phone/ip/UA, stamps SIGNED, computes **`doc_hash` = SHA-256 of the canonical (unstamped) PDF** (`WorkActPdfService.PdfModel.docHash` re-stamps it on download), pushes the master + emails the client a PDF copy (`EmailService.sendSignedActCopyEmail`, Resend attachment) — both fail-soft. Honest wording «Підтвердити приймання робіт», never a legal-equivalence claim. `PublicActView` is a fresh client-safe DTO (in `PublicEstimateIsolationTest` roots). `static/portal/index.html` has a `?a=` `renderAct` branch (before `adaptLegacy`).
- **Works axis in economy (FREE-visible)** — `ObjectEconomyResponse.acts` (`ObjectEconomyActsResponse(contracted, acceptedByActs, received)`), computed **unconditionally** (not behind `internals`) in `ObjectExpenseService.economy`: `contracted = sumIncomeCounted` (== `payments.contractedTotal`), `acceptedByActs = sumSignedActLineTotals`, `received = PaymentReceiptRepository.sumByProjectId`. PWA `ActsAxis` is a PaymentStrip twin; the balance line (`acceptedByActs − received`) flips «Невідпрацьований аванс»/«Заборгованість замовника»/«Розрахунки збігаються» by sign; `(i)`-popups (`acceptedInfo` interpolating the not-yet-accepted remainder, `clientDebtInfo`, `unearnedAdvanceInfo`) name the two different debts. **INVARIANT — numerator and denominator are counted over ONE estimate set** (acts-fix): both `sumSignedActLineTotals` and `sumIncomeCounted` count only SIGNED + `count_in_economy = true` estimates, plus off-estimate additional lines (`estimate_id IS NULL` — their ADDENDUM is itself counted). So «Прийнято актами» ⊆ «За договором», never past 100 %. Enforced three ways: `WorkActService.progress` skips `!countInEconomy` estimates; a write guard (`requireCountedEstimateLines`, 400 `WORK_ACT_ESTIMATE_EXCLUDED`) rejects an excluded-estimate line; and **both** sign paths create the ADDENDUM via the shared `ActAddendumCreator` (the portal path used to skip it — acts-fix). Don't drop the `IS NULL` branch or the `count_in_economy` filter from either query without breaking this.
- **Act «ДОВІДКОВО» reference block (acts-fix, V106)** — three object-wide money rows (виконано з початку / за кошторисами / залишок), NOT a per-line table; sourced from the SAME queries as the works axis via the shared `ActCumulativeCalculator`, so PDF and app never disagree. **Default OFF** (`show_cumulative` V106 default `false` + data-cleared; `WorkActCreator`/PWA `useNewAct` both send false) and shown only **from the 2nd act** (`existsByProjectIdAndStatusAndIdNot(..., SIGNED, ...)`). The figures are live and object-wide, so the block is **excluded from the canonical (hashed) render** (`PdfModel.cumulative = null` at hash time) — a later signing must never invalidate an earlier act's stored `doc_hash`; the block is thus a live reference, deliberately outside tamper-evidence.
- **Spring Security 7** — lambda DSL only; `PUBLIC_PATHS`; **`RestAuthenticationEntryPoint` keeps 401 vs 403 disjoint**.
- **Localization** — `messages.properties` is **Ukrainian** (base/fallback); exceptions stay English, advice maps type→bundle key (or key passed at throw site); filters use `requestLocale`.
- **Error shape** — `ErrorResponse`; **401 (re-auth) and 403 (not allowed) are disjoint**; client-disconnect → `null`, no Sentry; quiet 404 for scanner probes; quiet 406 (`HttpMediaTypeNotAcceptableException`) for a `produces`-typed endpoint (PDF, etc.) hit by a client whose `Accept` excludes both it and `*/*` — real browsers always send `*/*;q=0.8`, so this is almost always a link-preview bot unfurling a shared URL, not an app fault; do NOT widen the disconnect walk to "any IOException".
- **Offline idempotent creates** — `X-Entity-Uuid` header, idempotency **before** limit checks; wired on clients/projects/estimates/items; new offline entity → follow the pattern.
- **File storage** — pluggable local/S3-R2 via `StorageConfig` (`app.storage.kind`); reads always via `FileController`; keys identical across backends.
- **AI flows (`service/ai/`)** — per-flow provider+model; `AiExtractors.forFlow(...)` resolved at startup + logged; a typo disables ONE flow (503); an empty config value = absent.
- **Estimate import (Excel/photo)** — `import/parse|commit`, `Feature.ESTIMATE_IMPORT`; raw-HTTP extractor; failure → 503 `AI_UNAVAILABLE` (retries transient 429/5xx/529); uploaded file discarded.
- **`sheetKind`** — `PRINTED_PLAN` vs `HAND_DRAWN`; **default leans PRINTED_PLAN** (incl. missing field); passport (the one sheet in metres) rules gated behind ≥2 evidence signals.
- **Duplicate with markup** — `source_unit_price` on the **line** (V85), not one estimate percent; bulk delete cascades parent→duplicate only. The economy no longer derives the crew's margin from this automatically (economy-rework iteration removed `sumWorksCounted`'s difference formula) — `Прибуток` is now `contracted − Σ object_expense`, so a бригадир master must log what he pays the crew as a LABOR expense for the margin to show correctly; `source_unit_price` still drives the per-panel/per-estimate works/materials figures (unaffected) and the PDF/portal display.
- **Consolidation freezes «%» lines, and V92 makes that honest** — `copyForConsolidation` freezes the amount (correct: re-measuring against the merged subtotal would silently give an unsigned discount) but used to lose provenance, reading «10 % від 3450» with no hint what 3450 was. `estimate_items.base_origin_label` is a **snapshot** (not FK, same as `source_unit_price`/`source_item_id`) built from the line's kind/base BEFORE it's overwritten to MANUAL — «−15% від робіт · кошторис «Х»». PWA: `percentLabel` shows it verbatim when set; `ItemForm` hides the live POSITION/TOTAL picker for a frozen line (there's nothing left to point at) and lets percent + base sum be edited directly instead.
- **Consolidated / receipt import / object photos** — `consolidate` (new DRAFT); `RECEIPT_IMPORT` (adds to open estimate, no catalog upsert); photos stream only via authenticated owner/portal-token endpoints, never `/api/files/**`. Both photo sources (MANUAL progress, RECEIPT) can now be toggled `SHARED` — a shared RECEIPT appears in the portal's Чеки section and the PDF's «ЧЕКИ» appendix, same path as a shared progress photo. `PhotosSection` splits into "Фото прогресу" / "Чеки"; a receipt can be uploaded with no `estimate_id` (folder-only, no line-item parsing).
- **Object-level payments — PLAN/FACT split (`project_payment` V93 + `payment_receipt` V100)** — belong to the **project**, not an estimate (an object usually has several estimates but one money arrangement); absorbed the old `Estimate.depositAmount` (data-migrated, column left in schema unread). `ProjectPayment` is PLAN-only now (`amount`/`dueDate`/`nextStage`/`purpose`); its `paidAmount`/`paidAt` are **deprecated** (V100, unread, drop deferred to open-questions) — FACT lives in `PaymentReceipt` instead, so one plan stage can be closed by several partial receipts (`plan_payment_id` nullable, `ON DELETE SET NULL` — a receipt survives its plan stage being deleted). Status is **derived, never stored**, now a pure function over an injected received amount (`ProjectPayment.status(LocalDate today, BigDecimal received)` — PARTIAL beats OVERDUE beats PLANNED). `due_date`/`next_stage` are framed as a condition to unblock the next stage, never a debt reminder — same wording rule applies to any UI or copy touching them. `PaymentService.addReceipt` is the one path money enters through (mark-received and one-step "already received" both route through it); an overpayment requires the caller to say how to resolve it (`PaymentOverflowResolution`: TRANSFER the surplus to the next open stage / INCREASE the plan amount / RESERVE as an over-received stage) — the backend never decides on its own. `PaymentService.requireEconomy` gates every mutation (plan CRUD, split, and now every receipt mutation) behind `Feature.OBJECT_ECONOMY` (economy-polish iteration, 2026-08-09) — a FREE master can view but not create/edit payments; `list`/`summary` stay ungated, owner-scoped only.
- **Economy gate is field-level, not endpoint-level** — `ObjectEconomyResponse` is `{estimates[], payments{}, internals|null}`; `estimates` (one panel per SIGNED estimate, regardless of `count_in_economy`; panels also carry `markup`/`discount` alongside `works`/`materials`/`total` since the economy-rework iteration, feeding a Σ summary panel above Платежі on the PWA side) and `payments` (contracted/received/remaining/schedule) compute unconditionally, `internals` is `null` for FREE via a **soft** `featureGuard.isEnabled` check. This is new: every other PRO gate in this codebase still hard-403s via `requireFeature` at the top of the method — the expense-journal CRUD on the same controller still does. Don't assume one gating shape generalizes to the other. **`internals` is deliberately just `{expenses, profit}`** (economy-rework iteration cut the old works/materials/cashBalance/spentReceipts/spentManual split) — `expenses` is `ObjectExpenseRepository.sumAll` (every category/source), `profit = payments.contractedTotal() − expenses`, so it can never disagree with the FREE-visible contracted figure.
- **Catalog is reference data, estimate lines are snapshots** — templates copied BY VALUE; `estimate_items` carry their own name/unit/price with **no FK** (the client signed those numbers); a catalog rewrite must notice the master (`catalog_update_notices`); default catalog is works-only (V81).
- **`catalog_update_notices` is a queue, not a slot (V94)** — one row per notice-worthy event, `kind` (`COUNT`/`PRICE_DRIFT`) discriminates the shape (CHECK-enforced: each kind carries only its own fields); dismiss/accept are id-scoped, never "the" implicit notice. A master's own catalog price changes ONLY on `accept`, and only if it still equals the notice's `old_price` — never a silent overwrite.
- **Community prices (`price_insight_candidate`, V94)** — weekly job aggregates a two-level median off masters' own ESTIMATE lines (not their catalog, which drifts less): per-master median in SQL (`EstimateItemRepository.aggregatePerMasterWorkPrices`, `percentile_cont`), IQR-trim + cross-master median in Java (`PriceInsightMath`, no Spring dependency), **min 3 masters** survive the trim. `PRICE_DRIFT` (key matches a default) vs `NEW_POSITION` (doesn't — merges into the existing `AdminCatalogInsightsService.newPositions()`, not a new screen). Admin `apply` only updates the shared `catalog_templates` row + queues notices — a master's own price never changes until they personally accept.
- **Custom trades (V91)** — `user_trade` (id/name/sort per master) has no reference catalog by design; `catalog_items`/`estimate_templates` (own only) get a nullable `custom_trade_id` FK, `ON DELETE SET NULL`; invariant on both tables: `custom_trade_id IS NULL OR trade = 'OTHER'` (a custom-trade row is always OTHER underneath, never a bare system trade) — deleting the custom trade drops it to plain "Інше" for free, no app-level UPDATE. `CatalogTemplate`/`TemplateTradeOverride` untouched — a system default can never carry one (DB CHECK also pins `is_default = false`). PWA: `TradeFilterChips`/`tradeMatches` key on `TradeKey = Trade | \`custom:${id}\`` and must exclude custom rows from the plain "Інше" chip even though `trade` reads OTHER for both.
- **«%» is a share OF something (V88)** — base `POSITION`/`TOTAL` (per-type: works vs materials), `MANUAL` retired; **`line_total` is STORED** (server-authored, never from a request); only a `TOTAL` percent may be negative (+/− toggle, V89).
- **Mirrored formulas — change BOTH sides together** — `EstimateMath.recalculate` ↔ `useEstimate.recomputeLines` (percent pass); `MeasurementCalc` ↔ `measurementCalc.ts`.
- **Estimate line order** — explicit reorder; `EstimatePdfService` mirrors the grouping (change both or the PDF disagrees).
- **One estimate from several templates** — concat + dedup by lowercased name; estimate limit checked once.
- **Album takeoff (`service/album/`)** — **built + tested but NOT exposed**; don't call it available; when wired, run async (minutes-long), never on a request thread.
- **Entities vs records** — entities are Lombok JPA (`@EqualsAndHashCode(of="id")`); DTOs are records; don't mix Lombok with records.
- **Gotchas** — Jackson 3 = `tools.jackson.*`; SB4 splits auto-configs (`spring-boot-flyway`, `spring-boot-testcontainers` — declare explicitly); `TestRestTemplate` gone (use JDK `HttpClient`); Testcontainers 2.x renamed to `testcontainers-*`; JWT ≥ 32 bytes; keep the `-parameters` flag; `open-in-view: false`.
