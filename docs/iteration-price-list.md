# «Поділитися прайсом» — the master's prices for a client

- **Status:** ✅ Backend + PWA, not pushed yet (owner tests first).
- **Migration:** V205 (`catalog_items.in_price_list`, `price_list_link`).
- **PWA:** 1.79.0.
- **Goal (webinar, 2026-11-11):** «Чи можна скинути повністю кошторис клієнту, бо деколи питають а які у
  вас ціни?» — asked three times, and answered «звісно» in public before it existed.

## What the master does

1. Catalog → **📤 Поділитися прайсом** (the fourth door, so the doors are now a 2×2 grid), or the
   profile row of the same name (`/catalog?priceList=1`).
2. The catalog tree switches to ticking, works only, the same tree he already knows — no chips:
   - the first time, every priced work he put his own price on is ticked for him;
   - a work still at **the library's price** is marked «ціна з бібліотеки — перевірте» and is not
     suggested (a price he never looked at should not reach a client by default);
   - a work at **0 ₴** is marked «без ціни — не піде в прайс» and cannot be ticked; a whole-category
     or whole-trade tick passes over it (`CatalogSelection.noteOf`, `tickableIds`).
3. «Далі» → the sheet: share (phones) / copy the link, save the PDF, «Подивитися, як побачить
   клієнт», and «Закрити посилання» while a link is live. Every way out saves the ticks first.

## What the client sees (`/portal/index.html?l=<token>`)

The master's header (name, phone, logo), «Ціни на роботи · станом на <today>», his works grouped
trade → category in the order the work goes, each with its unit and price, the position's own
explanation in grey, «Остаточна вартість — у кошторисі після замірів», «Зателефонувати» and the PDF.
Nothing to sign or ask. The page and `robots.txt` keep it out of search like every portal link.

## Backend

- **The ticks are a flag on the catalog row** (`in_price_list`): a deleted position leaves the price
  list with it; a new one stays out until ticked. `CatalogItemRepository.setPriceList` sets the flag
  for the owner's **priced WORK** rows among the ids and clears the rest — a foreign id, a material or
  a 0 ₴ row changes nothing. The read filters price > 0 again, so a price later cleared to 0 drops off.
- **The link is its own table** (`price_list_link`) — `project_share_links.project_id` is NOT NULL and
  a price list belongs to no object. One live link per owner (partial unique index); the token is
  stored raw like every share link (open question «Share-link tokens stored raw vs hashed»). A revoked
  row stays: `configured` = the owner has ever shared, after which the ticks are his own.
- **The page reads LIVE prices** — no snapshot; a catalog change reaches the client on the same link.
- `PriceListService`: `state` / `save` (gate: `CLIENT_PORTAL` + the master's name, as every share) /
  `revoke` / `publicView` / both PDFs. Storage (the logo) is read outside the transaction
  (`Transactions`), the render goes through `HeavyWork`.
- **Library-priced** = a LIBRARY row whose price equals some `catalog_templates.suggested_price` for
  the same name (`lower(trim)`), type and unit (`findLibraryPricedIds`). A master who happens to type
  the library's price is marked too; he can still tick it.
- PDF (`PriceListPdfService`): A4, the same view; the logo only with `BRANDED_PDF`, as on the estimate.
  `TradeLabel` holds the Ukrainian trade names for it (the same words as `uk.json` and the portal's
  `TRADE_LABELS`).
- Public routes `/api/public/price-list/{token}` and `…/pdf` ride the existing portal rate limiter
  (`/api/public/<kind>/<token>`) and are forced Ukrainian; the portal's monitoring scrub knows `?l=`.

## Review fixes (2026-11-13/14)

- **The name comes first**: «Далі» runs the sender gate (`useSenderGate`) before the sheet opens — a master
  without a name (every new one since V190) got a bare 403 toast on share, copy and PDF.
- **A trade taken off the profile** hides its rows from his catalog, so he cannot untick them: `viewOf` now
  skips `hidden` rows, and the picker counts and sends only the works it shows with a price (a 0 ₴ row
  ticked earlier is neither counted nor stuck ticked).
- **One live link under a race**: `save` locks the owner row (`findByIdForUpdate`); the sheet runs one way out
  at a time.
- «Далі» stays open with no ticks while a link is live, so «Закрити посилання» is reachable.
- **Looking is not sharing** (owner: «будемо консистентні»): «Подивитися, як побачить клієнт» posts the ticks
  to `POST /api/price-list/preview`, which saves them and answers a ten-minute `PortalPreviewTokens` pass
  (kind `L`, the owner) — `?vl=`, read through `/api/public/price-list-preview/{pass}`, a preview banner and
  no PDF. No link is minted. `configured` = he has a saved tick or has ever shared.
- An empty PDF says «Майстер ще не додав робіт до прайсу», as the page does.

## Not changed / not built

- The client cannot order from the price list or build an estimate from it.
- No per-client price lists (one list per master); no view statistics.
- Materials are not offered.

## Tests

- `PriceListIntegrationTest`: the first suggestion; only own priced works can be ticked (foreign,
  material, 0 ₴ refused); the client's grouping, unit and price; a price cleared to 0 drops off; a
  closed link 404s, the ticks stay, the next share mints a new token; both PDFs render.
- PWA `CatalogPage.test.tsx` «Поділитися прайсом»: suggestion ticked, works only, library mark,
  unpriced row blocked and skipped by the category tick, the save sends exactly the ticks.
- Request contract: `PriceListRequest` in both snapshots.
