# Object economy — expenses + real profit (PRO, V42)

- **Status:** 🔨 Backend code complete; `./gradlew build` pending at the gate. V42
  migration **drill assertions added**. PWA verified: **tsc + vitest(73) + vite build green.**
- **Migration:** `V42__add_object_expenses.sql`.
- **Goal (user prompt, 2026-07-06):** a per-object expense journal + a real-profit
  summary (income from the object's estimates − expenses). The master taps "+ Витрата",
  enters amount/type/note, and sees how much they actually earn on each object. FREE
  masters see a locked teaser + upgrade CTA (new painted-door trigger). v1 = **fact**
  (real spend), not plan-margin.

## Backend

- **V42:** `object_expenses` (id, `object_id` FK → projects **ON DELETE CASCADE**,
  `amount NUMERIC(15,2) CHECK ≥0`, `category CHECK MATERIALS|LABOR|OTHER`, `note`,
  `spent_at DATE default today`, `created_at`) + index on `object_id`. Money is the same
  `BigDecimal(15,2)` as estimate prices — no new format.
- **PRO gate — reuses the existing mechanism.** New `Feature.OBJECT_ECONOMY` granted to
  PRO+TEAM in `PlanConfig`; every economy/expense entry point calls
  `featureGuard.requireFeature(user, OBJECT_ECONOMY)`. It's **plan-gated**, so an
  admin-granted **dateless PRO** qualifies. FREE → `FeatureNotAvailableException` → **403
  with `code: UPGRADE_REQUIRED`** (added the code to the feature-gate advice so the PWA can
  branch). The gate fires **before** any object read.
- **CRUD** (`ObjectExpenseService`, owner-scoped via `ProjectService.loadOwned`):
  `POST/GET/PATCH/DELETE /api/projects/{id}/expenses` (used `/api/projects`, not the
  prompt's `/api/objects`, for consistency with the existing project API).
- **Economy** `GET /api/projects/{id}/economy`: `incomeTotal` = SUM of all the object's
  estimates' line totals **except REJECTED**; `incomeSigned` = SIGNED only (shown
  alongside); `expensesTotal` + `expensesByCategory`; `profit = income − expenses`,
  `profitSigned` for reference. Income is one aggregate native query per figure (round per
  line, matching the estimate view); expenses one grouped query — no N+1.
- **Downgrade PRO→FREE:** expenses are **never deleted** (master's data is sacred). After
  a downgrade the block is locked and the data is inaccessible (server 403) until PRO
  returns — then everything is exactly as it was.

## Client isolation (critical)

- Economy is a **project-level, owner-only** surface. It is added to **no** estimate /
  portal / PDF / share DTO. The share portal (`PublicEstimateView`) is estimate-based and
  carries no expenses/profit. A regression guard (`PublicEstimateIsolationTest`) reflects
  the whole public-DTO record tree and fails if any `expense/profit/economy/cost/margin`
  field ever appears there.

## PWA (mobile-first)

- `ObjectEconomySection` on the object screen (before the questions section):
  - **PRO:** three figures — Дохід / Витрати / **Заробіток** (accent brand; negative in
    red — honest); "з них підписано: X ₴" under income; a category breakdown line
    (Матеріали X · Робота Y · Інше Z, no charts in v1); the expense journal (date, type
    icon, amount, note) with tap-to-edit + a delete (confirm).
  - **+ Витрата** quick sheet (`ExpenseSheet`): amount (numeric keyboard, autofocused) is
    the only required field; three category chips (default Materials); optional note; date
    defaults to today. Doubles as the editor.
  - **FREE:** the same block, **locked** — lock icon + one sentence + "Відкрити PRO"; click
    records `upgradeApi.click('OBJECT_PROFIT')` and opens the upgrade modal. **No real
    figures shown** (economy query disabled for FREE).
- i18n uk + en; brand tokens; ≤375px comfortable. App version → **0.4.0**.

## Not changed / confirmed

- Object screen, estimates, statuses, portal/PDF/share — economy is purely additive.
  FREE object/estimate limits untouched. `upgrade_event.trigger_source` is free-text
  VARCHAR(40) — `OBJECT_PROFIT` needs **no migration**. Object delete cascades expenses
  (FK). `EstimateStatus` = DRAFT/SENT/SIGNED/REJECTED (no archive) → income excludes
  REJECTED only.

## Tests

- `ObjectExpenseServiceTest` — FREE blocked before any read; PRO add persists with
  defaults (spent_at today, note trimmed); economy math (income excludes rejected;
  profit = income − expenses; signed variant). `DefaultFeatureGuardTest` — OBJECT_ECONOMY
  is PRO+. `PublicEstimateIsolationTest` — no economy leak in the share DTO. Drill: V42
  table + cascade FK + index. PWA: `ObjectEconomySection.test` — FREE teaser (no figures,
  OBJECT_PROFIT on click) vs PRO panel (fetches, shows figures + journal).

## Verify (after backend build green)

1. PRO master: "+ Витрата" (amount+type in seconds) → appears in the list; Дохід/Витрати/
   Заробіток recompute.
2. Income: a REJECTED estimate is NOT in the sum; "з них підписано" shows SIGNED separately.
3. FREE master: locked block, no figures; "Відкрити PRO" → upgrade modal; admin sees an
   `upgrade_event` with trigger `OBJECT_PROFIT`.
4. Client portal / PDF / share link — no trace of expenses or profit.
5. Downgrade PRO→FREE → expenses alive in DB, block locked; back to PRO → all present.
6. Another master's object — expenses inaccessible (owner-scoped); delete object → expenses
   cascade.
