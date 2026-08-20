# Iteration: Акти виконаних робіт (work acts)

**Status:** in progress — Prompts 0, 1, 2 & 3 done.
**Source plan:** `C:\Work\prompts\acts-prompts-v3.md` (six sequential prompts, 0→5).
**Migrations:** none yet (Prompt 0 needs none); V103 (Prompt 2), V104 (Prompt 3) to come.

Goal: a real «Акт виконаних робіт» document — built from signed-estimate positions, signed
separately by the client, with its own PDF and portal. The six prompts, in order:

| # | What | Where |
|---|------|-------|
| 0 | Duplicate signing no longer reopens the signed parent | backend + PWA |
| 1 | Rename tabs (act→economy, new acts tab) + Notes→FAB | PWA only |
| 2 | Document requisites (V103) | backend + PWA |
| 3 | Work-acts core (V104) | backend only |
| 4 | Acts tab + PDF | backend + PWA |
| 5 | Act portal + works axis in economy | backend + PWA |

---

## Prompt 0 — signed estimate no longer auto-reopens (done)

**Why.** A signature is a historical fact, not a state. The economy-rework iteration made
`PublicEstimateService.doSign` auto-reopen a still-SIGNED parent to DRAFT when its duplicate got
signed — rewriting a signature the client really gave. Acts make that dangerous: an act references a
SIGNED estimate's positions, so silently flipping SIGNED→DRAFT under an act already sent to the
client is a hole. The only thing auto-reopen ever needed to accomplish was *not double-counting the
same deal in the object economy* — and `countInEconomy = false` says exactly that, with the master
able to flip it back.

**What shipped.**

Backend:
- `PublicEstimateService.doSign` — the supersede branch now sets `parent.setCountInEconomy(false)`
  (was `estimateService.applyReopen(parent, null)`); still stamps `supersededByEstimateId`.
  `applyReopen` is untouched — the owner-facing `reopen` still uses it.
- No migration. Legacy parents already reopened to DRAFT in the past stay DRAFT (the data to
  re-sign them was erased by the old `applyReopen`; acceptable, it won't happen again).

PWA (bumped 1.16.4 → **1.16.5**):
- `useToast.ts` — toast helpers gained an optional `action: { label, onClick }` (backward-compatible
  second arg: number keeps positional-ttl, object adds the action). `Toast.tsx` renders it.
- `EstimateEditorPage.tsx` — after a duplicate is created, no longer force-navigates to it. Shows a
  toast «Кошторис «…» створено у вкладці Кошториси» with an «Відкрити» action, leaving the master
  where they were (golden rule: the context switch is theirs to make).
- `economyNote.ts` — `shouldShowSupersedeBanner(status, name)` extracted (pure, testable); the
  supersede banner in `EstimateRow` is now suppressed for DRAFT rows. Reason: post-Prompt-0 the
  parent stays SIGNED (lives in Економіка, shows its «не враховано» panel banner there); the only
  DRAFT rows still carrying the flag are legacy, and the «Повернуто в чернетки» wording reads wrong
  on a plain draft.
- i18n: `common.open`, `estimate.duplicatedToEstimatesTab` (uk + en).

**Tests.**
- `PublicEstimateServiceTest.sign_ofADuplicate_stopsCountingTheSupersededParent_butKeepsItsSignature`
  — rewritten from the old auto-reopen test (behavior change, per the prompt: rewrite, don't disable).
- `SupersedeOnSignIntegrationTest` (Testcontainers) — signs the duplicate end-to-end through the
  real service: parent stays SIGNED with signature intact, `countInEconomy=false`,
  `supersededByEstimateId` set; `sumIncomeCounted` = duplicate alone (not doubled); both remain
  SIGNED panels in `findSignedEstimateSummaries`.
- PWA: `economyNote.test.ts` (+banner-visibility cases), `Toast.test.tsx` (action renders/fires,
  plain toast has none).

**Gotcha.** `EstimateService.duplicate()` already sets the *source's* `countInEconomy=false` at
duplicate-*creation* time — that's unrelated to signing. Prompt 0 is about what happens at *sign*
time to the *parent*, which is a different estimate in the бригадір flow than the source.

---

## Prompt 1 — rename tabs + Notes → FAB (done, PWA-only, v1.16.6)

**Why.** The `'act'` tab key was already «Економіка об'єкту» (historical name). The real acts tab
needs that key, so economy had to be renamed *first*, carefully, with a legacy alias so old
`?tab=act` links don't silently fall back to «Кошториси».

**What shipped (PWA only, no backend touched).**
- `ProjectDetailPage.tsx`: `Tab` = `estimate | measurements | photos | economy | acts` (was
  `…| notes | act`). `'act'` → `'economy'`, new `'acts'` tab. `resolveTab` gained a legacy alias
  `?tab=act → 'economy'`. `shortLabelKey` machinery removed (economy's short form is now its only
  form). `mode={tab === 'economy' ? …}` and the render branches updated; `'acts'` renders the new
  `ActsSection`.
- Notes moved off a tab into the FAB: a `NotesSheet` (bottom-sheet, same shell as `ChatLinkSheet`)
  opened from a dynamic FAB action («📝 Створити нотатку» with 0 notes, «📝 Нотатки · N» otherwise —
  `useNotes(id)` drives the count). Read-only in a terminal object stage (view kept, create/edit/
  delete hidden) via one `readOnly` prop — golden rule: never take away access to what exists.
- `NotesSection.tsx` **deleted**; its list+form logic moved verbatim into `NotesSheet.tsx` (only the
  container changed). `NotesSection.test.tsx` → `NotesSheet.test.tsx` (+read-only case).
- `ActsSection.tsx` created — teaching empty state only (no create button; Prompt 4 fills it in).
  The real component, not a throwaway stub.
- i18n (uk+en): `tabEstimate`→`tabEstimates` «Кошториси», `tabAct`/`tabActShort`/`tabNotes` removed,
  `tabEconomy` «Економіка», `tabActs` «Акти», `projects.notesCreate`/`notesWithCount`, `notes.title`,
  new `acts.*` block. Removed a stray duplicate `tabActShort`.

**Tests.** `resolveTab.test.ts` (+legacy `act→economy`, +`notes` now unknown), `resolveBackUrl.test.ts`
(+legacy `act` pass-through), `NotesSheet.test.tsx` (list/add/empty/read-only). Full PWA gate green
(lint · tsc · typecheck:tests · vitest 597 + 1 load-flake that passes alone · vite build). Mobile:
max tab-label width (9 Cyrillic chars) unchanged from the previously-fitting «Економіка» short form —
no 375px regression; on-device glance still worth it.

**Gotcha.** `resolveBackUrl` is a pure pass-through (`?tab=${fromTab}`), so it needed no change — a
legacy `?from=act` interpolates to `?tab=act`, which `resolveTab` then maps to `'economy'`.

---

## Prompt 2 — document requisites (done, backend + PWA, V103, v1.16.7)

**Why.** The act PDF (Prompts 3–4) needs legal/bank details the model had nowhere to hold. Pure
prep — no acts yet, just the data + the forms to fill it.

**Migration V103** — all-nullable additions (two enum flags carry safe defaults, so no backfill):
- `users +=` `legal_name`, `tax_id` (РНОКПП), `legal_address`, `iban`, `bank_name`,
  `vat_payer bool NOT NULL DEFAULT false`, `vat_id` (ІПН ПДВ), `tax_group smallint`,
  `tax_rate numeric(5,2)`, `doc_city`, `act_number_format varchar NOT NULL DEFAULT 'PLAIN'`
  (+ CHECK `PLAIN|WITH_YEAR`).
- `clients +=` `client_type varchar NOT NULL DEFAULT 'PERSON'` (+ CHECK `PERSON|FOP|COMPANY`),
  `tax_id`, `legal_name`, `legal_address`, `signatory_title`, `signatory_name`.

**Backend.** New enums `ActNumberFormat`, `ClientType`. Fields added to `User`/`Client`. DTOs
extended: `ProfileUpdateRequest` (+11 optional), `UserResponse`, `ClientRequest` (+6 optional),
`ClientResponse`. `ProfileService.applyRequisites` (blank→null; the two flags only overwrite when
non-null so an older client can't reset them) and `ClientService` create/update wire them; a null
`clientType` on update leaves the stored type untouched. Fallbacks (legalName→companyName→fullName,
etc.) are deferred to the PDF service in Prompt 4 — V103 just stores.

**PWA (1.16.6 → 1.16.7).** `lib/requisites.ts` — warning-only length checks (РНОКПП 10 / ЄДРПОУ 8 /
ІПН ПДВ 12), NEVER blocking. `ProfileEditModal` gained a collapsed «Реквізити для документів» section
(auto-expands if any field is filled), VAT block shown only when «Платник ПДВ» is ticked (else the
єдиний-податок group/rate). `ClientEditModal` rewritten with a PERSON/ФОП/Компанія type switch that
reveals the matching requisites (ЄДРПОУ + signatory for COMPANY, РНОКПП for ФОП). Types + i18n
(uk+en) extended.

**Tests.** Backend: `ProfileServiceTest` (requisites round-trip + null-flag-preserves),
`ClientServiceTest` (create/update requisites, DRY'd via a `person(...)` helper),
`DocumentRequisitesIntegrationTest` (Testcontainers — round-trip + defaults + CHECK-backed enums),
+ fixed the `UserResponse`/`ProfileUpdateRequest`/`ClientRequest` fan-out in `AuthControllerTest`.
PWA: `requisites.test.ts` (warnings), `ClientEditModal.test.tsx` (type switch), + the central
`factories.ts aUser()` and ~10 inline fixtures updated for the new required fields. Full PWA gate
green (605 vitest). Backend build on the user.

**Gotcha (record fan-out).** Extending 4 record signatures broke 3 backend test construction sites
+ the PWA `UserResponse`/`ClientResponse` fixtures (caught by grep + `typecheck:tests`, exactly the
fan-out the memory warns about). РНОКПП (10-digit individual) vs ІПН платника ПДВ (12-digit VAT) are
DIFFERENT numbers — the UI labels + helpers keep them distinct.

---

## Prompt 3 — work acts core (done, backend only, V104)

**Migration V104.** `work_act` + `work_act_item` tables; `estimates += kind varchar NOT NULL DEFAULT
'REGULAR'` (+ CHECK REGULAR|ADDENDUM). All act line fields are frozen copies (name/category/unit/
price + `cumulative_before`), `estimate_item_id`/`estimate_id` are `ON DELETE SET NULL` so a frozen
act survives the estimate's edits/deletion. `UNIQUE(user_id, number)`.

**Entities/enums.** `WorkAct` (`@Version`), `WorkActItem`; enums `WorkActKind` (INTERIM|FINAL),
`WorkActStatus` (DRAFT|SENT|SIGNED|REJECTED), `EstimateKind` (REGULAR|ADDENDUM) + `Estimate.kind`.

**Service (`WorkActService` + `WorkActCreator` + `WorkActResponseFactory`).**
- **Numbering — CONTINUOUS per master** (user decision; PLAIN «7» would collide across years under
  the UNIQUE constraint otherwise). `maxNumberSeqForUser` parses the leading integer of existing
  numbers, +1; PLAIN → «7», WITH_YEAR → «7/2026» (issue year). **Race-retried:** `create()` is NOT
  transactional and loops over `WorkActCreator.attempt` (its OWN `@Transactional`, public so the
  proxy applies) — a lost UNIQUE race rolls that attempt back and retries with a fresh number.
  `WorkActResponseFactory` builds the response INSIDE the attempt's tx (create() never touches the
  act's lazy project).
- **One open act per object** (any DRAFT/SENT → 409 `WORK_ACT_OPEN` «Спочатку завершіть акт № N»);
  a FINAL act → 409 `WORK_ACT_FINAL_EXISTS` on any further create.
- **Immutable once signed** — shared `requireNotSigned` guard → 409 `WORK_ACT_SIGNED`; delete only
  DRAFT/REJECTED (409 `WORK_ACT_NOT_DELETABLE`).
- **Progress never denormalized** — `sumSignedQuantitiesByEstimateItem` (native, SIGNED acts only)
  drives both the progress endpoint AND the `cumulative_before` a new act freezes. `line_total`
  server-authored; `exceedsEstimate` computed live (cumulative+qty vs the estimate line's CURRENT
  qty) — the backend accepts overage, the master decides.
- **ADDENDUM on sign** — an act line with no `estimateItemId` (additional work) → a SIGNED, counted,
  non-shared `EstimateKind.ADDENDUM` estimate «Додаткові роботи до акта № N» (amounts via
  `EstimateMath`) in the same transaction; `addendum_estimate_id` stamped. ADDENDUM is filtered out
  of `EstimateService.listForProject` (so it stays out of the Кошториси tab + share pickers).

**Feature.** `Feature.WORK_ACTS` in the enum + PlanConfig FREE/PRO/TEAM (temp-FREE block extended).
Gating is hard `requireFeature` on create only; list/progress are owner-scoped, ungated.

**API.** `GET/POST /api/projects/{id}/acts`, `GET /api/projects/{id}/acts/progress`,
`GET/PATCH/DELETE /api/acts/{id}`, `PUT /api/acts/{id}/items`, `POST /api/acts/{id}/sign-offline`.
Create is offline-idempotent (`X-Entity-Uuid`, idempotency before the gate).

**Tests.** `WorkActIntegrationTest` (Testcontainers): continuous numbering across objects,
one-open-act, UNIQUE constraint, cumulative across signed acts (frozen per act), exceedsEstimate,
signed-immutable + delete-only-draft, ADDENDUM-on-sign, FINAL closes the object, and the
Prompt-0-interaction (reopening the parent estimate doesn't break a signed act).
`DefaultFeatureGuardTest` +WORK_ACTS-on-FREE. `EstimateSummary` gained `kind` (fan-out: only the
`from()` factory constructs it; the ProjectService `EstimateSummary` is a different nested record).

**Gotcha.** `@Transactional` silently no-ops on non-public methods under Spring's proxy — the
per-attempt `WorkActCreator.attempt` had to be **public** for the numbering retry to get its own tx.

---

## Prompt 4 — Acts tab + PDF (DONE, PWA 1.17.0)

**Backend done:**
- **`HryvniaInWords`** — Ukrainian «сума прописом» from scratch (feminine numerals + noun
  declension; kopecks as digits). `HryvniaInWordsTest` covers every boundary the prompt lists.
- **`WorkActPdfService`** (OpenPDF, mirrors `EstimatePdfService`) — title «АКТ № N приймання-передачі
  виконаних робіт» + «(проміжний)» for INTERIM + contract ref; two DISTINCT dates; place/object;
  Виконавець (legalName→companyName→fullName fallback, РНОКПП, IBAN+bank, VAT-vs-єдиний-податок);
  Замовник by `ClientType`; items table grouped estimate→category + a separate «ІІ. ДОДАТКОВІ
  РОБОТИ» section; Разом→аванс→До сплати; sum-in-words; optional cumulative «ДОВІДКОВО» block;
  quality statement; additional-works agreement clause; INTERIM disclaimer (ч.3 ст.853); signatures;
  logo (BRANDED_PDF). Wired: `WorkActService.renderPdf` + `GET /api/acts/{id}/pdf`.
  `WorkActPdfServiceTest` (pdfbox) asserts every mandatory block + FINAL omits the disclaimer.
- **PERCENT-in-PDF** — verified already fixed via `UnitLabel` (open-question closed RESOLVED).
- **Economy «act»→«кошторис» rename** — i18n (uk+en), panel + `SignedEstimatePanelResponse` javadocs
  drop the "act framing", stale `?from=act`→`?from=economy` nav. Economy test updated + green.

- **Closed-by-acts on the estimate read path** — `EstimateItemResponse.closedByActs` (nullable,
  new second `from(item, closed)` factory; the plain `from(item)` passes `null`, so no fan-out).
  `EstimateService.toResponse` builds a per-line Σ **only for a SIGNED estimate** (one
  `sumSignedQuantitiesByEstimateItem` aggregate, the same query the progress endpoint uses — no
  N+1), so a DRAFT act never contributes. Drives the estimate board's «✓ закрито» / «done / total»
  chip in the PWA.

**PWA done (1.17.0):**
- **`src/api/acts.ts` + `types.ts` + `useActs.ts`** — the acts API module (list/progress/create with
  `X-Entity-Uuid`/get/updateHeader/replaceItems/remove/signOffline/fetchPdf) and its react-query hooks.
- **`ActEditorPage`** (`/acts/:id`, route + `routes.act(id)`) — header (kind toggle, two dates,
  contract ref, «Показувати матеріали»); progress grouped estimate→line, **tick = full remainder**,
  partial by typing; **exceeds** → amber banner + «Оформити перевищення як додаткові роботи» (clamps
  the line to its remainder, moves the overflow into an additional-works row); «Додаткові роботи»
  with a first-add `InfoPopover`/localStorage warn (ст. 877); footer Разом→аванс→До сплати + «показати
  наростаючий підсумок у PDF»; Save/Sign-offline/PDF/Delete.
- **`useNewAct` (shared flow)** — `actCreateBlock(acts)` (open / final / null, mirrors
  `WorkActCreator`) + create-then-open with default period (last SIGNED act's `period_to`+1 → today,
  else the object's creation date). Used by both entry points.
- **`ActsSection`** — list rows (№ · kind · period · payable · status badge · ⋮ Open/PDF/Delete) +
  «+ Новий акт» blocked with an explanation when an act is open or a FINAL exists.
- **«Згенерувати акт»** — new item on the economy signed-estimate panel's ⋮ (preselects that
  estimate), hidden when `actCreateBlock !== null`.
- **`EstimateItemsBoard` closed lines** — success bg + chip («✓ закрито» full / «done / total»
  partial); meaning always carried by the CHIP, never colour alone, so it never collides with the
  chip-less last-touched highlight.
- **`ACT_STATUS_VARIANT`** — act status→badge colour, same mapping as estimates.

**Tests (PWA):** `ActEditorPage.test` (tick=remainder, exceeds+convert, materials toggle, preselect),
`ActsSection.test` (empty/open/final block states + list row), `EstimateItemsBoard.test` (+closed /
partial / null-doesn't-colour), `ObjectEconomySection.test` (+«Згенерувати акт» shown / hidden while
open). Full PWA gate green (lint, tsc, typecheck:tests, 618 vitest, vite build).

**Backend build:** run `./gradlew build` locally to confirm the `closedByActs` wiring is green.

---

## Prompt 5 — Act portal + works axis in economy (DONE, PWA 1.18.0)

**Migration V105.** `project_share_links.kind` CHECK widened to include `ACT`; `+ work_act_id`
(FK, `ON DELETE CASCADE`) with invariant `(kind='ACT') = (work_act_id IS NOT NULL)` — one link = one act.

**Act portal (backend).**
- `ShareLinkKind += ACT` (4th kind; javadoc rewritten). `ProjectShareLink.workAct` set only for ACT.
- **Owner side** — `ProjectPortalService` act trio (`actState`/`updateAct`/`sendActEmail`): publish flips
  DRAFT→SENT and mints/reuses the act's own link (a REJECTED act can't be shared). Endpoints on
  `WorkActController`: `GET/PUT /api/acts/{id}/share`, `POST …/share/send-email`.
- **Public side** — `PublicActPortalController` (`/api/public/act/{token}` view/sign/question/pdf) →
  `PublicActPortalService`. Defense-in-depth: only SENT/SIGNED served (a DRAFT is 404 even with a valid
  token). **Sign** records signer name/phone/ip/UA, stamps SIGNED, computes **`doc_hash` = SHA-256 of the
  canonical (unstamped) PDF**, pushes the master (`push.act-signed`, uk), emails the client a PDF copy
  (`EmailService.sendSignedActCopyEmail`, Resend attachment) — both fail-soft. Honest wording:
  «Підтвердити приймання робіт», never a legal-equivalence claim (a simple e-signature has no such
  presumption). Reuses `SignRequest`/`QuestionRequest`/`QuestionResponse`.
- `WorkActPdfService.PdfModel += docHash` — a tamper-evidence footer stamped on the SIGNED PDF; the
  stored hash is of the canonical content, the download re-renders with the stamp.
- `PublicActView` — a fresh client-safe DTO (no economy/other-acts leak); added to
  `PublicEstimateIsolationTest` roots.

**Works axis (economy).** `ObjectEconomyResponse += acts` (`ObjectEconomyActsResponse(contracted,
acceptedByActs, received)`), computed **unconditionally** (FREE-visible) in `ObjectExpenseService.economy`.
`contracted = sumIncomeCounted` (same figure as `payments.contractedTotal`); `acceptedByActs =
WorkActItemRepository.sumSignedActLineTotals`; `received = PaymentReceiptRepository.sumByProjectId`.

**PWA (1.18.0).** `actPortalApi` (state/publish/sendEmail); `ActShareSheet` (publish-on-open + copy /
email / open, honest wording), wired into `ActsSection` ⋮ «Поділитися»; the works-axis `ActsAxis`
(PaymentStrip-twin, FREE-visible, balance line flips «Невідпрацьований аванс»/«Заборгованість
замовника»/«Розрахунки збігаються» by sign) in `ObjectEconomySection`; `acts.whatIs` master-help
InfoPopover. `static/portal/index.html` — `?a=` in all 5 sites + a fresh `renderAct` branch (before
`adaptLegacy`), verified with a one-off Node harness against sample data.

**Tests.** `PublicActPortalServiceTest` (view DRAFT→404 / SENT ok, sign sets SIGNED+docHash+push,
already-signed→409), `ProjectPortalServiceTest` (+updateAct DRAFT→SENT+mint, REJECTED rejected),
`PublicEstimateIsolationTest` (+PublicActView root), PWA `ActShareSheet.test` + `ObjectEconomySection.test`
(+works axis). Full PWA gate green (lint, tsc, typecheck:tests, 621 vitest, vite build).

**Backend build:** run `./gradlew build` locally (V105 + the act-portal wiring).

## Prompt 5 follow-up — act-editor polish (DONE, PWA 1.18.1)

Two fixes the master hit while testing the act editor live:

1. **Estimate group header showed «КОШТОРИС ВІД» with no date.** The act-progress line carried
   `estimateName` but not the estimate's `createdAt`, so an unnamed estimate fell to the dated default
   name with an empty date. `ActProgressResponse.Line += estimateCreatedAt` (Instant), fed from
   `e.getCreatedAt()` in `WorkActService.progress`; the PWA now calls
   `estimateName(line.estimateName, line.estimateCreatedAt)` so the default reads «Кошторис від N місяця»,
   matching the economy panel.
2. **Additional works are now searchable from the catalog.** The «Додаткові роботи» name field was a
   plain input; it's now the shared `CatalogAutocomplete` (same type-ahead as adding an estimate line) —
   picking a catalog position fills name/type/unit/price, manual entry still works. `Additional` grew a
   `type` field (was hardcoded `WORK` in `buildItems`), seeded from the stored line and preserved from
   the picked item / converted-excess source, so a picked MATERIAL stays a material.
3. **The four bottom buttons (Зберегти / PDF / Підписати / Видалити) moved into a speed-dial FAB.** The
   act editor is long; scrolling to the bottom for every action was slow. Replaced the stacked buttons
   with the shared `Fab`/`FabAction` (same component as the estimate editor), ordered so the primary
   Save sits nearest the thumb and the destructive Delete farthest. Save/Sign shown only while editable,
   Delete only for DRAFT/REJECTED. `acts.actionsMenu` label added.
4. **Act generation is now scoped by its source, with a cross-estimate duplicate warning.** The
   `?preselect=` navigation param (which only sorted the estimate first, still showing all) became
   `?scope=`, which **filters** the editor to that one estimate's positions when generated from a
   panel's «Згенерувати акт»; the Acts-tab «+ Новий акт» passes no scope and still spans every SIGNED
   estimate. Additionally, any off-estimate «додаткова» line whose name matches a position in ANY signed
   estimate (even a hidden one under scope) shows a soft amber warning naming that estimate — the master
   can tick it there as done instead, or keep it loose; his call (`estimatesByLineName` map,
   `acts.additionalDuplicate`). The old test asserting «preselect keeps every estimate» was replaced by
   two — unscoped shows all / `?scope=` shows only one — plus a duplicate-warning test.

PWA gate: lint 0, tsc 0, typecheck:tests 0, ActEditorPage tests 6/6. **Backend build:** the DTO/service change
needs `./gradlew build` (no test constructs `ActProgressResponse.Line` directly — the integration test
reads it back from the service).

## Acts-fix — ДОВІДКОВО rewrite + economy set-reconciliation (DONE, backend V106, PWA 1.18.2)

Found in live testing of the first act. Three linked problems, shipped together.

**Chunk A — «ДОВІДКОВО» block.** The old per-line table printed `cumulativeBefore + quantity`,
which on a first act equals the act's own quantities — the client saw the same numbers twice, and it
never showed the base or the remainder. Rewritten:
- **V106** — `show_cumulative` default → `false` + `UPDATE work_act SET show_cumulative = false`
  (the old shape was wrong; no existing act should carry it). `WorkActCreator` default flipped to
  `!= null && ...`; PWA `useNewAct` create sends `showCumulative: false`.
- The block now renders **three object-wide money rows** — «Виконано з початку робіт» /
  «Загалом за кошторисами» / «Залишок» — from the SAME queries as the economy works axis
  (`sumSignedActLineTotals` + `sumIncomeCounted`), never a private PDF calculation, and only from the
  **second act on** (`existsByProjectIdAndStatusAndIdNot(..., SIGNED, ...)`). Computed by the shared
  `ActCumulativeCalculator` used by both render paths.
- **doc_hash reconciliation** (a conflict the prompt didn't foresee): the figures are live and
  object-wide, so the block is **excluded from the canonical (hashed) render** (`cumulative = null` at
  hash time) — a later signing never invalidates an earlier act's stored hash. The block is thus a
  live reference, deliberately not covered by tamper-evidence. Checkbox relabelled «Додати довідку: …».

**Chunk B — «Прийнято актами» vs «За договором» counted different estimate sets.**
`sumSignedActLineTotals` summed every SIGNED-act line regardless of whether its estimate was in the
economy, while `sumIncomeCounted` filters `count_in_economy = true` — so closing acts against an
excluded kosторис pushed the numerator past 100 %. **Prevent + fix, both:**
- `WorkActService.progress` now also skips `!countInEconomy` estimates; a write-path guard
  (`requireCountedEstimateLines`, 400 `WORK_ACT_ESTIMATE_EXCLUDED`) rejects an act line from an
  excluded/non-SIGNED estimate.
- `sumSignedActLineTotals` gained `LEFT JOIN estimates … (estimate_id IS NULL OR e.count_in_economy)`.
  The `IS NULL` branch is mandatory: additional (off-estimate) lines store `estimate_id = null` and
  their ADDENDUM is in «За договором», so they must count here too. **Chosen `IS NULL` over
  back-filling `estimate_id = addendum.id`** because historical additional rows have null and can't be
  cheaply back-filled — and the PDF's additional/main split keys on `estimate_item_id`, unaffected.
- **Also fixed a pre-existing gap the prompt didn't know about:** the **portal** sign path
  (`PublicActPortalService.sign`, the primary one) never created the ADDENDUM — only the offline path
  did. Extracted `ActAddendumCreator`, now called from BOTH sign paths, so portal-signed additional
  works land in «За договором». `sumSignedQuantitiesByEstimateItem` (green per-line progress) left
  untouched — it keys on estimate_item_id, independent of economy inclusion.
- **Invariant (write it down so the next change keeps it):** «Прийнято актами» and «За договором»
  are counted over ONE set of estimates — SIGNED and `count_in_economy = true`, plus off-estimate
  additional lines whose ADDENDUM is itself counted. Numerator ⊆ denominator, always.

**Chunk C — name the unseen remainder.** No fourth tile (1.16.3 chose density). Instead `(i)`-popups:
`economy.acceptedInfo` now interpolates the not-yet-accepted amount; new `economy.clientDebtInfo` +
`economy.unearnedAdvanceInfo` sit on the balance line via the existing `InfoPopover`.

**Tests.** `WorkActPdfServiceTest` (+block-absent-without-ref, +three-rows-with-ref);
`PublicActPortalServiceTest` (+2 mocks); `WorkActIntegrationTest` (+excluded-estimate not in picker /
PUT→400, +acceptedByActs counts counted+additional only and ≤ contracted);
`WorkActCumulativeDefaultMigrationIntegrationTest` (V106 data-migration drill); PWA economy
(+client-debt balance case). PWA gate green; **backend build on the user**.

## Acts-improvement — title, status moves, shared signed-copy, rate limiting (committed 4a65f1d, V108)

Master feedback + a self-review pass on top of the shipped acts feature (this was committed directly;
documented here after the fact):

- **`work_act.title`** (V108, varchar 120) — a real interim act reads «Штукатурні роботи» / «Шпаклювання»,
  not just a number. Optional, frozen on sign. PWA suggests names from the object's estimate categories
  and auto-fills when every selected line shares one category. Added to `WorkAct`, `WorkActResponse`,
  `PublicActView`, `WorkActResponseFactory`, the create/update DTOs, and the portal `index.html` render.
- **Owner status moves** — `PATCH /api/acts/{id}/status` (`WorkActStatusRequest` → `WorkActService.changeStatus`).
  Allowed ONLY: `SENT→DRAFT` (recall a sent act), `SENT→REJECTED` (client declined), `REJECTED→DRAFT`
  (client came around). Everything else, including any move on a SIGNED act, is 409
  `WORK_ACT_BAD_TRANSITION`; `REJECTED→DRAFT` re-asserts one-open-act (409 `WORK_ACT_OPEN`). `DRAFT`
  target clears `sent_at`.
- **`ActSignedCopyService`** — extracted the docHash computation + client-copy email so BOTH sign paths
  run them. **Review fix: the offline path (`signOffline`) previously produced neither** — an
  offline-signed act had no tamper stamp and the client no independent copy. Canonical (unstamped,
  no-ДОВІДКОВО) render for the hash, stamped render for the email; both fail-soft.
- **`QuestionRateLimiter`** — a write-path rate limit for client questions on every public portal
  (`?t`/`?p`/`?e`/`?a`), keyed IP+token (mirrors `MessageLinkRateLimiter`), because those endpoints
  store a message AND push the master — the blanket 30/min read cap of `PortalRateLimiter` is the wrong
  shape. Wired through all four public portal controllers; `RateLimitProperties.question` +
  `application.yml`.
- **PWA**: `useLeaveGuard` (warns on navigating away from an act with unsaved edits) and `openPdfTab`
  (opens the act PDF via a pattern that survives iOS Safari's popup/focus rules — same class of fix as
  the earlier copy-link iOS fix). Tests: `openPdfTab.test.ts`, expanded `WorkActIntegrationTest`,
  `PublicActPortalControllerTest`, rate-limiter tests.
