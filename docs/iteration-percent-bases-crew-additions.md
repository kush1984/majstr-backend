# «% від кількох позицій», a markup copy's additions, and reopening a signed estimate — V221

**Status:** built, not pushed. **Migration:** `V221__percent_of_several_positions.sql`. **PWA:** on top of 1.87.0.

## Why

The owner (2026-11-23), on a phone screenshot of the «%» form with one position picker:

1. «Тут треба дозволяти вибирати з декількох позицій».
2. «Коли ми робимо кошторис з націнкою і потім ще добавляємо позицію надбавки 10 відсотків, то воно ставить
   чогось бригаді тоже + тих 10 відсотків… ту позицію я собі додав як прораб, бригада навіть не знає про неї,
   тобто все що іде нове в тому новому кошторисі і до клієнта, то все мій заробіток як прораба.»

Rulings: every new line in the copy is his (ordinary works too, not only «%»); the client sees the names of
all the positions a percentage is measured against.

## What shipped

### A percentage of several positions

- `estimate_items.percent_base_item_ids uuid[]` replaces `percent_base_item_id` (copied over, self-checked,
  old column dropped). The amount is the share of the lines' sum, in the order ticked.
- One base gone = the line is detached and keeps its amount (`EstimateMath.sumOf`; delete path
  `detachPercentagesPointingAt`). An array has no FK, so that path is now the only guard.
- `EstimateItemRequest.percentBaseItemIds`; an older build's single `percentBaseItemId` is still read
  (`baseItemIds()`); the response carries both (the single = the first, for installed apps).
- The row, the PDF and the consolidation label name every base: «від «A», «B»».
- A markup copy raising some bases and not others re-sets the percent to `p·f·(M+U)/(f·M+U)` so the copy
  charges exactly the parent's amount × factor (the old two rules are its ends).
- Acts carry such a line by the closed share of all its bases; the tile calculator reads each base.
- Signed snapshots: new ones store `percentBaseItemIds`; older ones are read through `baseItemIds()`.
- PWA: «Від позицій» is a list of the estimate's lines with checkboxes («Можна кілька — відсоток рахується
  від їхньої суми»); the preview counts them when several («25 % від 3 позицій (21 600 ₴) = 5 400 ₴").
  `recomputeLines` mirrors the sum (`sumOfBases`).

### What the master adds to a markup copy is his

- `CrewMarginCalculator`: a line with no crew price (`source_unit_price` null) is ZERO in the crew view —
  «Бригаді» stays at the crew's prices and the whole amount is «Твоя націнка»; a discount he added comes
  out of his margin. `sumSignedActMargin` reads such a line's crew price as 0, so an act accepting it
  accepts all of it. Mirrored in `crewMargin.ts`.
- The note under the margin now reads «N позицій на X додано тобою, без бригади — уся сума у твоїй націнці».
- If the crew does a new work, its price belongs in the crew's original sheet.

### Reopening a signed estimate is back

Two masters asked to fix or delete a signed estimate. The owner: allowed even with payments on the
object, never with acts — «якщо є акти створені, то однозначно не дозволяємо».

- «Перевідкрити для змін» is shown again (editor banner, object row menu). Confirmation: the client signs
  again, the earlier signature stays in history, payments on the object stay, and «За договором» leaves
  the estimate out until the new signature. A signed estimate is deleted by reopening it first.
- Server rules unchanged: any act not REJECTED refuses it. A DRAFT act alone now answers
  `ESTIMATE_HAS_DRAFT_ACT` — «спершу видаліть чернетку акта»; SENT / SIGNED stay
  `ESTIMATE_HAS_SIGNED_ACTS`.
- Money is the object's (V100), so nothing about payments moves: during the gap the economy panel reads
  the deposit as an advance not yet earned.
- The signed banner puts its button under the text (on a phone the label broke into four lines), and a
  «%» row keeps «25 %» on one line beside a long list of bases.

## Tests

- `PercentOfSeveralPositionsIntegrationTest` — sum and order, invalid bases dropped, the single id, a
  deleted base, a markup copy raising one of two bases.
- `EstimateMathTest`, `CrewMarginIntegrationTest` (new rule: added line, «+10 %», parity fixture,
  accepted by an act); PWA `percentMath.test.ts`, `AddItemSheet.test.tsx`, `crewMargin.test.ts`.
- `WorkActIntegrationTest`: a DRAFT act refuses with `ESTIMATE_HAS_DRAFT_ACT` and stops refusing once
  deleted; a SENT act refuses; reopening with a deposit keeps the money, drops and restores «За
  договором», and deleting afterwards keeps the money.
- 375 px screenshots: the row label with two bases, the form with three ticked, the signed banner and
  the reopen confirmation.
