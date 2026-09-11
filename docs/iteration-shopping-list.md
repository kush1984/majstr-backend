# Iteration: the shopping list — «скільки чого купити» (material calculator, cut 1)

**Status:** code complete, backend build green, PWA gate green (incl. `test:e2e:offline:shell`),
NOT pushed (awaiting the user's approval).
**Source:** Prompt A of `C:\Work\prompts\materials-calculator-prompts-v4.md`, the pack we rewrote
ourselves after reviewing v3. The plan behind it is
[iteration-material-calculator.md](iteration-material-calculator.md) — this doc is what actually
shipped of it.
**Migrations:** **V126** (`shopping_list_and_material_norms`).
**PWA:** 1.39.1 → **1.40.0** (new headline capability).

Cut 1 is the **infrastructure half**: the place the answer lives (the list), the vocabulary the
calculator will speak (materials, norms, the master's habits), and the screen the master actually
uses in the shop. The DRYWALL norms themselves and the estimate→materials engine are the next cut.
Nothing here computes a quantity yet — every row today is typed by hand.

---

## 1. What shipped

### Backend

| | |
|---|---|
| `V126` | `material`, `material_norm`, `master_material_pref`, `shopping_list`, `shopping_list_item`; `LITRE` added to four unit CHECKs |
| `ShoppingListService` | the list: read, add, patch, delete, `clearBought`, `applyCalculated` (the door cut 2 will call) |
| `ShoppingListController` | `GET /api/shopping-lists/summary`, `GET/POST/PATCH/DELETE /api/projects/{id}/shopping-list[/items[/{itemId}]]`, `POST …/clear-bought` |
| `MaterialPrefService` + `MaterialPrefController` | `GET/PUT /api/me/material-prefs` — the master's habitual answers |
| `NameKeys` | the one place a catalog/estimate name becomes a norm lookup key |
| tests | 5 classes: two controller (Mockito), two integration (Testcontainers — the migration, the two-rung lookup), one unit-render coverage |

### PWA

| | |
|---|---|
| `/shopping/:projectId` | `ShoppingListPage` — full screen, no prices, fat rows |
| `ShoppingHomeCard` | «🛒 Купити» on the dashboard; renders **nothing** when there is nothing to buy |
| `ShoppingObjectRow` | one row under the object hero (deliberately not a sixth tab) |
| `useShoppingList` | the two queries + all four writes, every one of them offline-first |
| outbox | `shoppingItem` (create/update/delete) + `shoppingClear` (coalesced) |
| `CollapseGroupRow` | extracted from `PaymentsBlock` so the «✓ Куплено · N» fold is not a third copy |
| tests | `useShoppingList.test.tsx` (6), `ShoppingListPage.test.tsx` (5) |

## 2. The decisions worth keeping

**A norm has no foreign key.** `catalog_items` has no link to `catalog_templates`, and the templates
are deleted and recreated by every catalog rebuild (V82, V116, V122) — an FK to either is broken by
construction. Norms are keyed the way this codebase already joins catalog to estimate lines: by
**name and unit** (`NameKeys.of`).

**`trade` is the first rung of the lookup, never the key.** `estimate_items.trade` is nullable by
design (V125), V118 stores a position two trades both ship exactly **once** — under whichever trade
claimed it first — and both the V125 backfill and `EstimateService.resolveTrade` **derive** the
trade from (name, type, unit). So the engine asks `findByTradeAndKey`, then `findByKey`. The second
rung is the one that will do the work.

**A norm's unit is read off the position, never guessed.** The norm stores the unit it was written
against; a position whose unit differs is a different norm, not a conversion.

**`material` is a dictionary with no price and no owner** — V81 deleted invented material prices
from the default catalog («a stale guess competing with a real number is worse than no guess»).
Quantities are this feature's output; a price is born in the master's own catalog or on a receipt.

**The list never creates an expense.** Nothing here writes `object_expenses`, and a ticked row
carries no amount. Money still enters only through a receipt.

**A recalculation replaces its own contribution.** Every calculated row records
`source_estimate_id`, so re-running one estimate rewrites exactly its own rows. Without that column
a master who fixes a typo and recalculates buys double.

**A bought row is never rewritten, and «прибрати куплені» HIDES.** `clearBought` stamps `cleared_at`
instead of deleting: a deleted bought row comes back unbought on the next recalculation and the
material gets bought a second time. `edited` does the same job for a hand-typed quantity.

## 3. The screen is designed for a builders' merchant

That is a basement or a metal shed, one hand on a trolley, in a work glove:

- **Every write queues.** Ticking a row included — the outbox handles all four actions, and the add
  replays under the same `X-Entity-Uuid`, so a retry cannot leave him buying the same material twice.
- **The optimistic patch updates the home card too**, from the same write, so a row ticked offline
  does not still read «12 позицій» on the dashboard; an object with nothing left drops off the card.
- **56 px rows**, the tick box and the row body **siblings, not nested buttons** — the box buys, the
  name edits.
- **The bought rows fold away** rather than scroll past.
- **No price column.** In the shop he reads the price tag, not our forecast.
- The one online-only step, «🧾 Додати чек», is a link to the object's photos tab and appears only
  once the shopping is essentially done (`bought > 0 && open <= 1`) — the next step, not a dead end.
- An **archived** object (the list of a finished job) shows a banner and takes no writes.

## 4. Deviations from Prompt A, deliberate

1. **Cross-estimate merging is display-level, not storage-level.** The prompt described merging rows
   from several estimates into one. Storage keeps them separate, because `source_estimate_id` is
   what makes a recalculation safe (§2); merging in the table would destroy the thing that lets one
   estimate be re-run.
2. **A MANUAL row is never auto-merged into a calculated one.** Merging one would leave a replayed
   offline create with nothing to find under its `X-Entity-Uuid` — and a create that finds nothing
   creates, which doubles the money-shaped thing we were trying to avoid.

## 5. Not changed / confirmed

- **`src/sw.ts` needed no change** — the catch-all `NavigationRoute` already covers any non-`/api/`
  route, so `/shopping/:projectId` survives a refresh offline. The offline e2e still ran, because
  the prefetch set changed.
- `measurement_item_unit_check` deliberately does **not** get `LITRE` — a measurement is a
  dimension, not a volume of paint.
- No plan gate anywhere: the list is FREE on every plan. Deciding how much to buy is not a premium
  question, and a FREE master with one object is exactly who stands in the shop guessing.
- No PostHog events — the backend writes this state, and that is the rule (`iteration-posthog.md`).

## 6. Gotchas found while building

- `catalog_items` holding a shared position **once** (V118) is why the trade rung must be a fallback
  and not a filter — a DRYWALL norm must still answer for a position filed under PAINTING.
- The optimistic patch reads with `getQueryData`, computes, then writes. Assigning into a captured
  `let` from inside a `setQueryData` callback type-checks nowhere useful and hides the ordering.
- `initOutbox` also subscribes to reconnects — the shopping handlers are registered in the same
  single call, not in a second one.
- The repo asserts with `toBeTruthy()`/`toBeNull()`; `toBeInTheDocument` is not set up.

## 7. Follow-up (V128): the number a recalculation was not allowed to write

Prompt A, §5 asked for two things on a row the master had corrected by hand: his number is not
overwritten, **and** the difference is «показати… не забирати мовчки». Cut 1 shipped the first half
and dropped the second — the new figure was discarded inside `applyCalculated` with a one-line
comment. The rule looked satisfied from the database's side and was invisible from the master's:
the run where our arithmetic had improved (he fixed a typo in the estimate and re-ran, which is a
re-run *we* offer him) looked exactly like the run where it had not.

- **`shopping_list_item.suggested_quantity` (V128)** parks what the recalculation would have
  written. NULL is the normal state — «we agree with him, or have not run since».
- **It never outlives the decision that answers it.** Cleared when he takes it, when he keeps his
  own, when he types a third number, and when the position leaves the estimate altogether (the
  «gone from the new calculation» branch now clears instead of skipping). A stale offer is worse
  than none: it invites a tap that applies a figure computed against an estimate that has changed.
- **The answer is a field on the existing PATCH, not an endpoint.** `suggestion: ACCEPT|KEEP_MINE`
  rides the `shoppingItem` outbox op unchanged, so «взяти нове» works in the basement like every
  other action on this screen. Two consequences: applying it to a row that no longer carries an
  offer is a **no-op, never a 400** (the tap may replay days later), and the op carries **no
  number** — both sides read the parked figure off the row, so a replay cannot apply a figure the
  master never saw.
- **`ACCEPT` clears `edited`.** Taking our figure hands the row back to the calculator; the next run
  owns it again. Keeping his own leaves the flag exactly where it was.

Same round, the other half of §5's delta rule: a top-up row now **says** it is one.
`ShoppingListItemResponse.topUp` is derived on the read path (never stored — it is a statement
about the row's NEIGHBOURS, and a neighbour can be bought at any moment) and true when a settled
row for the same material has a **lower `sortOrder`**, so only the later row is labelled «ще 6».
Beside a bought 12, a bare «6» reads as our arithmetic having slipped.

⚠️ **`render()` had to stop using `findVisible`.** A **cleared** row is invisible and still settled
— it is exactly what makes the row beside it a top-up — so the read loads every row and filters the
visible ones in Java. The query was deleted rather than left beside its replacement.

## 8. Follow-up: a hint, deliberately not a gate (2026-09-08)

The master asked whether a material that has been transferred into the estimate and ticked «куплено»
should be locked there the way an act closes a position — and whether ticking «куплено» should be
forbidden until the estimate is signed, since an unsigned estimate can still move the quantities.
His ruling: **«підказка, без воріт»**. Nothing is blocked and nothing in the estimate is locked.

The reason a gate is wrong here is that the two documents answer to different clocks. An act is a
legal fact about work that was accepted; the shopping list is a note to himself about a trolley. He
buys before he signs all the time — that is what a deposit is for — and a screen that refuses the
tick would simply be lied to.

What shipped instead:

- **`ShoppingListResponse.sourceEstimateUnsigned`** — true when any visible row came from an
  estimate that is not SIGNED (`countByIdInAndStatusNot`, one query, computed in `render()` so
  `get`, `clearBought` and `applyCalculated` cannot disagree). The screen shows one quiet line.
- **`MaterialCalculationResponse.estimateSigned`** — the same sentence on the calculator, before he
  commits anything.
- **The top-up row now says WHY it appeared** — «Кошторис змінився після покупки». §7 gave the delta
  row its «ще»; without the reason, a master who had not touched the list still had to guess where
  a second number for a material he already bought came from.
