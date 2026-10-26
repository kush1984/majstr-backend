# Inline editing (spreadsheet-style rows) + «Оновити каталог» card

**Status:** IN PROGRESS — stages 1-2 done (not pushed); 3 and 4 next. UI freeze 4 November, meetup
11 November. Phone (PWA) only.
**Rule:** «Що видно в рядку — редагується в рядку. Діалог — для решти.»
**Flags:** `INLINE_ITEM_EDITING`, `CATALOG_SYNC_CARD` in `majstr-pwa/src/lib/featureFlags.ts`.

## Stage 0 — recon (2026-10-26), owner rulings

- A line is edited through `PUT /api/estimates/{id}/items/{itemId}` with the WHOLE line; the PWA's
  `useUpdateItem` already goes through `offlineMutate` with an optimistic `recompute`. A cell sends
  the line as it stands with one field changed (`inlineItem.ts:itemRequest`).
- `source_unit_price` (the crew price) has no editor at all: it is provenance written by «Копія з
  націнкою», and `EstimateItemRequest` has no such field — no cell can send it.
- Matching a line to the master's catalog: variant **(а)**, reliable now — exact
  `lower(trim(name))` + type + unit, the key template apply prices by and the unique index of
  `catalog_items`. `CatalogNameKey` (the fuzzy dedup key) is NOT used. `catalog_item_id` is an
  open question for after the meetup.
- Templates carry only name / type / unit: inline edits the NAME only, into the explicit-save draft.
- Rulings: an ordinary line may be 0 in quantity and price (negative still 400); a catalog price of 0
  or empty is never saved; a «%» cell edits the magnitude and keeps the sign; a cell is editable
  exactly where the server allows the edit (not signed — an ADDENDUM is always signed).

## Stage 1 — the cells (PWA `abb6eb1`)

`InlineNumberCell` / `InlineTextCell` + `inlineSession.tsx` + `lib/inlineEdit.ts`: comma or dot, uk
grouping, 2 decimals for money and 3 for a quantity/percent, empty = 0, a minus or letter = red frame
and nothing saved; save on blur / Enter / «Далі ↓» / `visibilitychange → hidden`; cancel on Esc /
system «Назад» (a history entry, as `Modal` takes); an unchanged value sends nothing; only the last
change is undoable (`toastUndoable`, 5 s).

## Stage 2 — the estimate

- Backend: `EstimateItemRequest` — an ordinary line's `quantity >= 0` and `unitPrice >= 0`; a «%»
  line keeps its rules (not 0; only «% від кошторису» negative; a manual base above 0).
  `EstimateItemRequestValidationTest`; `TemplateLineEndpointTest` now pins «a minus is refused».
- Zero lines downstream, checked: the PDF prints «0» / «0,00», the portal «0» / «0,00 ₴»; nothing
  divides by a quantity, totals are unaffected. There is no Excel export of an estimate. Nothing
  changed.
- PWA: `InlineItemBody` — name · quantity · unit · price · sum · «⋯» (the old dialog). A typed sum
  becomes price = sum / quantity at the kopeck; when that does not give the typed sum back, the row
  says so and offers «Округлити ціну» (nearest whole hryvnia). Quantity 0 → the sum is read-only with
  «Спершу вкажіть кількість». A quantity typed over measurements sets `quantityManual` and shows
  «✎ вручну». A «%» line edits only its percent, sign shown beside it. `inline_edit_commit` (field +
  screen only) in PostHog.

## Not verified yet

The 375 px browser check failed (the automation's screenshots timed out and the window would not
resize). Pending the owner's manual check on Android Chrome and iPhone Safari: the keyboard does not
cover the active cell, «Далі ↓» walks the row, «Назад» closes the cell, backgrounding saves, offline →
online syncs, undo, flag off = old behaviour.
