# Inline editing (spreadsheet-style rows) + «Оновити каталог» card

**Status:** DONE (not pushed) — stages 1-4. PWA 1.62.0. UI freeze 4 November, meetup
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

## Stage 3 — catalog and templates

- Backend, V179 `catalog_update_notices.resolution` (DISMISSED / ACCEPTED / RESOLVED; NULL on rows
  closed before, never guessed; CHECK it comes with `dismissed_at`). `CatalogUpdateNotice.close(why)`
  is the one door.
  - A master who RE-PRICES a position himself (`CatalogService.update`, so the row, the dialog and —
    stage 4 — the card) closes his open PRICE_DRIFT notice about it as RESOLVED (`resolvePriceDrift`,
    matched by `CatalogNameKey` on the old AND the new name); a rename alone resolves nothing.
  - «Оновити мою ціну» applies the new price ALWAYS (it used to move it only while it still equalled
    the notice's old price — a silent no-op on an explicit tap). This covers V176's 253 notices.
  - `PriceDriftResolutionIntegrationTest` (notices planted exactly as V176 writes them),
    `CatalogTemplateServiceTest` updated.
- PWA catalog row: name and price in the row, «⋯» for trade / category / unit / description. A price
  of 0 or empty is never saved. «Ціну/Назву в каталозі змінено · Скасувати». Estimates are snapshots
  and are not touched. The notices list refreshes through the `['catalog']` prefix.
- PWA templates: the NAME edits in the row, into the explicit-save draft (no toast); the price shows
  greyed «з каталогу N ₴» or «немає в каталозі» by `lib/catalogMatch.ts` — `lower(trim(name))` + type
  + unit, the key the server prices template lines by.

## Stage 4 — «Ціни відрізняються від каталогу»

- Backend: `POST /api/catalog/sync` (`CatalogSyncRequest {updates[], creates[]}` →
  `CatalogSyncResponse {updated, added}`), ONE transaction. Re-prices positions the master owns
  (a foreign or deleted id is skipped, so a stale batch never wedges the queue), adds new ones through
  the catalog's own upsert (client id → a replay finds its row; an existing name+type+unit is
  re-priced — the last write wins), and resolves the PRICE_DRIFT notices of everything it wrote
  (V179). No crew-price field exists in the request. `CatalogSyncIntegrationTest` (atomic, replay,
  last-write-wins, foreign id, notice, no crew field); an IDOR class-3 case (B's sync naming A's
  position leaves A's price alone). Contract snapshot refreshed and copied to the PWA.
- PWA: `catalogSync.ts` computes the card — «%» lines and 0 ₴ lines skipped, the same name in another
  unit/type skipped (not «missing»), several lines at different prices for one position skipped; only
  `unitPrice` is read. «Не зараз» stores the card's signatures per estimate in localStorage (try/catch:
  storage failing just shows the card); the card returns only for a signature not stored then.
  `CatalogSyncCard` under «Кошторис готовий?», unsigned estimates only; the sheet pre-ticks a HIGHER
  price, never a lower one («знижка клієнту?») and never a missing position. One queued `catalogSync`
  batch; «Каталог оновлено: K позицій · Скасувати» undoes the whole batch (old prices as another
  sync, the added positions bulk-deleted). `catalog_sync_apply` (counts only) in PostHog.

## Calm cells (PWA `bdabab1`, 2026-10-27)

Look only — commit/cancel, empty = 0, undo and the outbox are unchanged. At rest a value is text:
a number carries a dotted underline (`--cell-dot`), a name nothing; only the open field is framed
(white, `ring-2 ring-brand`) and its row is `--row-editing`. A section is ONE white block with
`--divider` hairlines (estimate, catalog sub-category, template composition); the session marker is
a dot by the line number; a missing quantity is «к-сть?» in the cell and «N без кількості» once in
the section header (the per-row sentence is gone). The sum follows a typed quantity/price live, grey.
**The name field opens exactly over the text**: a 16 px textarea (iOS zoom) scaled to the row's
14 px with every length divided by the same factor (`fieldStyle`), a hidden sizer for the height —
measured identical to the sub-pixel at 375 and 1440. **The caret lands at the end of the TAPPED
word** (`lib/caret.ts`: `caretPositionFromPoint` → `caretRangeFromPoint` → end; `wordEndAt` breaks on
space/punctuation/×/—, not on an apostrophe); keyboard entry = end of name; a double tap selects the
word by hand (the click count restarts on the new textarea, and the iOS rAF re-placement must
re-place the CURRENT selection). With a mouse (`pointer: fine`) a key hint replaces the bar; Tab moves
on. The name stays selectable (`select-text` overrides the base `role="button"` rule) so a long
press still selects a word.

## Not verified yet

The 375 px browser check failed (the automation's screenshots timed out and the window would not
resize). Pending the owner's manual check on Android Chrome and iPhone Safari: the keyboard does not
cover the active cell, «Далі ↓» walks the row, «Назад» closes the cell, backgrounding saves, offline →
online syncs, undo, flag off = old behaviour.
