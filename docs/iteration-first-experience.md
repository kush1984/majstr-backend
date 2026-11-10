# Iteration — a master's first experience (2026-11-09)

**Status:** built 2026-11-09, NOT committed (the owner checks locally first). **Migrations:** V196.
**PWA:** 1.74.0. Gate figures are in «Gates» at the end.

A file of its own rather than a part of [iteration-registration-friction.md](iteration-registration-friction.md)
or [iteration-share-without-verification.md](iteration-share-without-verification.md): this round is not
about the registration door nor the send rule. It adds a metric (V196), a portal mode and a navigation
change, and it builds ON the previous round's decisions without moving them:

- the master's own send (link, portal, PDF) needs only a name; our letter needs the email too;
- «поділились» = owners with a SENT or SIGNED estimate, one computation for the aggregate and the
  by-source rows;
- `emailFirst` stays gone; `trialDays` stays on `GET /api/plan/limits`.

## Why
95 masters made an estimate, 14 shared it. On prod: 180 drafts, 80 % have a sum; drafts with 5+ empty
lines are 17 (9 %), and the SENT ones show the same share — so how an estimate looks is not the main
reason it stays home. What we could not see at all is how many leave as a PDF in Viber. The round:
do not frighten a newcomer, see real use, reach the ones who got stuck.

## 1. The PDF counter (V196 `pdf_render`)
- **An event table, not a counter on the document.** A counter on `estimates` would UPDATE the row
  whose `@Version` the portal signature checks — a download racing a signature into a 409 — it would
  vanish with a deleted estimate, and the funnel wants OWNERS, which a row with `owner_id` answers
  directly. Columns: `owner_id`, `project_id` (SET NULL), `document` ESTIMATE|ACT, `reader`
  MASTER|CLIENT, `created_at`. **No IP, no user agent** — the link's token only resolves whose object.
- **Written once the bytes exist, off the request.** `PdfRenderLog.record` is `@Async("metricsExecutor")`
  (a new one-thread pool, queue 1000) and fail-soft (`BackgroundErrors.capture("pdf-render-log", …)`).
  The render's read transaction hands out `PdfRenderLog.Traced(job, Origin)` so the owner and object
  ids leave the transaction with the job; the insert never runs inside the render's transaction and the
  download never waits for it. A lost row is a slightly low admin figure; a lost PDF would be a master
  who cannot send.
- Six doors: owner estimate (`EstimateService.renderPdf`) and act (`WorkActService.renderPdf`) =
  MASTER; `?t=`, `?p=`, `?e=` estimate PDFs (`EstimateService.renderForClient`, the one path all three
  public renders now share) and the act link (`PublicActPortalService.pdf`) = CLIENT. The shopping-list
  PDF is not counted (not a document for the client).
- **`ClientHandoffs`** is the ONE computation behind the funnel, its by-source rows and the broadcast
  segment: `shared()` (SENT ∪ SIGNED, moved here from `MetricsService`), `pdfMade()` (MASTER rows),
  `clientDownloadedPdf()` (CLIENT rows) and **`handedToClient()` = shared ∪ pdfMade, a union of id SETS**.
- Funnel (`ActivationFunnelResponse`): the chain is now registered → object → estimate → **«Віддали
  клієнту»** → signed; beside it «Поділились посиланням», «Сформували PDF» (counted from 2026-11-09 —
  earlier PDFs left no trace), «Клієнт завантажив PDF з посилання» (masters), the email and
  shared-while-unverified. `SourceStat` gains `pdfMade` + `handedToClient`;
  `SourceBreakdownIntegrationTest` pins Σ over sources == aggregate for both.
- The admin list's ⚠️ is now exact: **`AdminUserSummary.pdfWithoutShare`** — made a PDF, and no estimate
  of his reached SENT or SIGNED (same rule as the user card's «поділився»).

## 2. «Подивитися, як побачить клієнт» — the preview
- **The mechanism: a stateless ten-minute pass** (`PortalPreviewTokens`). The portal is on the backend's
  domain and the JWT is in the PWA, so the page cannot carry the master's session; a share link cannot
  be borrowed either (minting it is the very footprint the funnel must not see). The pass is
  `base64url(estimate id ‖ expiry) . base64url(HMAC-SHA256)`, under a key **derived from the JWT secret
  with its own label** — it can never be read as an access token, nor an access token as a pass (a JWT
  has two dots; the key differs anyway). Nothing is stored: no table, no sweep, no column on the share
  tables. **Not single-use, on purpose**: the page fetches the estimate, then its photos, and reloads on
  rotate — a one-shot pass would need a table and break the very look it exists for; ten minutes bound a
  leak.
- Owner door: `POST /api/estimates/{id}/client-preview` → `ClientPreviewResponse(url, expiresAt)`, in
  `ShareLinkService.preview` — owner-only (`loadOwned`, 404 foreign; in the IDOR matrix), ADDENDUM 409,
  `CLIENT_PORTAL` like every share; **no name required** (he may look before giving one — the empty
  header is part of what he should see). Read-only transaction: no link row, no status, no push, no letter.
- Public door: `PublicPreviewController` `/api/public/preview/{token}` (+ `/photos/{id}/file`) →
  `PublicEstimateService.viewPreview` = the SAME `buildView` the `?t=` link uses (no second template).
  **No sign, question or PDF route exists under it** (a PDF there would count as the client's download).
  Forged, expired, deleted or ADDENDUM = the neutral 404. The token sits in the `/api/public/<kind>/<token>`
  slot, so the portal rate limiter and `SafePath` handle it like any link.
- Portal page: `?v=` mode → the legacy single-estimate render with a sticky «Так кошторис побачить
  клієнт. Це попередній перегляд — підписати чи задати питання тут не можна.», the actions card dropped
  (a signed banner kept), and its own 404 text («Попередній перегляд застарів…»).
- PWA: «👁 Подивитися, як побачить клієнт» in «Кошторис готовий?» (not on an ADDENDUM) and in the
  estimate's share sheet. `useClientPreview` reserves the tab **before any await** (`lib/openLinkTab.ts`:
  on every platform, `opener` nulled), flushes the outbox first (`estimateQueueClear`, like PDF/share),
  then sends the tab to the pass; on refusal it closes the tab.

## 3. Lines at 0 ₴ before the estimate leaves
- `zeroQuantityLines(est)`: WORK/MATERIAL with quantity 0, not «%»; none on a SIGNED estimate or an
  ADDENDUM. Asked only when the total is above 0 (a 0 ₴ sheet keeps «Заповніть кількість»).
- Before «Поділитися» (which holds the link, the system share and the letter) and «Зберегти PDF» (the
  check runs BEFORE the PDF tab is reserved — a question never strands a blank tab): `ZeroQuantitySheet`
  «N позицій без кількості — клієнт побачить їх як 0 ₴» → «Прибрати й продовжити» (the existing bulk
  delete, offline-queued; toast «Прибрано N позицій · Скасувати», 5 s) or «Залишити як є» (not asked
  again on this estimate this visit). For the PDF the sheet spends the tap: the toast says «Натисніть
  «PDF» ще раз». **Undo re-adds the lines through the same offline queue** — name, unit, price,
  category, sort order and measurement refs come back; the line's catalog `description` snapshot does
  not (`EstimateItemRequest` has no such field — V119).
- «Кошторис готовий?» says «N позицій без кількості» under its hint.

## 4. FREE import → «перенесемо за вас»
On FREE the «З файлу або фото» button of «Обʼєкт створено. Що далі?» (it ended on «Доступно у плані
PRO») is now «Є кошторис на папері чи в Excel? Надішліть — перенесемо за вас» and opens the owner's own
Telegram (`config.conciergeUrl`, `VITE_CONCIERGE_URL`, default `https://t.me/andriykushka` — the owner's
answer; `telegramUrl` stays the public channel). PRO unchanged. PostHog `concierge_import_tapped` (no
properties) — the tap leaves for Telegram, which the backend never sees, so it is the only count.

## 5. Texts and the first screen
- Landing `heroBadge` lost «PWA» («Кошториси для майстрів і бригадирів — просто з телефона»); the FAQ
  about PWA stays. `dashboard.createFirstText` = «Почніть з назви — адресу й клієнта можна додати
  потім.» (V192 made both optional).
- The empty dashboard shows no metric cards until the first object.
- **Navigation is SPEC E's four places**: Головна · Обʼєкти · Каталог · Я (bottom bar and desktop
  sidebar). «Шаблони» moved into the Catalog as tabs «Позиції | Шаблони» (`CatalogTabs`); `/templates`
  stays a real route (and in `public/_redirects`), and «Каталог» stays lit there (`NavItem.also`).

## 6. Broadcast segment «Склали кошторис, не віддали клієнту» (`NOT_HANDED_OVER`)
- Between EMPTY_ESTIMATE and INACTIVE in `AudienceRules`: verified, settled (3 days), a real estimate
  (≥ `FULL_ESTIMATE_LINES`), NOT in `ClientHandoffs.handedToClient()`. UNVERIFIED stays first. A master
  away a month who never handed anything over gets THIS letter, not INACTIVE's (INACTIVE's criteria now
  say «уже віддали клієнту»). No migration: `broadcast.segment` is a VARCHAR(40) with no CHECK.
- The button «Відкрити кошторис» opens his **latest DRAFT**, read at send time
  (`EstimateRepository.findLatestDraftId`, non-ADDENDUM, newest `updated_at`), else the app. The admin
  tab carries a starting draft for it (it mentions the preview and that no verified email is needed).

## Second pass — the owner's answers (2026-11-09)
- **Toasts get out of a sheet's way, everywhere** (`lib/openSheets.ts`): `Modal` holds a counter while
  open; `ToastViewport` sits at the TOP (under the notch, `env(safe-area-inset-top)`) while any sheet is
  open and at the bottom otherwise — desktop unchanged (top-right). `TrialOfferHost` now waits only for
  the page to be visible: a toast no longer holds it back, because the two never share the bottom.
- **The preview asks the name first**, like the PDF (`useClientPreview` → `ensureSender(…, { remind: false })`;
  the tab is reserved in the tap; after the sheet «Натисніть «Подивитися, як побачить клієнт» ще раз»).
  The server still does not require one — the PWA is the door that asks.
- **EMPTY_ESTIMATE's button** opens the latest draft too (`BroadcastDispatcher.buttonUrl`).
- **The object portal's preview (`?vp=`)**: the share sheet in portal mode previews exactly the ticked
  set — `POST /api/projects/{id}/portal/preview`, the pass carries the object + the ticked estimates,
  `/api/public/preview-portal/{token}` renders through `signaturePortal`, the builder the real `?p=` page
  now shares. Nothing is published (the published `portal_visible` set is never read nor written), no
  link minted, no status moved; a foreign estimate id is 404 (IDOR matrix: route + a body case); the
  two pass kinds never open each other's view. The ECONOMY portal has no picker, so nothing to preview.
- Admin: the step reads «Поділились або сформували PDF» — a PDF may have been made for himself.
- **Found on the 375 px walk: «Пропустити» on the phone reminder closed the object's share sheet the
  moment it opened** (pre-existing, not this round's code). Closing a sheet removes its history entry
  with an asynchronous `history.go(-n)`; the share sheet, opened in the same tick, pushed its entry
  first, and the late landing read as Back. `modalHistory` now marks its own jump and, on that landing,
  re-pushes the entry of any open sheet that lost it instead of closing it (bounded at 400 ms for a jump
  that lands nowhere). jsdom does not reproduce the race, so the proof is the real-browser walk;
  `Modal.test` keeps the case and gained a reset seam (`resetModalJumpForTests`).
- `conciergeUrl`'s default `https://t.me/andriykushka` is the owner's own answer in this session
  (asked before the round: «andriykushka»).

## Not changed / confirmed
- The send rules, the `shared` definition, `emailFirst`, `trialDays` — as the previous round left them.
- The ECONOMY portal (`?e=`) has no preview: it has no picker, the server decides what it shows.

## Tests
- Backend: `PortalPreviewTokensTest` (ten minutes; forged/foreign-head/other-secret/access-token = nobody),
  `PublicEstimateServiceTest` (preview = the link's view, touches no link repo; forged/unknown/gone/
  ADDENDUM = 404), `MetricsServiceTest` (union counted once; by-source fold), `AdminUserServiceTest`
  (`pdfWithoutShare`), `EstimateServiceTest` (owner PDF records MASTER), `AudienceRulesTest` (order,
  before INACTIVE, settle), `BroadcastDispatcherTest` (latest draft / root), `AsyncExecutorsConfigTest`,
  `ClientHandoverIntegrationTest` (real socket: preview leaves no link row / status / handoff, no
  sign/pdf under it, forged 404; owner PDF = one MASTER row; client PDF = one CLIENT row; a PDF takes a
  master out of NOT_HANDED_OVER), `SourceBreakdownIntegrationTest` (Σ == aggregate for `pdfMade` and
  `handedToClient`), `BroadcastIntegrationTest`, `IdorMatrixIntegrationTest` (the new route).
- PWA: `useClientPreview.test` (tab reserved before the first await, opener nulled, closed on refusal),
  `ZeroQuantitySheet.test`, `EstimateNextStep.test` (zero line; preview row), `SharePortalSheet.test`
  (preview row marks nothing sent), `ProjectDetailPage.test` (FREE concierge + event; PRO import),
  `DashboardPage.empty.test`, `navItems.test` (four places; Каталог lit on /templates),
  `EstimateEditorPage.queueGate.test` (preview flushes; zero check before the PDF tab), the e2e journey
  (no metrics on an empty dashboard).

## Gates (2026-11-09, first pass — the second pass's figures are in iteration-sample-object.md, run together)
- Backend `./gradlew build`: green, 2436 tests, none failed or skipped.
- PWA: `npm run lint`, `npx tsc -b`, `npm run typecheck:tests`, `npx vitest run` (1859 in 189 files),
  `npx vite build` — green; `test:e2e:offline:shell` 12/12.
- e2e on a real backend 7/7 — run on :5174 → :8081 because the owner's own servers held :5173/:8080
  (`landing` and `smoke` assert the literal `localhost:5173`, so they ran from a port-adjusted copy).
- 375 px walkthrough (scripted Playwright, iPhone user agent): empty dashboard (no metrics, four
  places), «Що далі?» with «перенесемо за вас», a template, «14 позицій без кількості», the preview in
  a new tab (banner, no «Підписати»), the 0 ₴ sheet → removed → name sheet → share sheet (still DRAFT,
  no zero lines left), the PDF (200), Catalog → «Шаблони» with «Каталог» lit.
- Seen on the first walk and fixed in the second pass: the undo toast over the next sheet's buttons
  (toasts now go to the top while a sheet is open — checked at 375 px, no overlap with the share
  sheet's buttons) and the nameless preview (the preview now asks the name first).
