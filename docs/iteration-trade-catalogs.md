# Every trade holds its own positions — V220

**Status:** shipped 2026-11-23 (backend `1df6807`, PWA 1.87.0 `07a398c`). **Migration:** `V220__catalog_positions_per_trade.sql`.

## Why

The owner (2026-11-23), looking at an electrician's price list that ended in a «Плитка» section:
«щоб якщо я електрик — мені не підкидувало нічого з плитки чи штукатурки чи з інших трейдів, щоб я бачив
всі свої категорії … позиції чітко електрика, калькулятор все рахував як треба, шаблони також щоб були
чіткі — без всяких вгадувань і фокусів». Any trade, not only electrical; shared positions in the right
trade and the right folder.

The cause: the library ships 78 positions under several trades («Прибирання приміщення після робіт»
under nine), but a master's catalog held ONE row per (name, type, unit) — seeding skipped a name he
already had under another trade. The row sat under whichever trade claimed it first, and every reader
had to guess which trade he meant. The catalog screen guessed (`sharedTrades`), the estimate guessed
(`CatalogFiling` + a `trade` hint from the picker), and the readers that did not guess — the price
list, a typed line, an import, dictation — filed it under the wrong trade. The audit found 24 readers
of a row's trade; 7 applied the guess.

## What shipped

- **The trade is part of a position's identity.** `ux_catalog_items_owner_trade_name_type_unit`
  replaces `ux_catalog_items_owner_name_type_unit`. The same name under two trades is two rows, each in
  its own trade's folder, each at its own price (owner ruling: a price changed under one trade does not
  move the other).
- **V220 fills in what each master was missing:** for every trade on his profile, each library position
  of that trade he held only under another trade gets a copy — in that trade's library folder, at his
  price, with his comment and price-list tick. Nothing deleted or repriced. Dev database: 363 copies for
  48 masters.
- **Seeding and «Додати нові з каталогу»** copy per trade.
- **Nothing re-files.** `sharedTrades` is gone from the catalog response, the PWA tree shows each row
  under its own trade, and a catalog add takes the row's own trade and folder (`trade` removed from
  `EstimateItemFromCatalogRequest` and the batch `Entry`). A library row of a trade off the profile is
  hidden — catalog, pickers, price list.
- **Lines that arrive by name** (`CatalogFiling`):
  - a bundle line prices from the bundle trade's own row and files in his folder of that trade;
  - a typed, renamed, imported or dictated line: one trade he files the name under → that trade;
    several → the estimate's own trade when it is one of them (owner ruling); otherwise no group;
  - dictation, the bot and «Перенести в Majstr» match one row per name; two prices with nothing to
    choose by → unmatched, never priced from the wrong trade.
- The calculator reads a line's trade, so it now gets the right trade's norms with no change of its own.

## Not done / decided

- Existing estimates, acts and signed documents are not rewritten — they are snapshots.
- A PRICE_DRIFT «Оновити мою ціну» still updates every library row of that name (the notice has no trade).
- The autocomplete in the estimate can offer the same name twice to a master of two trades (one per
  trade); the row he picks decides the trade.

## Tests

- `TradeCatalogsIntegrationTest` — for EVERY trade the library ships: a one-trade master's visible
  catalog, all default bundles applied together, and the price list with everything ticked carry that
  trade only, and every library position of the trade sits in its library folder; a two-trade master
  holds «Прибирання» once per trade, priced apart; a typed shared name follows the estimate's trade or
  none.
- `CatalogFilingTest` (the rules), `CatalogServiceTest` (same name under another trade = a new row),
  `EstimateServiceTest` (a catalog add takes the row's trade and folder).
- PWA: `catalogTree.test.ts`, `CatalogPicker.test.tsx`, `AddItemSheet.test.tsx`.
- Migration dry-run on the dev database inside a rolled-back transaction: self-check passes.
