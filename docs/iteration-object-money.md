# Iteration — «Гроші обʼєкта»: the client's material money apart, earnings per object

**Status:** IN PROGRESS (started 2026-11-02, target: pushed by 2026-11-08, webinar 2026-11-11)
**Migration:** V182 (`cash_entry.storage_key`) · **PWA:** 1.68.5 → 1.69.0 (bump not done yet)
**Branch:** `master` in both repos, nothing committed yet (see «State of the work tree» below).
The bot work sits committed on branch `majster-bot` (backend `6284ddb`, PWA `c8d5146`) — NOT merged;
its migration takes the **next free number** when merged (V187 as of V186 — V183 and V185 stay skipped).

## Why

Webinar topic «Як майстру бачити, скільки він реально заробив на кожному об'єкті». Reading the code
showed the app could not answer it:

* the object had no «Заробив» (removed twice: B-85 / crew-margin round — the formula subtracted
  expenses no screen let a master enter against an object);
* there was no door to add a non-receipt expense to an object (crew, delivery); «Мої гроші» rows
  carry no object by the master's round-3 ruling («нічого не вибирати при додаванні»);
* «Розбити на частки» APPENDS stages (split twice = 200 % of the contract), stage names are not
  validated, three entry points (Розбити / + Платіж / FAB) mixed plan and fact;
* money «на матеріал» given BEFORE any receipt became work money (B-65 cap);
* «Мої гроші» counted a till receipt «клієнт поверне» as −5000 and the repayment as +5000 — correct
  cash, but the owner (user) found it incomprehensible: it is not the master's money.

Prod data (2026-11-02, role USER): 150 objects, only 9 have any payment, 7 have plan stages,
8 stages total (6×1, 1×2) and **every one is a «завдаток»** (most likely from the V93 deposit
migration). 141/150 objects record no money at all → the plan feature is effectively unused.

## Decisions (agreed with the user in chat, 2026-11-02)

1. **The master's money and the client's material money are counted apart.**
   * Master's: payment for work, his own spending (object expenses, crew, personal rows) →
     «Прийшло / Витрачено / Заробив».
   * Client's material: till receipts «клієнт поверне» (not billed on an act) + payments
     «на матеріал» (`payment_receipt.material_refund`, before OR after the purchase) → shown as rows
     WITHOUT a sign, counted in no total; balance on its own line: «дав / куплено / винен | лишилось».
   * This reverses the B-33 reading for these rows (B-33 put the receipt in expense and the refund in
     income so they netted; netting still holds, both halves are simply left out).
   * Material billed through an act (receipt on a signed act, or a till receipt billed on an act) stays
     the master's expense: the client repays it inside the act as work money.
2. **B-65 cap removed:** all material money is material (`MaterialRefundSplit.applied = refunds`),
   `outstanding = reimbursable − refunds` may be NEGATIVE (= client's money not spent yet).
   The works axis «Отримано» (`ObjectEconomyActsResponse.received`) is now work money only.
3. **«Мої гроші»:** default stays as now (WEEK, no object filter); new object filter
   (all / «Без обʼєкта» / one object) on the READ only — adding still asks nothing about an object.
   New block «Клієнти винні за матеріал» (all-time, per object, non-zero only).
   Personal rows may carry a **receipt photo** (робочий одяг etc.), QR read pre-fills the sheet.
4. **Object: «Гроші обʼєкта» card** replaces the payments block and the materials axis:
   «Отримано за роботу / Витрачено / Заробив», crew warning when no CREW expense is recorded,
   the client's material line, journal rows (same `CashRow` as «Мої гроші»), «+» →
   **Отримав** (chips Завдаток / За роботу / На матеріал) · **Витратив** (Бригада / Матеріали / Інше,
   object expense) · **📷 Чек** (navigates to the object's receipts page, where «клієнт поверне /
   моя витрата» is decided). Object «Заробив» = «Мої гроші» filtered by that object (same server code).
5. **Payment plan hidden** (UI only, no data change): «Розбити на частки» and «Запланований» are not
   rendered. Existing 8 stages show as ordinary rows. Dead code removal = phase 2.
6. **Estimate «Додати з чеку»** no longer offers «Записати у витрати?» (third door for one paper);
   `ExpenseRequest.source` removed (server sets MANUAL; resolves open question B-84 remainder).
7. Unplanned payment label may now equal a stage name (`error.payment.label-conflict` removed) —
   the «Завдаток» chip would otherwise 400 on objects whose stage is named «Завдаток».

## Done so far

### Backend (compiles; targeted unit + integration tests GREEN; full `./gradlew build` NOT run yet)
* `service/MaterialRefundSplit.java` — uncapped; docs. `MaterialRefundSplitTest` rewritten.
* `service/PaymentService.java` — comment; `validateUnplannedLabel` removed (+ message keys in
  `messages*.properties`). `PaymentServiceTest` updated (cap test → «never pays for work», label test).
* `service/ObjectExpenseService.java` — `source` always MANUAL; acts axis `received` = work only.
* `dto/ExpenseRequest.java` — `source` removed. Contract snapshot refreshed
  (`src/test/resources/contract/request-dtos.json`) and COPIED to PWA `src/api/contract/`.
* `dto/PaymentsSummaryResponse`, `ObjectEconomyMaterialsResponse`, `ObjectEconomyActsResponse`,
  `PublicPortalView`, `PublicEstimateService` — docs/comments for the new rule.
* `repository/ProjectReceiptRepository.sumReimbursableByOwner`,
  `repository/PaymentReceiptRepository.sumMaterialRefundsByOwner` — per-object balances.
* `dto/CashFlowResponse` — `Entry.clientMaterial`, `Entry.hasPhoto`, `materials: List<MaterialBalance>`.
* `dto/ObjectMoneyResponse` (new), `service/CashScope` (new), `controller/ObjectMoneyController`
  (`GET /api/projects/{id}/money`).
* `service/CashFlowService` — totals exclude clientMaterial; `flow(..., CashScope)`; `objectMoney`;
  `materialBalances`; personal photo `attachPhoto` / `removePhoto` / `readPhoto`; `readQr`;
  personal delete drops its photo after commit. `ProjectReceiptService.requireImage` made package-private.
* `controller/CashController` — `?object=none|<uuid>` (`scopeOf`, 36-char check, 400
  `error.cash.object-invalid`), `POST /api/cash/receipt/qr`, `POST|GET|DELETE /api/cash/{id}/photo`.
* `entity/CashEntry.storageKey` + **`V182__cash_entry_photo.sql`**.
* Tests: `CashFlowServiceTest` (new cases: client material in no total, billed receipt = expense,
  per-object debts, filter, foreign object 404, objectMoney, photo cleanup),
  `CashFlowIntegrationTest` (slices add up to the whole, upfront material money scenario, photo
  store/replace/drop, foreign row), `CashControllerScopeTest` (new), `IdorMatrixIntegrationTest`
  (cases for `/api/cash/{id}/photo` ×3 and `/api/projects/{id}/money`).

### PWA (tsc clean; affected vitest suites were being fixed when this doc was written)
* `api/types.ts` — `ExpenseRequest.source` removed; `CashEntryResponse.clientMaterial/hasPhoto`;
  `CashFlowResponse.materials`; `CashMaterialBalance`; `ObjectMoneyResponse`; docs.
* `api/cash.ts` — `object` filter param, `readQr`, `attachPhoto`, `removePhoto`, `photoUrl`.
* `api/economy.ts` — `money(objectId)`.
* `features/cash/CashRow.tsx` (new, shared row), `useCash.ts` (key includes object filter,
  contribution excludes clientMaterial, `add` resolves the id), `AddCashSheet.tsx` (photo + QR for
  personal rows; «на матеріал» tick only on object payments), `CashFlowPage.tsx` (object filter
  modal remembered in localStorage `majstr.cash-object`, «Клієнти винні за матеріал» block).
* `features/economy/useObjectMoney.ts` + `ObjectMoneyCard.tsx` (new); `ObjectEconomySection.tsx`
  renders the card instead of `PaymentsBlock` and the materials axis (`MaterialsAxis` deleted);
  `useEconomy.invalidateObjectMoney` exported + invalidates `['object-money', id]`;
  `useProjectReceipts` writer invalidates it too.
* `features/estimate/ReceiptImportSheet.tsx` — expense prompt removed.
* `locales/uk.json`, `en.json` — new `money.*`, new `cash.*` keys; `receipt.expense*` removed.
* `npm ci` was run (master moved zod to v4; node_modules was stale).
* Tests updated: `useCash.test.ts`, `CashFlowPage.test.tsx`, `ObjectEconomySection.test.tsx`
  (money card + material line tests), `ReceiptImportSheet.test.tsx`.

### Progress after the doc was first written (same day)
* PWA: all gates green — `npm run lint`, `npx tsc -b`, `npm run typecheck:tests`, `npx vite build`;
  full `npx vitest run` 1746/1750 with 4 failures in UNRELATED files (catalog picker, template picker,
  material calculator, SEO calc page) that pass when run alone — timeouts under CPU load from a
  parallel Gradle build. Re-run the full suite on an idle machine before committing.
* PWA tests added: `CashFlowPage.test.tsx` (filter, material debts, unsigned client rows),
  `useObjectMoney.test.ts` (`applyMoneyDelta`). Version bumped to **1.69.0**.
* Backend: first full `./gradlew build` → 2325 tests, 4 failed in `ObjectExpenseServiceTest`
  (acts axis now subtracts the material split from an unstubbed received sum) — fixed + new test
  `theWorksAxisLeavesTheClientsMaterialMoneyOut`. Second full build was running.
* Portal: the payments card's «+ X ₴ повернення за матеріал» now reads «+ X ₴ — гроші на матеріал».
* Docs done: CLAUDE.md (V182 + «Мої гроші» bullet), `architecture-index-detail.md` twin,
  `open-questions.md` (`ExpenseRequest.source` RESOLVED; new «Payment plan hidden …» item).

### Verified (2026-11-02, evening)
* Backend full `./gradlew build` — **BUILD SUCCESSFUL** (all tests incl. Testcontainers ITs).
* PWA full mirror of CI — lint, tsc, typecheck:tests, **vitest 1750/1750**, vite build — green.
* Mobile 375 px (Playwright against local bootRun + vite dev, dev account `pro@majstr.dev`): object card,
  «+» chooser, «Отримав» form, «Мої гроші» with filter + material line, filter modal, personal sheet
  with photo buttons — no horizontal overflow. «📷 Сфотографувати» wrapped → label shortened to «Камера».
* Local DB note: it held the bot's `V181__majster_bot.sql`; its history row was moved to
  `V183__majster_bot.sql` and the app was started once with `SPRING_FLYWAY_OUT_OF_ORDER=true`, which
  applied master's V181 + V182. Prod is unaffected (migrations apply there in final order).
* SPEC.md (section F) and PROMPTS.md updated.

### Owner's local test round (2026-11-03)
* **«Мої гроші» split into objects and «Особисте»** — `CashFlowResponse.objects` (per object, most
  recently active first) + `personal` (null when none), cut by the same «own money» rule, so
  Σ objects + personal = the top three (`CashFlowServiceTest`). PWA: «Заробив» shows «по обʼєктах /
  особисте» when both exist; an «Обʼєкти» card (one line per object: earned + the client's material
  balance, folded rows) and an «Особисте» card; title panels `bg-brand-soft`, no other colour coding
  (owner's call). `applyCashDelta` moves the slices too. Filter «Без обʼєкта» renamed «👤 Особисте —
  без обʼєктів». The old «Клієнти винні за матеріал» card folded into the object lines.
* Row wording: a till receipt of the client's is «куплено для клієнта» (was «клієнт поверне» — wrong
  when he paid ahead). The hint under the totals says «Клієнти дали на матеріал X» (what came IN).
* «Гроші обʼєкта» rows fold under «✓ Отримано · N» / «Витрачено · N»; crew warning removed.
* A vision-read receipt date more than a year back or in the future is dropped
  (`ReceiptRecognizeResponse.read`); the receipts page marks an undated priced receipt.
* Unrelated bugs found on the way: the receipts cache was patched as an array («old?.map is not a
  function», on prod too); inline cells now share one history entry (`useInlineSession`) — a mouse
  click to the next cell used to close it at once; receipts «←» returns to «Економіка».
* **Material money left over pays the receipts on signed acts** (owner-approved model change):
  `MaterialRefundSplit.of(refunds, reimbursable, actMaterials)` — till receipts first, the rest
  into `toActs` (≤ Σ receipts on SIGNED acts, `sumSignedActReceipts`), `applied = refunds − toActs`,
  `outstanding = reimbursable − applied`. Works axis / payments summary / portal read it through
  `MaterialRefundCalculator` unchanged; the portal names it (`materialToActs`). «Мої гроші» /
  «Гроші обʼєкта»: NOT income (owner's second ruling — a first cut showed a derived «Зараховано в
  акт» income row and it read as the money arriving twice) but `Entry.clientShare` taken off the
  act's own expense rows, oldest first: **V184** `object_expenses.work_act_id` (set by
  `ActAddendumCreator`, backfilled from the «Чек до акта № N:» note); an act that posts no expenses
  spreads it over its receipts. Row: «Чек до акта № 9 −12 151,72 · 16 388 · з них 4 236,28 з
  грошей клієнта на матеріал»; the edit sheet opens on the paper's 16 388. Balances carry `toActs`;
  the object's material line says «в акти X»; act writes now invalidate the economy / object money
  / cash queries.
* **Migration numbering:** V183 stays reserved for the bot branch, which is ALREADY applied in the
  local DB as `V183__majster_bot.sql`; this round took **V184**. Prod will apply V184 with V183
  missing — fine — so when the bot merges its migration takes the next free number (V187 since V186 landed); the local history row now sits at V1000.

## Remaining
1. **NOT committed** — everything sits as uncommitted changes on `master` in both repos. A local commit
   was made by mistake and undone (soft reset) at the user's word: no commit without the user's «ок».
2. **The user tests locally, then gives the word to commit and push.** Push backend first (Railway runs
   V182), then PWA.
3. Phase 2 list above.

## Review follow-up (2026-11-04, PWA 1.69.2)
* **Portal shows no plan stages.** The PWA lost its payment plan, yet the portal still listed an old
  stage as «наступний платіж» and could mark it overdue, with no way for the master to remove it.
  `PaymentsCard.payments`/`PaymentRow`/`unplannedReceipts` became one `receipts` list of the money
  that ARRIVED (label: the receipt's own, else its old stage's purpose, else «Оплата»); the portal's
  «наступний платіж», overdue dots and >5 expand toggle are gone. Totals are unchanged — they were
  always contract-based. No data rewrite: the 8 prod stage rows stay.
* **«Показувати платежі клієнту» hint** no longer promises «графік виплат».
* **Offline material balance** (`applyMoneyDelta`) now keeps the server's `toActs`: outstanding =
  bought − given + toActs, with the cached share capped at what is left after the till receipts
  (exact when the leftover shrinks; when it grows the next refetch brings the larger share).
* **«Гроші обʼєкта» reads only the object.** `collect(owner, from, to, projectId)` passes the object
  into the five feed queries and the three material sums (`AND (:projectId IS NULL OR … = :projectId)`)
  and skips `cash_entry` (it belongs to no object). Still ONE formula — «Мої гроші» passes null; a
  «Мої гроші» filtered by object now also reads only that object.
* `PaymentsBlock.tsx` (and its two test files) deleted.

## Phase 2 (after the webinar)
* ~~Delete dead plan code~~ — DONE 2026-11-05 (PWA 1.69.3): stage CRUD, split preview/commit, overflow
  resolution, surplus transfer and `surplusIdOf` removed; the import's deposit is a receipt «Завдаток»;
  `project_payment` kept read-only for the names of receipts recorded against a stage.
* One door for receipts (act / estimate / object) — the «same paper three ways» problem.
* Merge the bot (renumber its migration to the next free number — V187 as of V186, PWA version above 1.69.0; its expense/payment
  writes land in «Гроші обʼєкта» automatically). Since the plan is gone, its `InboxService.incomeRequest`
  must stop matching a stage (`ProjectPaymentRepository` and the 6-argument `PaymentReceiptRequest` no
  longer exist) and send the text as the label.
* If masters ask for a payment schedule: one plan per object, payments close stages in order.

## Gotchas met
* Bash heredocs break on apostrophes in this environment — write scripts with the Write tool.
* `./gradlew` works from the PowerShell tool with `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1'`.
* Real branch is `master` (not `main`) in both repos.
