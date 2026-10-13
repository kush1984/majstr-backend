# Trade-by-trade review — the catalog pass (additions, renames, de-duplication, descriptions)

**Source:** §1 of each `C:\Work\prompts\trade-review\0N-*.md`, re-read against the live library.
**Owner ruling (2026-10-13):** go trade by trade on the recommendations without per-table approval,
ask only when critical; existing masters' data must never break. One push at the end.

## The shapes every trade's migration repeats

- **Addition** — V148's: a `catalog_templates` row at the next `added_in_version`, copied into the
  catalogs of masters with the trade under `ux_catalog_items_owner_name_type_unit`'s key (an existing
  row of any source wins), one COUNT notice topped up or queued, `last_synced_catalog_version`
  advanced, then V118's re-rank verbatim.
- **Rename** — a bundle line prices itself off the master's OWN catalog by name and a miss is 0 ₴, so
  everything joined by the name moves in the same migration: the library row; the lines of every
  default bundle; **every master's LIBRARY copy still on the old name, whatever its price** (the name
  is ours, the price stays his — V116 renamed only copies at our price, but V116 did not rename the
  bundle line under a re-priced copy); his pending PRICE_DRIFT notice; the lines of his FORKS of our
  bundles, where his catalog now carries the new name; and the norms — every row on the old key,
  shipped and forked, is COPIED to the new key while the old key keeps its rows for estimates already
  written. A copy he typed himself (MANUAL / IMPORT) and a master already holding the new name are left alone.
  A name another trade ships too is refused (a one-trade rename would split it). Every rename is
  recorded in `catalog_renamed_position` (V158): the old key's norms are kept ON PURPOSE, and
  `MaterialCalculatorIntegrationTest`'s «every shipped norm names a live position» reads that table
  to tell a kept key from a lost one. History ITs that migrate past V157 are capped at their version.
- **Description** — the library row, and a master's LIBRARY copy only while its description is
  empty or still the library's old text.
- **Removal of a duplicate** — the library row goes, default bundle lines move to the surviving
  wording, a master's copy STAYS (his catalog, his estimates); norms stay on both keys.

## DRYWALL — V158

- Added: «Монтаж облицювання стіни на стійковому профілі CW (без кріплення до стіни)» 500 ₴/м² with
  a norm (GKL 1, CW 2,0, UW 0,7, TN25 14, dowel 1,4 — Knauf W625 + calculation), «Монтаж відкосів з
  гіпсокартону на клей» 230 ₴/м.п. V148 had shipped the report's other five additions.
- Renamed: the niche (two jobs in one name), both framed sound-insulation rows («слоя»), the glued
  board, the reveals («укоси» → «відкоси … на профілі»), the XPS lining (a brand).
- Descriptions on 11 positions: partitions «по обшивці з обох боків», boxes and niches «вкажіть
  розгортку», the slope ceiling, the height surcharge, the Q2 wording.
- Left: partition names (the rule went into the description), «Монтаж гіпсокартону на стіни»,
  «Грунтування» (one row three trades share), the 1400 ₴ track (no source).
- Test: `DrywallCatalogReviewOnLiveDataIntegrationTest` — a copy at our price, a re-priced copy with
  his own description and a pending notice, a copy he typed, a fork of «Стеля з гіпсокартону», a
  forked norm; new positions and the lining's frame; descriptions.

## TILING — V159

- Added: preparing old tile for tile-on-tile (verdict «consumes nothing»; the contact primer is its
  own step), factory waterproofing corners (new `WATERPROOF_CORNER`, one per piece), a slope-to-drain
  surcharge 30 %, the bath / tray joint (tape 1,05 m/m + mastic 0,3 kg/m). V148 had shipped four.
- Renamed 21: «плітки» ×4 and «заусовка» → «запил», «конструкціїї», «полукруглими», «вкладання»,
  «… обсягів» → «(великі обсяги)» ×4 + the stair flights, the one-component mastic, the stair
  waterproofing, «без шва», the screed grinding, «Ґрунтівка» → «Ґрунтування», «Демонтаж фарби», the
  latex grout on stairs, «1000х1000» → «… мм».
- Category moves 3 (hardboard cover, weather surcharge → «Організаційні послуги»; 1000×1000 →
  large format). Descriptions on every square-format laying row, grouts, waterproofings, priming,
  the «%» surcharges, consumables, exclusive tile, the turnkey pool.
- Left: the two waterproofing rows BUILDER ships under the same name (one row in a two-trade
  catalog), the epoxy corner's unit, «Обсяг замовлення» (meaning is the owner's), the zero prices.
- Test: `TilingCatalogReviewOnLiveDataIntegrationTest`.

## PAINTER — V160 (additions, renames, descriptions; duplicates wait)

- Added 8, each with its sibling's norm: cement-lime plaster (THICKNESS 1,4, suggested 20 mm),
  structural glass wallpaper (fabric 1,1 + glue 0,3), painting paintable wallpaper and wet rooms
  (paint 0,22 l), a radiator per section (metal enamel 0,05 kg), metal with enamel (0,20 kg), wood
  with stain or varnish (clear varnish 0,20 l), anti-mould treatment (unnormed — no source).
- Renamed 13: the primer stages, «Вирівнювання стін» → start levelling (now buys start putty), the
  over-5-cm plaster, Ferozit and «- від» out of names, the membrane, «/мет.» and «прих.», the batten
  grid, «Демонтажні роботи (за погодженням)». Categories: 3D panels → «Оздоблення», the loft hatch →
  «Суміжні роботи». Descriptions on the report's key positions.
- Waiting for the owner: the ~30 duplicates (V112's «з позицій нічого не викидаємо»). Left: the
  painting-line unit, rows shared with DRYWALL/BUILDER, «Відтяжка» / «Опуск дверної пройми» and what
  «Шпаклівка … під фарбування» includes (the owner's to say).
- Test: `PainterCatalogReviewOnLiveDataIntegrationTest`.

## FLOORING — V161

- Added 7, all normed (FLOORING stays fully answered): floating engineered board, strip parquet on
  adhesive, herringbone laminate (1,12), plywood/OSB, parquet oil (new `PARQUET_OIL` 0,04 l), a
  transition profile, and the dry gypsum-fibre floor copied verbatim from DRYWALL with its norm
  re-filed to `trade = NULL` (forks too).
- Renamed 11 («укладка» ×4 with SPC vs LVT said, the board floor, art parquet, both primings, the
  «заливаня» typo, the self-levelling pour, the tint/varnish coat). «Ґрунтування основи підлоги»
  421 → 35 ₴ with a PRICE_DRIFT notice. Categories: hidden skirting → «Плінтус», a «Тераса» category.
- Left: duplicates (owner), «Машинна стяжка самовирівнююча» (which product is the owner's to say),
  the threshold's unit, rows BUILDER ships under the same name.
- Test: `FlooringCatalogReviewOnLiveDataIntegrationTest`.

## BUILDER — V162

- Added 5: foundation concrete from a mixer (ready-mix 1,015), a ring beam in AAC U-blocks and a
  380 mm ceramic-block wall (unnormed — the format decides the count), semi-dry machine screed (the
  cement-sand THICKNESS norm), and «Цементно-піщана стяжка до 20м2» copied verbatim from FLOORING
  with its norm re-filed to `trade = NULL`.
- Renamed 20: brick walls by the Ukrainian brick (250 mm = one brick), the half-brick and AAC
  partitions, 2NF block, floor beams, antiseptic «за м³ деревини», membrane, OSB deck, soffits, PU
  foam, site levelling, the monolithic slab, the four concrete stair rows by how concrete arrives.
  Categories: beams → «Монтажні», PU foam and stone-chip plaster → «Фасад». Descriptions on the
  «комплекс» rows, the foundation, the masonry thresholds.
- Left: the 28 duplicates incl. the doorway pair (V120: one job, two wordings), «керамоблок
  38х60х20» (no such block — the owner's), «розчин з щебнем», «Гідроізоляція плівкою», the masonry
  reinforcement units, the post caps' unit, rebar tying (waits for the kg/m³ basis).
- Test: `BuilderCatalogReviewOnLiveDataIntegrationTest`.

## ELECTRICAL — V163

- Added 5: a hollow-wall box (new `BOX_HOLLOW`), a cable tray (new `CABLE_TRAY`), panel assembly per
  module, a dedicated air-conditioner line, heating cable in a screed (the last three unnormed).
- Renamed 15: the plasterboard «штроблення», the built-in trunking chase, «Заробка штроб» →
  «Закладення штроб гіпсовою сумішшю», conduit runs by cross-section (≤ 2,5 / ≥ 4 мм²), neutral bars,
  the LED power supply, the sensor, the pole-count spellings. Ten category moves. Placeholder prices
  with notices: surface panels 48-180 modules 120 → 1200/1600/2500, 3P/4P/change-over 4P/RCBO 270 →
  500/600/700/500, TV cable 210 → 60. Descriptions on the report's key rows.
- Left: duplicates (owner), the UPS POINT unit, the 50×250 chase and the bare cable / trunking prices,
  the chase-size order.

## PLUMBING — V164

- Added 6: circulation pump, extra leak sensor, sewer air valve (new `AIR_VALVE_SEWER`), a frame for
  a wall-hung basin or bidet (new `INSTALL_FRAME_BASIN`), a bidet, the linear shower drain copied
  verbatim from TILING.
- Renamed 18: the under-sink filter, «осмос», the ball valve, sewer outlets, the boiler hook-up per
  point, the WC that was «унітаз біде», the concealed mixer parts and the Smart Box brand, the radiators,
  the electric water heater, «рушникоосушки», «Ф» → «Ø», the water-hammer arrester, «Опресування».
- Left: ~22 duplicates (owner), every POINT → PIECE unit, the category regrouping, a separate UFH
  insulation / damper row (V152's norm already buys both), blockage clearing and repairs (no price).

## DEMOLITION — V165

- Added 10, priced off siblings: haulage by the trip, built-in wardrobes, a WC frame (unnormed — pipe
  ends), plaster off a ceiling, suspended ceiling, plasterboard lining, brick masonry and concrete by
  m³, floor underlayers (all «consumes nothing», V154's rule), and the film cover copied verbatim
  from TILING with film 1,10 + tape 0,5 (trade-less).
- Renamed 7 (laminate/parquet, sockets and switches, the board floor, both partitions and the brick
  wall by thickness, the 100 mm concrete wall). Wall tile → «Демонтаж стін». Descriptions.
- Left: diamond cutting and opening reinforcement (no price; the frame is METAL's), bags per floor,
  shared rows, the balcony-block pair and the debris wordings (owner), «Підйом будматеріалів».

## METAL — V166

- Added 5, priced off siblings, unnormed: MMA welding per hour, a stringer staircase per step,
  picket and chain-link fencing, rust converter treatment.
- Renamed 5: the stair frame «(за сходинку)», bending «за гин», cutting «за м.п. різу», «Монтаж
  воріт», the fence rails welded to the posts. Descriptions on every «Виготовлення…» (work only),
  the gates, primer and paint per m² of STEEL with the 127/t · 255/t rule, the services.
- Left: six additions with no price in the report (the seam per metre among them), the BUILDER
  cross-trade pairs (owner), the hangar's tonne.

Test for V163-V166: `CatalogReviewLaterTradesOnLiveDataIntegrationTest`.

## Waiting for the owner: duplicates

Every trade's report lists two wordings of one job (≈30 PAINTER, ≈30 ELECTRICAL, ≈22 PLUMBING, 28
BUILDER, ≈9 FLOORING, ≈6 TILING, a few in DEMOLITION and METAL). Removing the loser from the LIBRARY
(a master's copy stays; default bundle lines move to the survivor; norms stay on both keys) goes
against V112's «з позицій нічого не викидаємо», which TRADES-REVIEW §3 item 4 asks the owner to
revisit — so none of it is done here.
