# Iteration — «Націнка на вибрані позиції» (in-place markup on picked lines)

- **Status:** code complete; both gates run before push. Not committed (push needs the owner's word).
- **Commit:** _pending_
- **Migrations:** **none.** No new column, no new table — the new price simply *is* the price.
- **PWA version:** 1.44.2 → **1.45.0** (minor — a new headline capability).

## Goal

A master asked for it in his own words: «деколи роботи при малих обʼємах чи на висоті мають
коштувати більше… щоб не робити нових і не переписувати кошторис чи не робити дубль». The product
already had «Дубль ±%», but that answers a different question — a бригадир's two-price workflow, one
sheet at the crew's prices and one at the client's. This answers the smaller, far more common one:
a few positions in **this** estimate are worth more than the catalog says. The alternative was
opening eight lines and re-typing eight prices, and a master with eight such lines quotes the job
wrong instead.

## Shape (as agreed with the owner, verbatim on the key points)

- Menu entry beside «Дубль ±%» → the **existing** selection mode → a percent sheet cloned from the
  duplicate's → applied in place.
- **Percentages, not a coefficient.** The master types the number himself.
- **Materials are untouched** — no checkbox on a material row at all.
- **Nothing is stored and nothing is shown to the client.** No chip, no history, no flag. Catalog
  prices are not touched.
- In the sheet: the picked lines' total **before and after**.

## Work by chunk

### 1. Backend — one endpoint, one transaction

- `dto/EstimateItemsMarkupRequest` — shaped like `EstimateDuplicateRequest`: an **unsigned
  magnitude plus a direction** (`discount`), so a discount is a markup with a minus and nothing
  downstream branches on which it is. Same bounds (discount ≤ 100 %, markup ≤ 1000 %), enforced by
  an `@AssertTrue`. Unlike the duplicate, **`itemIds` is required** — there is no "all WORK lines"
  default, because a mistyped percent applied to everything by omission is not undoable.
- `EstimateService.markItemsUp` — modelled on `deleteItems`: `requireNotSigned`, filter the fetched
  lines to this estimate, multiply, and finish with `EstimateMath.recalculate`. Reuses the existing
  private `markedUp(price, factor)` helper, so the rounding is literally the duplicate's.
- `POST /api/estimates/{estimateId}/items/markup` → 204.

### 2. PWA

- `api/types.ts` + `api/estimates.ts` — `EstimateItemsMarkupRequest` / `markUpItems`. Deliberately
  **not** a loop over `updateItem`: eight positions would be eight round trips and eight chances to
  leave the sheet half-repriced.
- `lib/outbox/init.ts` — new outbox entity `estimateItemsMarkup`, beside `estimateItemsBulkDelete`.
  **Not coalesced and not idempotent**, on purpose (see Gotchas).
- `useEstimate.ts` — `useMarkUpItems`, offline-capable via `offlineMutate`. The optimistic patch
  rounds the **unit** price the way the server does, and `patchEstimate` re-runs the percent pass.
- `EstimateItemsBoard.tsx` — `Selection` gained an optional `canSelect` predicate. A row outside the
  current mode's question carries **no tick** (an empty gutter keeps it aligned), and a section tick
  answers only for the rows it can actually pick.
- `EstimateEditorPage.tsx` — selection mode gained an **intent** (`'delete' | 'markup'`), set by the
  menu entry that opened it; the sticky bar keeps exactly ONE primary action, whose label and colour
  follow the intent. New `ItemMarkupSheet` (the duplicate's sheet minus the online gate, plus the
  before → after total).

### 3. Tests

`EstimateServiceTest` — seven cases: the unit price rises; rounding to whole hryvnia (333 → 383); a
discount moves down; **a PERCENT line is left alone**; a line belonging to another estimate is
ignored; a SIGNED estimate is refused; zero percent never even reads the lines.

## Not changed / confirmed

- **`EstimateMath.recalculate` ↔ `useEstimate.recomputeLines`** — the mirrored-formula rule is **not**
  triggered. Neither side changed: the markup moves `unitPrice`, and both sides' existing percent
  pass lifts the «%» lines from there.
- **`source_unit_price` is NOT written.** That column means «what the crew's sheet charged» and
  exists for a future diff view; giving it a second meaning would poison it.
- **Work acts, PDF, portal, the material calculator** — untouched. Act lines are frozen copies by
  value, and `MaterialCalculatorService` reads only `getQuantity()`.
- **Duplicates do not cascade**, unlike a delete: a copy IS the client's sheet at the client's
  prices, and the whole point of the parent is that its prices differ.

## Gotchas

- **PERCENT lines are excluded as CORRECTNESS, not policy.** A «%» line is a share of a base that is
  itself in the list; `recalculate` already lifts it. Marking it up too would land the markup twice —
  the same trap `markedUpPercent` exists to avoid on the duplicate path. Materials, by contrast, are
  excluded by **UI policy** (the owner's call), which is why that half lives in `canSelect`.
- **Not idempotent, by design.** +10 % twice is +21 %. That is the honest reading of a master who
  picked the same line twice — the owner ruled: «якщо він вибрав ще дещо з тих що попередньо
  змінював, то значить так треба». It is also why the op carries the whole selection at once, and
  why two queued offline markups both replay: they are two decisions, and the second was typed while
  looking at the result of the first. The selection is cleared on success so a stray second tap on
  the same lines takes deliberate effort.
- **The sheet's «after» must round the UNIT price, then multiply by quantity** — the order the server
  uses. Rounding line totals instead drifts by a hryvnia per line, on exactly the many-position
  sheets this feature is for.
- **The bar could not grow a third button.** It is a single `ml-8` row with ~311 px of content on a
  375 px phone; that constraint is what produced the intent-based design rather than a wider bar.
