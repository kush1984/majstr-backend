# Iteration: estimate trade tree — trade snapshot + category collapse + trade badge on ≥ 2 trades

**Status:** all four phases code complete. Backend green (`./gradlew build`), PWA gate green
(vitest + build), portal JS syntax-checked.
**Source:** the "Trade grouping on estimates" idea (green-progress-strip iteration comments,
2026-09-04), promoted by the master with «робимо для більше рівно двох, але все по дефолту
розгорнуте» (2026-09-04).
**Migrations:** **V125** `estimate_items.trade` (nullable snapshot + backfill).
**PWA:** planned **1.38.1 → 1.39.0** on completion (minor — new capability on every estimate
board: collapsible categories; trade badge when applicable).

---

## 1. Why this cut is BADGE + COLLAPSE, not a full tree

The master's original idea was a nested three-level tree (`Trade → Category → Item`) with drag at
every level, mirrored across estimate board, portal and PDF. On honest inspection two facts pushed
us to a smaller shape:

- **~95 % of masters are single-trade** (a painter is a painter, a tiler is a tiler). The grouping
  we ship must not clutter the 95 % just to please the 5 %.
- **No real master today runs an estimate spanning two trades** — no signal for full-tree UX. The
  cheap read-side change captures 70 % of the value at 5 % of the risk (no drag semantics to design,
  no reorder API surface, no portal drag).

So what actually ships:

- **Trade badge on category headers** — visible only when the estimate carries ≥ 2 distinct non-null
  trades. On single-trade sheets nothing changes.
- **Collapsible categories** — on both the estimate board AND the portal (PDF untouched). All
  expanded by default. Collapse state kept in `localStorage` keyed on estimate id — remembers what
  the master collapsed, no backend.
- **Trade is a snapshot on the line** (V125), same rule as name/unit/price/category: the client
  signed THIS wording, so re-classifying the catalog position later must never change what a signed
  estimate says.

The full tree with drag stays as an open-question, promoted only when a real multi-trade master
asks for it. This iteration doc pins the shape so we do not silently drift back into the big design.

## 2. Phase 1 — backend (this commit)

### V125 migration

`ALTER TABLE estimate_items ADD COLUMN trade VARCHAR(50);` — nullable. A CHECK guards the values
against the same enum literals `catalog_items.trade` accepts (V30/V33/V54). A covering index on
`(estimate_id) INCLUDE (trade)` keeps the "count distinct trades per estimate" read cheap.

**Backfill** is one native `UPDATE ... FROM (join catalog_items on owner, lowercased name, type,
unit)`. Rules:

- **Owner scope is load-bearing** — the join goes through `estimates.project_id → projects.owner_id`
  and matches the master's OWN catalog. A stranger with a same-named catalog row must never bleed
  through (pinned by `EstimateItemTradeBackfillOnLiveDataIntegrationTest.ownerScope_isEnforced_…`).
- **NULL is the fallback, NOT `OTHER`**. NULL means "we don't know"; OTHER means "the master's
  catalog says this is «Інше»". Conflating them breaks the "≥ 2 trades → show the badge" count,
  because a single-trade sheet with unlabelled rows would falsely count 2 trades.
- **Signed estimates are included** — the master decided (2026-09-04) that even old signed sheets
  show the trade grouping when the client re-opens the link. Only visual — line_total, name, unit,
  price are untouched.
- **`lower(trim(...))` on both sides** — the same rule the `ux_catalog_items_owner_name_type_unit`
  index trims on. A single space or a capitalised name must never leave a line unlabelled.

### Entity + response

`EstimateItem.trade` (nullable `@Enumerated` string). `EstimateItemResponse` gains the field between
`category` and `description`. `EstimateItemResponse.from` returns it as-is (nullable).

### Snapshot on every write path

The rule matches `category`: the trade comes from the source. Where the source is a catalog row,
we copy `source.getTrade()`; where the source is an import batch (dictation commit, receipt
import, Excel import, template apply), we consult a per-master trade index built from that master's
own catalog by (name, type, unit) — one SQL per commit, matched in Java. A miss stays NULL.

Every builder call was audited:

| Site | Source of trade |
| --- | --- |
| `EstimateService.createFromImport` | `resolveTrade(name, type, unit, tradeIndex)` |
| `EstimateService.duplicate` (per line) | `item.getTrade()` |
| `EstimateService.copyForConsolidation` | `item.getTrade()` |
| `EstimateService.addItem` (manual) | `resolveTrade(...)` — typed line still gets its trade if it names a real row |
| `EstimateService.addItemFromCatalog` | `source.getTrade()` |
| `EstimateService.addItemsFromCatalogBatch` | `source.getTrade()` |
| `EstimateService.appendItems` (dictation + receipt) | `resolveTrade(...)` |
| `EstimateTemplateService.applyTemplates` | `match != null ? match.getTrade() : null` |
| `ActAddendumCreator` (both loops) | deliberately NULL — an ADDENDUM row is filed under a work document, not a trade; comment pins the reason |
| `DevDataSeeder` | NULL (dev only) |

**`tradeIndex(ownerId)`** in `EstimateService` is one small helper that loads the master's catalog
once and returns `Map<key, Trade>` where key = `lower(trim(name)) + "|" + type + "|" + unit`. Same
normalization rule as `CatalogMatcher.normalize`. `Locale.ROOT` on the lowercase call — the Turkish
`I`/`ı` trap that the dictation matcher already covered.

### Tests

`EstimateItemTradeBackfillOnLiveDataIntegrationTest` (7 tests) — same *OnLiveData* shape as the
existing whitespace and painter migration tests: migrate a scratch database to V124, seed rows of
every case (matched, unmatched, signed, whitespace variant, case-only variant, stranger's
same-named row), migrate to head, assert per-row trade. Also asserts the CHECK constraint exists.

`./gradlew build` — green.

## 3. Phases 2-4 (pending)

### Phase 2 — PWA estimate board

- Add `trade: Trade | null` to `EstimateItemResponse` (PWA `api/types.ts`).
- New `TradeBadge` component. Colours per trade (map on `Trade` enum).
- `estimate.trades = distinct non-null trades over estimate.items`. Badge rendered on category
  header only when `trades.length >= 2`.
- Category header becomes a `<button>` toggling a collapse state kept in
  `localStorage[`estimate:${id}:collapsed`]` as a `Set<string>` of category names. All open by
  default (empty set).
- Collapsed category shows its name + item count + subtotal, no rows.
- Tests: badge shows only on ≥ 2 trades; collapse toggle persists across refresh; single-trade
  estimate looks identical to today.

### Phase 3 — client portal (SHIPPED)

`static/portal/index.html` mirrors the collapse + badge rules. No drag, no editing — read-only.

- **`TRADE_LABELS`** inline: same 11 entries as the PWA's `trades.*` i18n block. Change one, change
  the other. Named in the file comment because the two views drift silently otherwise.
- **`.trade-*` CSS classes** duplicate the PWA's `CLASS_BY_TRADE` palette (soft background,
  saturated text). Same rule as the paybar CSS twin — the file lives in this repo, the PWA lives in
  the other; a comment in both names its counterpart.
- **`estimateHasMultipleTrades(items)`** — the same "distinct non-null trades ≥ 2" rule as
  the PWA's `showTradeBadges`. NULL does NOT count as a trade.
- **Collapse structure** — one `<tbody>` per section, `.collapsed` class on the tbody hides its
  item and footer rows via CSS (`tbody.collapsed tr.section-body { display: none }`). A tbody-per-
  section is deliberate: a plain sibling selector on `[data-section-body]` would leak between two
  sections whose category names happen to match across estimates on the same page (the
  multi-estimate route). Header stays in the DOM at all times — fold is one class flip, never a
  DOM insert or re-render.
- **Delegated click** on `tr.section[data-section-toggle]` at document level. Enter/Space on a
  keyboard-focused header trigger the same handler.
- **`localStorage` key** = `portal:estimate:${estimateId}:collapsed`. Per-origin (portal's origin)
  so it never touches the master's app state. For legacy single-estimate `?t=` links `est.id`
  arrives as `null`; the fallback key becomes `legacy:${location.search}` so fold state is scoped
  to THAT `?t=` token — otherwise two different single-estimate links would share their fold state
  and a folded «Каркас» from estimate A would appear folded on estimate B.
- **Acts untouched** — `renderAct` does not go through `renderItems`, matching the master's
  decision that trade level is not shown on acts.

### Phase 4 — dictation trade picker + feedback bundle (SHIPPED)

Live-test feedback bundled into phase 4:

1. **Bigger chevron** on collapsible category headers, PWA (`text-base leading-none` + `w-4`) and
   portal (`font-size: 1.05rem`). Master feedback 2026-09-04: «іконка згортання дуже маленька».
2. **First-letter capitalization** on an unmatched dictated name — `capitalizeFirst` in the sheet's
   `toDrafts`. Web Speech returns lowercase; the review reads like an estimate line the master will
   sign, not a chat log. Matched rows keep the catalog's own casing.
3. **Auto-restart the mic on `onend`** in `useSpeechDictation` — a `wantListenRef` tracks the
   master's intent, `stop()` clears it, `onend` re-arms 200 ms later if the intent is still set.
   `continuous: false` stays (iOS hangs otherwise); this simulates the longer listen the master
   expects («дуже скоро обривається конекшин»). A real error (`not-allowed` / `audio-capture` /
   `network`/service) clears intent so we never loop into a broken state.
4. **Trade badge on matched dictation rows** — `DictationParseResponse.DictationItem.trade` gains
   the matched catalog row's trade (V125); `DictationSheet` renders `<TradeBadge>` on every matched
   row so the master spots a wrong-trade match at a glance («в каталозі він під трейдом сантехніка,
   чому тут не видно»).
5. **Trade dropdown on save-to-catalog** — under the tick, visible only when it is checked AND
   priced. Value is a discriminated key: system trade names ride as their enum value; custom
   trades ride as `custom:<id>` (a custom trade always maps to system trade `OTHER` under the V91
   invariant). Master's own trades + custom trades are offered — same list `ProfileEditModal`
   shows. Default target trade with no picker touched stays `OTHER`, so a master who never opens
   the dropdown gets identical behaviour to phase-1 shipping.

Not adding (deferred to open-questions): a per-line trade override on the estimate row editor,
and a synonym/rename UX for a mis-picked target — a wrong trade today is fixable via the catalog
board.

## 4. Not yet verified

- **PWA changes not yet started** — phase 1 is backend-only. Estimate board renders `EstimateItemResponse`
  without knowing about `trade` yet; the field is present in JSON but ignored.
- **Real master feedback** — the shape (badge on ≥ 2 trades + collapse) is my recommendation; the
  master approved it, but nobody has used it yet.
