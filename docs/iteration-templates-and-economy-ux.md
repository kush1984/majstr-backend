# Iteration — economy checkbox (FREE) + template filters + new default templates

Three master-requested items in one pass.

## 1. Economy checkbox on estimate rows — disabled for FREE, better on mobile

FREE masters can't see the object economy, but they still saw the per-estimate
"враховувати в економіці об'єкта" checkbox (and it was hard to tap on a phone).

- `ProjectDetailPage` now reads `me`/`isPro` and passes `economyLocked={!isPro}` to
  `EstimateRow`. The checkbox is **kept, not removed** — disabled + dimmed with a
  `🔒 PRO` hint for FREE (consistent with the locked economy teaser below).
- **Bigger tap target** (mobile-first): the toggle is now `min-h-[44px]`, `py-2.5`,
  `gap-2.5`, a `h-5 w-5` box (was `h-4 w-4`), plus an `active:bg` press state.

## 2. Templates page — trade filter chips

`TemplatesPage` now shows `TradeFilterChips` at the top (same component/pattern as
the catalog), built from the trades **present in the templates** (own + defaults),
so the filter never shows an empty option and hides itself when fewer than two
chips would appear. Selecting a chip narrows both "my templates" and the default
groups; empty selection = all. The "My templates" empty-state keys off the
unfiltered total so a filter doesn't read as "you have none".

## 3. New default templates + catalog positions (V56)

A web pass over Ukrainian contractor price lists (2026) surfaced common jobs the
existing defaults didn't cover. **ADDITIVE ONLY** — brand-new templates and
catalog positions; no existing template edited or split (per the rule). Positions
land in the default catalog **by category** (version 7); template item names match
catalog names so prices resolve per master.

- **8 new default templates** across trades: `Відеонагляд`, `Генератор та АВР`,
  `Зарядка електромобіля` (ELECTRICAL); `Септик автономна каналізація`,
  `Свердловина насосна станція` (PLUMBING); `Мікроцемент` (PAINTER); `Епоксидна
  підлога` (FLOORING); `Арка та декор ГКЛ` (DRYWALL).
- **28 new catalog positions** (version 7), categorized (Відеонагляд, Генератор та
  АВР, …). One position (`Монтаж насосної станції`) already existed in the default
  catalog (PLUMBING/WORK/PIECE, v2) — **not** re-added (no duplicate); its template
  item resolves from the existing entry.
- Items linked to templates by `JOIN (VALUES …) ON name` (no literal UUIDs; same as
  V54), guarded by `NOT EXISTS (items for that template)` so it only ever populates
  the new, empty templates.
- Prices are orientative UAH hints the master refines. All positions are WORK.

### Validation
- V56 dry-run on the live dev DB (`BEGIN … ROLLBACK`): 28 catalog / 8 templates /
  29 items; **0 duplicate catalog names** introduced; **0 template items** without a
  matching catalog name+type+unit (all resolve, incl. the reused насосна-станція).
- Noted (pre-existing, not from V56): a legacy duplicate `Щебінь гранітний фракція
  5-20` (BUILDER ×2) already in the dev DB — separate data cleanup, out of scope.

## 4. Economy counting is default-ON (V57)

Follow-up to (1): the master wanted every estimate's checkbox **pre-checked** on
PRO (regardless of status), except a **consolidated** estimate; on FREE the boxes
show **unchecked + disabled**, and switching to PRO should immediately reflect them
all in the economy.

- **Model flip** — `Estimate.countInEconomy` now defaults **true** (was false). The
  only built-in exclusion is a consolidated estimate: `EstimateService.consolidate`
  now creates the rollup with `false` and **leaves the sources counted** (was the
  reverse). `PublicEstimateService.sign` no longer force-sets the flag (default
  handles it, and a signed consolidated must stay excluded). The `count_in_economy`
  flag itself is the "is-rollup" marker — no separate column needed.
- **V57** — `ALTER … SET DEFAULT TRUE` + backfill existing (`= true where false`:
  62 rows in dev). Blanket true is deliberate: a pre-existing consolidated estimate
  has no persistent marker (rollup items are copied, not linked), so it's set counted
  too — acceptable (rare feature; the master unchecks that one rollup).
- **Client** — `EstimateRow` renders `checked = economyLocked ? false : countInEconomy`,
  so FREE always shows unchecked+disabled and PRO shows the real default-on state;
  upgrading needs no write, the stored defaults already read "all except the rollup".
- Tests: `EstimateServiceTest.consolidate_excludesRollupAndKeepsSourcesCounting`
  (rollup false, sources true); `PublicEstimateServiceTest` sign comment (default-on).

## PWA (v0.10.1)

Tests: `TemplatesPage.test` — new "trade filter chips narrow the list". Economy
checkbox changes are presentational (disabled/unchecked-for-FREE + tap-target),
covered by tsc + the full suite. tsc + vitest + build green.
