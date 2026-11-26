# Majstr Backend — Claude guide

SaaS backend for Ukrainian contractors (estimates, client portal with signing, acts, money, catalog,
Telegram bot). **Kept minimal — it loads every session.** Full rules and reasoning:
[docs/contributing.md](docs/contributing.md) (stack, commands, layout, schema, testing, conventions);
per-bullet long forms: [docs/architecture-index-detail.md](docs/architecture-index-detail.md) (paired by
title — read the twin before changing what a bullet guards); per-area narrative:
[docs/architecture.md](docs/architecture.md); history `docs/iteration-*.md`; deferred decisions
[docs/open-questions.md](docs/open-questions.md).

## Hard rules

- **Stack pinned:** Spring Boot 4 / Java 21 / PostgreSQL 17 + Flyway / Jackson 3 (`tools.jackson.*`). Don't bump without reason.
- **Secrets from env only** (`JWT_SECRET` has no default). **English only** in code, comments, SQL, docs.
- **Layering** `controller → service → repository`; controllers return DTOs; `passwordHash` never leaves.
- **Schema = Flyway only** (`ddl-auto: validate`). New `V<N>__…sql`, **latest is V225** (V183, V185 skipped);
  never edit an applied migration; a self-check `RAISE EXCEPTION`s only about its own work, else `WARNING`.
- **Tests + green `./gradlew build` before any push** (Claude runs it; Docker must be up). Controller tests =
  Mockito standalone; DB/filters = `…IntegrationTest` on Testcontainers.
- **PWA gate = CI's verify job:** `npm run lint` → `npx tsc -b` → `npm run typecheck:tests` → `npx vitest run`
  → `npx vite build` → `npm run test:e2e:offline:shell`.
- **Catalog names the work, never a brand.** **Crew prices never reach the client.** UI: what the row shows,
  the row edits.
- **Scope:** bug fix ≠ refactor; no shims for unshipped code. Never commit/push without the owner's OK.
- **After every behaviour-changing push:** update the iteration doc, `C:\Work\SPEC.md`, `C:\Work\PROMPTS.md`,
  open-questions and the tester spec; run the `open-questions` skill before a new step.

## Not implemented (don't claim)

Multi-instance rate limits; in-app reply to a client; general offline photo upload; TEAM shared workspaces.

## Architecture index

One line per invariant; the reasoning is in the twin in docs/architecture-index-detail.md.

- **Auth flow** — register/login/refresh/logout/me; `LoginRateLimitFilter` precedes `JwtAuthenticationFilter`.
- **Google sign-in (V210)** — `google_sub`, else LINKS the email (unverified ⇒ password+sessions dropped).
- **FREE object cap counts LIFETIME creations (V107)** — `users.lifetime_project_count`; `LimitService.reserveProjectSlot` locks + increments, a delete never decrements; `projectsUsed` = lifetime.
- **Refresh tokens** — hashed at rest (SHA-256), rotated, swept daily; **the PWA must single-flight `/refresh`** or a burst of 401s self-logs-out.
- **Email verification is soft** — `@Async` fail-soft; email editable only while unverified. `SendReadiness`: the master's own send needs a NAME, our letter also a verified email.
- **Web push** — VAPID, `@Async`, env-gated fail-soft; `deliver()` MUST pass `Encoding.AES128GCM` (legacy `aesgcm` → FCM 403).
- **Client portal is project-level** — `project_share_links` + `estimates.portal_visible`; the ECONOMY portal has NO picker (SIGNED ∧ counted + ADDENDUM); `?t=` is the single-estimate path, not legacy; previews `?v=`/`?vp=` are a stateless 10-min HMAC pass.
- **Portal rate limit = three greedy buckets (P0, iteration-p0-meetup)** — per IP+link reads 120/min, writes 10/min, a per-IP ceiling 300; `/api/public/**` answers in Ukrainian.
- **DRAFT → SENT only when the link LEAVES the app (P1, iteration-p1-meetup)** — minting or publishing changes no status; `…/mark-sent` after a real share; email marks itself; acts still flip on publish.
- **Client messages** — one-way inbox (renamed from "questions", V74); entity field `read` vs view JSON key `isRead`; message-link + attachments + 6-month retention (V75-77).
- **Login rate limit** — `CachedBodyHttpServletRequest`, key `email|ip`, in-memory single-node; all limiters share `BucketRegistry<K>` (idle entries dropped).
- **Signed estimates are immutable** — mutate/delete → 409 `ESTIMATE_SIGNED`; `requireNotSigned` guards all write paths; signing a duplicate un-counts the parent (`superseded_by_estimate_id`, V95), never reopens it; the owner's «Перевідкрити для змін» is back in the UI (2026-11-23) — client re-signs, payments stay on the object, any act not REJECTED refuses it; `ReopenGuard` is the one rule, also sent as `reopenBlock` on the estimate and its economy panel so the app says why before the tap.
- **Work acts (`work_act`/`work_act_item`, V104)** — frozen line copies; one open act per object, a FINAL act closes it; numbering continuous per master (`WorkActCreator` retries); progress = Σ SIGNED acts; off-estimate lines → ADDENDUM; `persist()` is the one door.
- **Act portal (`ShareLinkKind.ACT`, `?a=`, V105)** — one link = one act; only SENT/SIGNED served; signing freezes a snapshot (`doc_hash`); wording «Підтвердити приймання робіт».
- **Works axis in economy (FREE-visible)** — «Прийнято актами» ⊆ «За договором»: both sums take SIGNED ∧ `count_in_economy` estimates PLUS `estimate_id IS NULL` lines — never drop either filter.
- **The economy progress strip is ONE component in THREE places (green-progress-strip)** — colour is a position (gradient spans the track via `background-size`); `ProgressStrip.tsx` and the portal's copy are a mirrored pair.
- **Act «ДОВІДКОВО» reference block (V106)** — default OFF, from the 2nd act, computed as of the act's signature and frozen into its snapshot.
- **Acts follow-ups (acts-improvement, V108 `work_act.title`)** — `title` frozen on sign; status moves only SENT→DRAFT, SENT→REJECTED, REJECTED→DRAFT (`PATCH …/status`).
- **Act receipts — «Чеки та рахунки» (V110 + V111)** — photo mandatory; writes behind `requireNotSigned`; receipts join the ADDENDUM and `acceptedByActs`; keep every `AND r.itemized = false` (legacy, load-bearing); a new LLM verb joins `api/client.ts`'s long timeout.
- **Fiscal QR fast path (`service/fiscal/`)** — `FiscalQrService.read` degrades, never fails; everything from a QR is FREE; build URIs with `.encode().build().toUri()`.
- **Receipts arrive as a BATCH — saved first, priced later (receipts-batch)** — amount 0 is legal; `ActReceiptCompleteness.requireAllPriced` on all three sign/publish doors; «Чек №N» numbered server-side in the creator's lock; `findByWorkActIdNewestFirst` everywhere.
- **Partial returns on an act receipt (V115)** — `amount` is the paper; everything downstream reads `billedAmount() = amount − returnedAmount`; `work_act.advance_offset` is document-only.
- **Admin funnel: «Віддали клієнту» = `shared` ∪ `pdfMade` (V196)** — `ClientHandoffs` is the one computation; every step filters `role = USER`.
- **By-source funnel + first-touch UTM (V114)** — Σ over sources == the aggregate (`SourceBreakdownIntegrationTest`); UTM stamped once, NULL is a value; search traffic reads `first_referrer_host`/`first_entry_path`.
- **The training sample «Приклад: …» (V197)** — `projects.sample`, one per owner, outside the cap, read by no figure; acts «П-N»; marked «Навчальний приклад Majstr» on client surfaces.
- **Where a master works (V219, iteration-regions)** — `users.region` (24 oblasts + Kyiv city, CHECK-listed); its own `PUT /api/profile/region`, so no profile form blanks it; asked on «Чим займаєтесь?» and the home card.
- **Prices by oblast, step 1 (V222 + V223, plan-regional-prices)** — `RegionalPriceService` (weekly, own tx, beside V94) writes `regional_price` (position × oblast, 3+ of its masters), `regional_baseline` (one vote per OBLAST, 3+ oblasts — not V94's one per master) and `regional_index` (oblast factor vs that baseline, 3+ masters) from the SAME per-master rows — estimate lines (not REJECTED, any status, estimate MADE in the last 6 months) PLUS catalog rows with `own_price` (V225: typed, edited or imported; a library price never counts), an old library wording folded into the one it became (`CatalogRenames` over `catalog_renamed_position`, both runs); admin-only («🗺 Ціни по областях»); the full recipe is the plan's «How it is computed» — change both together.
- **The market price hint (plan-regional-prices, 2026-11-25)** — `GET /api/catalog/price-hint` (`PriceHintService`): his oblast's median → country baseline × his oblast factor («орієнтовно», to 10 ₴) → the country baseline → the library price (one «по країні» with «Ціни по областях»); matched by `CatalogNameKey` only (no fuzzy) — an old library wording with nothing of its own takes the new wording's figures (`CatalogRenames`) — works only, FREE, 204 = nothing; PWA `PriceHint` under both price fields, «Порівняти» = a Google `site:` search per site (`COMPARE_SITES`: rabotniki, kabanchik, budver, OLX) under EVERY work, figure or not — never fetched by us; «Ціни по областях» (`GET …/regional-prices`, PRO `REGIONAL_PRICES`) = the same levels per catalog work, rows joined by item id.
- **Masters rate Majstr itself (V218, iteration-app-reviews)** — `app_review` (1–5, text, `publish_consent`, NEW/PUBLISHED/HIDDEN); a CHECK refuses PUBLISHED without consent, and the service never keeps consent below 4 stars; the home card asks only via `GET /api/app-reviews/prompt` (success first, not week one, 90-day pause); majstr.pro shows stars, the words if any (stars-only too), «Олена К.» and months in Majstr — no trades, no estimate counts (owner, 2026-11-24).
- **Anonymous visit statistics (V187, iteration-visit-stats)** — cookieless beacon → `POST /api/public/visit` (204); no IP stored (daily-salted hash); each visitor-day counted once.
- **PostHog is PWA-only, and the boundary is a rule (iteration-posthog)** — money and state are the backend's; PostHog gets only what it doesn't write; no personal data; `reset()` on logout; replay→person join by id equality.
- **Admin broadcasts — fixed segments, never a query (V180)** — one of six segments per master (`AudienceRules.segmentOf`); FREE + USER + not opted out; the send recomputes and intersects; unsubscribe footer by `BroadcastLetter`, GET never unsubscribes.
- **The bot never decides money, an object or an estimate (majster-bot, V211)** — stored first, filed through each record's own service under the item's lock; money and forwarded messages wait for «Записати»; a line only into the one DRAFT; voice (V213) PRO + 10 FREE.
- **One object without a subscription (V214, iteration-one-off-object)** — a 149 ₴ payment (`kind = OBJECT`) adds a credit via the webhook only; `reserveProjectSlot` spends it AT the FREE cap and the object is stamped `paid_at`; PRO's per-object caps + logo (`PaidObject.branded`), never AI.
- **The client pays the master from the portal, never through us (V217, iteration-client-pay)** — `payTo` (card / IBAN + NBU QR format 002 / payment link) on every client page, from «Налаштування» (`PUT /api/profile/requisites`, Luhn + https refused hard); «Я оплатив» = a `PAYMENT` project message; its receipt is READ by AI but MATCHED in code (`PaymentReceiptCheck`) — a match is recorded via `addReceipt`, anything else waits `PENDING` for «Кошти прийшли».
- **The PWA update banner needs THREE things, any one silently kills it (iteration-sw-update-banner)** — `registerType: 'prompt'` + `SKIP_WAITING` in `src/sw.ts` + `clients.claim()`; `swUpdate.test.ts` reads the files as text.
- **An act receipt authors offline (offline-act-receipts, PWA-only)** — outbox `actReceipt`, create only, photo as bytes; edits rewrite the pending create; queued rows merge at `ActEditorPage`, never into the cache; call `initOutbox` ONCE.
- **Dictation is text, never audio** — `AiFlow.DICTATION` parse/commit; an unmatched position comes back flagged, never 0 ₴; `CatalogMatcher` decides (Dice, `MIN_SCORE` 0.6 + `MIN_MARGIN` 0.1, ties refused); FREE needs a verified email.
- **The shopping list is where the material answer lands (V126)** — object-scoped, FREE, no price, never writes an `ObjectExpense`; a recalculation replaces its own `source_estimate_id` rows; rows never merged; creating writes lock the object (`getOrCreate`).
- **The calculator answers «скільки купити» (V127)** — norms keyed by name+unit, filtered by the POSITION's trade; unit read off the position, never converted; `PERCENT` lines out of coverage; `PERIMETER` asked, never derived; rounding UP; nothing stored.
- **TILING + PAINTER norms, and the habits nothing was reading (V137, V138, V139)** — a stored preference needs a reader AND a door; habits rescale linearly, never an owned norm; per-position params (`sections`, `thicknesses`, `steps`, …) are separate query params; suggestions pre-filled visibly.
- **An ANSWER the calculator asked for belongs to the estimate, not the browser (V142)** — `estimate_material_param` keyed by (position, basis); no `requireNotSigned`; value > 0, a cleared field deletes; the query string wins per question.
- **A coefficient is the MASTER's, and it forks on write** — `owner_id` is inside `ux_material_norm`; `MaterialNormService.saveOwn` copies the default and answers with the row it wrote; resolution is `preferOwn` on the read path.
- **A receipt at the till is a RECEIVABLE, not a cost (V129 «Чеки обʼєкта»)** — `project_receipt` is reimbursable by default and writes nothing to expenses until «це моя витрата»; the materials axis never folds into `contracted`/`acceptedByActs`.
- **«Мої гроші» is a LENS over money that already exists (V135)** — `CashFlowService` unions payments + object expenses + `cash_entry`; rows edited in place through their own services; the client's material money is in no total (`MaterialRefundSplit`); «Гроші обʼєкта» is this feed per object.
- **The same paper in BOTH receipt tables (V134, review B-04)** — `FiscalQrReceiptReader` is the one QR read; `ReceiptIdentityIndex` (earliest row wins); `ActReceiptReconciler` settles at sign time and never throws; a blank identity is not an identity (`FiscalIdentity`).
- **A signature freezes what was READ (review round 3, V141)** — `ActLineBinder`: an estimate-linked line takes name/unit/price from the estimate, capped by what is open; both documents carry `@Version` into the portal and back; `requireNoActs` on reopen/delete/duplicate/uncount.
- **Act money squares to the kopeck (round 4, B-87)** — the last unit takes exactly what remains (`ActRepricer.settleLastUnits`); adjustments are cumulative; re-derived at save/publish/offline sign, not at the portal signature.
- **A figure the CLIENT may read never carries the markup (round 3 §3-§4, V144)** — `ClientSafeName` strips `[+−]N%` on public surfaces; `@Version` on receipt tables is `Long`; outward side effects wait for the commit (`AfterCommit`); sign paths `rollbackFor = Exception`.
- **A rule right for ONE case, applied to every case (round 2 §2-§3, V145)** — primer bought once; grout is geometry (`baseline_param`); a bound per question; foreign cash id → 404; `Locale.ROOT` on every key fold.
- **A tap is lost to the BROWSER, not to a handler (touch-targets)** — every tapped element: `user-select: none` + `-webkit-touch-callout: none` + `touch-action: manipulation`; PWA and static pages are a mirrored pair pinned by text-reading tests.
- **A figure the master already READ may not be rewritten (round 3 §2)** — object card and dashboard take Σ SIGNED ∧ counted (falling back to the latest non-ADDENDUM); an object with a signature or money is archived, never deleted (`ProjectDeleteGuard`).
- **A saved answer that nothing can read (round 2 §1, V143)** — forked bundle items remember their default (`forked_from_item_id`); re-filing a shipped norm re-files its forks; `MaterialPrefs` is the one definition of a habit.
- **The PWA half of the reviews (iteration-review-pwa)** — figures recompute from data, not the screen; a separator may be a decimal point; guards ask the outbox/queue, not a snapshot; `flushOutbox` is single-flight; `offlineMutate` takes `cancel`.
- **A signature is a stored snapshot (V150, B-76)** — signing writes `signed_document` (canonical JSON of every printed value, `doc_hash` = its SHA-256, stamped PDF + copied logo); a signed document renders FROM it (`SignedDocumentService`); reopen clears the pointer.
- **An ownership guard is proven by WHOSE ROW came back (idor-matrix)** — `IdorMatrixIntegrationTest` covers every id-bearing owner route (bidirectional guard); DENIED = 403/404, a 400 fails; idempotent deletes need a witness; a two-hop guard needs its own transaction.
- **Spring Security 7** — lambda DSL only, `PUBLIC_PATHS`; **`RestAuthenticationEntryPoint` keeps 401 vs 403 disjoint**.
- **Localization** — `messages.properties` is **Ukrainian** (base/fallback); exceptions stay English, advice maps type→bundle key; filters use `requestLocale`.
- **Error shape** — `ErrorResponse`; **401 (re-auth) and 403 (not allowed) are disjoint**; client-disconnect → `null`, no Sentry; quiet 404 for scanner probes; quiet 406 for a `produces`-typed endpoint hit by a link-preview bot.
- **What reaches Sentry (sentry-coverage)** — controller 5xx, `BackgroundErrors.capture(task, e)` in fail-soft catches, and the scheduler's handler; never declare an `AsyncConfigurer`.
- **Round 5 P0 (iteration-review-round-5-p0)** — `PushEndpointPolicy`, `RequestSizeLimitFilter`, `ClientIp.of`, `esc`/`oneLine` in letters, monobank outside any tx, `SafeWorkbook` for POI, the outbox sends only the current owner's ops.
- **Round 5 P1 (iteration-review-round-5-p1)** — no storage/bcrypt/DNS inside a transaction (`Transactions`); `HeavyWork` gates PDFs/imports; every `@Async` names its pool; `AiFlowBudget`; refresh-token families; `IdempotentWriteFilter`; `SafePath`.
- **Offline idempotent creates** — `X-Entity-Uuid` header, idempotency **before** limit checks; wired on clients/projects/estimates/items; a new offline entity follows the pattern.
- **File storage** — local/S3-R2 via `StorageConfig`; reads via `FileController`; a blob is deleted AFTER the row (`StorageCleanup.afterCommit`); a project delete collects five key sources first.
- **AI flows (`service/ai/`)** — per-flow provider+model; `AiExtractors.forFlow(...)` resolved at startup + logged; a typo disables ONE flow (503); empty config value = absent.
- **Estimate import (Excel/photo)** — `import/parse|commit`, `Feature.ESTIMATE_IMPORT`; raw-HTTP extractor; failure → 503 `AI_UNAVAILABLE` (retries 429/5xx/529); file discarded.
- **`sheetKind`** — `PRINTED_PLAN` vs `HAND_DRAWN`; **default leans PRINTED_PLAN** (incl. a missing field); passport rules need ≥2 evidence signals.
- **Duplicate with markup, and «Твоя націнка» (crew-margin)** — `source_unit_price` per LINE; `CrewMarginCalculator` recalculates detached copies; crew prices never reach the client (`PublicEstimateIsolationTest`); a line added to the copy is all the master's margin (2026-11-23); mirrored with `crewMargin.ts`.
- **Consolidation freezes «%» lines, and V92 makes that honest** — `copyForConsolidation` freezes the amount; `base_origin_label` is a snapshot.
- **Consolidated / receipt import / object photos** — `consolidate` (new DRAFT); `RECEIPT_IMPORT` adds to an open estimate, no catalog upsert; photos stream only via authenticated owner/portal-token endpoints, never `/api/files/**`.
- **Photo folders (V111)** — «Чеки»/«Інше» are virtual; other names persist; a non-empty folder can't be deleted; `folder` param has three states. New nullable backend fields are `?:` in `api/types.ts`.
- **Object-level payments (`payment_receipt` V100)** — money belongs to the project; the payment plan is gone (`project_payment` read-only); `addReceipt` is the one door; mutations behind `Feature.OBJECT_ECONOMY`.
- **Economy gate is field-level, not endpoint-level** — `ObjectEconomyResponse.internals` is null for FREE (soft check); other PRO gates hard-403.
- **Catalog is reference data, estimate lines are snapshots** — templates copied BY VALUE; `estimate_items` carry their own name/unit/price with **no FK**; a rewrite must notice the master (`catalog_update_notices`); default catalog is works-only (V81).
- **A default bundle is a SEQUENCE, not a set (V112)** — `sort_order` is the content; template items carry no price (resolved by name at apply — a miss is 0 ₴, so names are copied exactly and checked).
- **A default bundle is a JOB (V148)** — protection first, cleanup last, ≥ 6 lines; rewritten in place; a catalog RENAME rewrites bundle lines, forks and norms in the same migration (V158 shape).
- **DRYWALL is filed by PHASE, and a refresh guard must be TRADE-AWARE (V116)** — phase categories; a price migration queues PRICE_DRIFT, never writes a master's price; a migration inserting a template re-runs V118's ranking.
- **Doors/windows and climate are trades (V224, iteration-lviv-gap-positions)** — `DOORS_WINDOWS`, `CLIMATE` after METAL in the enum, six CHECKs and V118's rank; no default bundles (`template_trade_override` untouched); established names (короїд, ондулін, Армстронг) are never renamed (owner).
- **Every trade holds its own positions (V220, iteration-trade-catalogs)** — `catalog_items` is unique per (owner, TRADE, name, type, unit): a shared library position is a row in each trade, own folder, own price; seeding copies per trade; nothing re-files (`sharedTrades` gone); a line arriving by NAME goes through `CatalogFiling` (one trade → it, several → the estimate's, else none); `TradeCatalogsIntegrationTest` holds every trade to it.
- **An estimate line explains itself, by COPY (V119)** — `estimate_items.description` is a snapshot copied at catalog add, duplicate, consolidation and `applyTemplates`; not on `EstimateItemRequest`.
- **A finish level is a BUNDLE, and it explains itself (V121)** — Q1/Q2/Q4 are bundles; `estimates.quality_note` is a snapshot at apply time that nothing renders yet.
- **Templates are fully editable, and a system default FORKS ON WRITE (V113 `template_default_override`)** — `resolveWritable` forks a default on first write and answers with the written id; delete on a default is a hide; the PWA editor saves explicitly, order last.
- **`catalog_update_notices` is a queue, not a slot (V94)** — one row per notice-worthy event, `kind` (`COUNT`/`PRICE_DRIFT`) discriminates the shape (CHECK-enforced); dismiss/accept are id-scoped.
- **Community prices (`price_insight_candidate`, V94)** — weekly two-level median of masters' estimate lines, min 3 masters; admin apply queues notices.
- **Custom trades (V91)** — `user_trade` has no reference catalog by design; `catalog_items`/`estimate_templates` (own only) get a nullable `custom_trade_id` FK, `ON DELETE SET NULL`; invariant on both: `custom_trade_id IS NULL OR trade = 'OTHER'`.
- **«%» is a share OF something (V88)** — base `POSITION`/`TOTAL` (per-type: works vs materials), `MANUAL` retired; a `POSITION` base may be several lines, summed (V221 `percent_base_item_ids`); a catalog «%» position has NO figure and arrives empty (`POSITION`, no base, 0 %) — single AND batch add; **`line_total` is STORED** (server-authored, never from a request); only a `TOTAL` percent may be negative (V89).
- **Mirrored formulas — change BOTH sides together** — `EstimateMath.recalculate` ↔ `useEstimate.recomputeLines` (percent pass), `MeasurementCalc` ↔ `measurementCalc.ts`, `CrewMarginCalculator` ↔ `crewMargin.ts`, `HryvniaInWords` ↔ `src/seo/hryvniaInWords.ts`.
- **A request DTO is a CONTRACT with the other repo** — `RequestContractSnapshotTest` freezes every `*Request` into `contract/request-dtos.json` (`-Dcontract.update=true`); copy it to `majstr-pwa/src/api/contract/` — mirrored pair.
- **The public calculator IS `MaterialCalculatorService` (seo-calculators)** — `POST /api/public/materials/calc` builds lines from a white list and calls the same `compute`; tile is a material per format; no SEO figure typed by hand (`npm run seo:check`).
- **Estimate line order** — explicit reorder; `EstimatePdfService` mirrors the grouping (change both, or the PDF disagrees).
- **One estimate from several templates, and only the POSITIONS he ticked** — `ApplyTemplatesRequest`; empty `itemIds` = whole bundle; pick order kept; subset applied before the name de-dup.
- **Album takeoff (`service/album/`)** — built, tested, **NOT exposed**; run it async when wired (minutes-long).
- **Entities vs records** — entities are Lombok JPA (`@EqualsAndHashCode(of="id")`); DTOs are records; don't mix Lombok with records.
- **Gotchas** — Jackson 3 = `tools.jackson.*`, omitted primitives fail unless `fail-on-null-for-primitives: false` (required = `@NotNull` wrapper); SB4 split auto-configs; no `TestRestTemplate`; Testcontainers 2.x `testcontainers-*`; `-parameters`; `open-in-view: false`.
