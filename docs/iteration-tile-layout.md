# Iteration — tile layout in the materials calculator

**Status:** DONE (code) 2026-11-12 — stages 0–4: herringbone, engine, storage + calculator, the screens, sizes into the measurements on a yes. Not committed; PWA version to bump to 1.78.0 at the commit.
**Plan and mockups:** https://claude.ai/artifact/SyQS96Cm2zqrFGxcWG1pNL · analysis with sources and the
owner's rulings: `C:\Work\prompts\majstr-rozkladka-plytky-analiz.md` (outside the repos).
**Migrations:** V203, V204. **PWA:** engine, the layout screen, the calculator card, the line entry, «Як ми рахуємо ваші позиції».

## Goal

A master sees, per wall, how much tile the job takes with its cuts, where a narrow strip falls and how
to avoid it, and the result lands in «Скільки матеріалу купити». The WORK position never changes.

## Owner's rulings (2026-11-12)

- The count is a RANGE: without offcuts (every cut from a fresh tile) — with offcuts reused under the
  factory-edge rule.
- A laid-out tile takes a 5 %, at-least-two spare instead of the master's `WASTE_PERCENT`.
- Standard library formats plus a «свій розмір»; the format check is five geometric hints, never a block.
- Two doors in: the tile line's ⋮ menu and a prompt on the calculator's tile card. Sizes typed in the
  layout go to the object's measurements only after the master says yes.
- Herringbone 1,09 and the ANSI offset hint ship with the layout, one release.

## Stage 0 — herringbone (backend, done)

- `MaterialCalculatorService.HERRINGBONE_LAYOUT = 1.09`: a «Укладання плитки ялинкою (плюс % до м.кв.)»
  line now scales the tile of the position it rides on (POSITION base) or every position (TOTAL base),
  exactly like the diagonal's 1,05. Sources ask 15–20 % for herringbone against ~10 % straight; on top
  of the master's 10 % that is 1,20 / 1,10 ≈ 1,09. The diagonal's 1,05 (≈ 15,5 % total) matches the
  sources and stays.
- The boolean `CalcLine.diagonal` became `CalcLine.layout` (`CourseLayout` STRAIGHT / DIAGONAL /
  HERRINGBONE). A position both lines mark takes herringbone. The public calculator's own request
  enum (`PublicMaterialCalcRequest.TileLayout`) is unchanged and maps onto it — no herringbone there.
- Only `CERAMIC_TILE_*` is scaled; the adhesive and grout of the same position are not.
- Tests: `MaterialCalculatorServiceTest` — diagonal 21 / herringbone 21,8 of 20 m², a TOTAL-based line,
  both lines on one position, the adhesive untouched.

## Stage 1 — the engine (PWA, done)

`src/lib/tileLayout.ts` — pure, offline, no React. One rectangular surface in, pieces and counts out.

- Patterns STRAIGHT and OFFSET ½ · ⅓ · ¼ (row `r` shifts by `r × fraction` of a step, cumulative).
  Starts CORNER (from the left / the floor), CENTER (a tile on the axis), SYMMETRIC (the better of tile-
  on-axis and joint-on-axis), AUTO (5 mm sweep over one step plus the canonical three: the widest
  narrowest strip up to «not narrow», then the fewest pieces, then the widest strip).
- Openings with a position (`x` from the left, `y` from the floor). A tile wholly inside one is not
  laid; one bitten across its whole height or width from a side stays a rectangle with that side cut;
  anything else is SHAPED and takes a tile of its own. Overlapping openings are a validation error.
- **Offcut reuse (`tilesForCuts`)** — the lower bound. A piece keeps a factory edge wherever it meets
  another tile; a cut edge may face only the wall, the floor/ceiling or an opening. A tile turns 180°
  in its plane, never 90°. Biggest piece first, from the smallest offcut it fits, else a fresh tile; a
  cut leaves the rest of the width at full height, then the rest of the height; kerf 2 mm; an offcut
  under 40 mm in either direction is thrown away. Consequence: one tile gives at most one edge strip
  per factory end.
- Narrow = under a third of the tile side or under 50 mm (as the plan says; the 50 mm clause only bites
  for tiles under 150 mm).
- Hints: NARROW_UNAVOIDABLE (AUTO on both axes still leaves one), MOSTLY_CUT (more cut than whole, at
  least four pieces), TILE_BIGGER_THAN_SURFACE, OFFSET_HALF_LONG (½ offset on a side over 380 mm, ANSI
  A108.02 4.3.8.2). The herringbone-format hint waits for the herringbone pattern.
- A position: `countPosition` reuses offcuts across its surfaces and adds `spareTiles` once.
- Performance: a 5 × 3 m wall with a door, ⅓ offset, AUTO on both axes — ~18 ms in Node.

### Reference figures (counted by hand, pinned in `tileLayout.test.ts`)

Wall 1850 × 2550, tile 600 × 300 landscape, joint 2, wall gap 2 (frame 1846 × 2546):

| Start | Columns | Top row | Whole | Cut | Tiles (min–max) |
| --- | --- | --- | --- | --- | --- |
| Corner | 600 · 600 · 600 · **40** | 130 | 24 | 12 | 30–36 |
| Symmetric | 320 · 600 · 600 · 320 | 130 | 16 | 20 | 34–36 |

+ spare 2 → 32–38 / 36–38. By area: 4,72 m² + 10 % = 29 tiles. The prompt's «34 from the corner» bought a
tile per strip although its own reuse rule allowed one tile for all eight strips; the factory-edge rule
gives two per tile.

Same wall 2050 wide with a 700 × 2000 door 1000 mm from the left: 10 whole + 24 cut + 2 shaped,
24–36 tiles (a 396 and a 106 beside the door from one tile).

## Stage 2 — storage and the calculator (backend, done)

- **V203 `estimate_tile_layout`** — one row per estimate line (`ux_tile_layout_item`): the format short side
  first, `laid_area` m², `min_tiles`/`max_tiles`/`spare`, the inputs as JSON (`params`, ≤ 32 KB, never read by
  the server) and `measurements_declined` for stage 4. CASCADE with the line and the estimate. Beside
  `estimate_material_param`, not in it (one number per question vs a document); like it, no
  `requireNotSigned` and no estimate `@Version`.
- **Doors:** `PUT /api/estimates/{estimateId}/materials/tile-layouts/{itemId}` (insert or replace, under the
  line's row lock so a double save cannot 500 on the unique key) and `DELETE` (idempotent). A line of
  another estimate is a 404; a line that is not m² WORK, or `minTiles > maxTiles`, is 400
  `TILE_LAYOUT_INVALID`. Both in `IdorMatrixIntegrationTest` (classes 1 and 2).
- **The calculation:** a laid-out line puts `maxTiles + spare` WHOLE tiles on its format's material with no
  allowance (source row «N шт × 0,18 = … м²», `normId` null); what the layout did not cover
  (`quantity − laid_area`) is bought by area as before, in the layout's format, with the course factor
  (diagonal/herringbone) and the master's allowance. The adhesive, grout and primer still follow the whole
  line. A line no tile norm answers for still gets its rest at the course factor.
- **Pause:** a layout whose line is no longer m² work, or whose name now states another format
  (`TileFormats.nameAgrees`; «30х60» counts as 300×600), is reported `paused` and not applied. A RENAME keeps
  the row; a UNIT change deletes it (`EstimateService.updateItem`). `duplicate` copies layouts like params.
- **Custom format:** a format the library does not ship gets a material built per calculation and never
  stored (`CERAMIC_TILE_CUSTOM`, id derived from the format) — «Плитка 300×500 мм», packaged by the piece.
  `toShoppingList` resolves it from the estimate's layouts and writes the row BY NAME (`material_id` NULL).
  Deviation from the plan page: no `material` row is inserted, so no one can grow the shared dictionary.
- `MaterialCalculationResponse.tileLayouts` lists every stored layout, paused ones included.
- Tests: `TileLayoutIntegrationTest` (12, against the real library norms: 20 m² by area = 123 tiles; 9,1 m²
  laid with 72 tiles + 10,9 m² by area = 139; whole line laid = exactly the layout's tiles; pause; cm
  format; custom format to the shopping list by name; replace; delete; validation; foreign line; unit change
  vs rename; duplicate), `TileFormatsTest`. Contract snapshot refreshed and copied to the PWA.

## Stage 3 — the screens (PWA, done)

- **`/estimates/:id/items/:itemId/tile-layout` — `TileLayoutPage`.** Format first (read off the line's
  name, «30х60» as cm; else a select of the 18 library formats + «Свій розмір»), landscape/portrait, the
  joint (also the wall gap). With no surfaces yet: «Як виглядає те, що облицьовуєте?» — a ROOM (L × W × H →
  four walls А–Г and the floor; the floor alone is ticked when the line's m² is within 15 % of it, else the
  walls), separate surfaces, or «лишити за площею» (back). A line with measurement refs seeds its
  rectangular planes as surfaces; their openings carry no position and are listed UNPLACED with «поставити
  сюди» (until placed the wall is counted without them — more tile, never less).
- Each surface: name, on/off, width × height (depth on a floor), pattern, start, the drawing
  (`TileLayoutDrawing`, from the engine's own pieces: whole plain, cut orange, narrow red with its mm,
  openings dashed), «Цілих N · на підрізку M», the narrow strip named per side with one tap to fix
  (symmetric for straight, AUTO for offsets), the hints, openings (w, h, from the left, from the floor).
  MOSTLY_CUT says «від кута було б менше» when the start, not the format, is why (`cornerCutsLess`).
- **Bottom summary:** «Розкладено X з Y м²», the range WITH the spare, the rest by area, «за площею вийшло б
  N — забракло б щонайменше K» (against the LOWER bound), the over-the-line warning, «Готово · N шт».
- **Save:** `useTileLayoutActions` → `offlineMutate`, outbox entity `tileLayout` (update = PUT, delete =
  DELETE; coalesced; deps on the line), cache key `['tileLayouts', estimateId]`, materials invalidated.
  `GET …/materials/tile-layouts` (new, light — no calculation) is what the screen reopens from; the
  draft rides `params` as JSON (`version: 1`), an unreadable one is not reopened.
- **Doors:** the line's edit sheet («🧱 Розкласти плитку по стінах», tiling m² work only —
  `isTilingLine`: TILING trade or a name with плитк/керамограніт/кахл/мозаїк), and «Плитка по стінах» on the
  calculator: every tiling line behind a «Плитка…» material, with «Розкладки немає — …» + «Розкласти по
  стінах», «Розкладено X м² · N шт» + «Змінити», or the paused line + «Перерахувати».
- `MaterialSourceLine.normId` is optional now: a layout's counted row has no norm, so it shows no «Змінити
  норму».
- Verified at 375 px (Playwright with mocked API, screenshots): no horizontal overflow; the card, the
  shape chooser, a corner layout with the 40 mm strip, the symmetric fix.
- Tests: `tileLayoutModel.test` (15), `TileLayoutPage.test` (5), `MaterialCalculatorPage.test` (+3),
  outbox `handlerCoverage` and `i18nKeys` cover the entity and the strings. Backend: the GET in
  `TileLayoutIntegrationTest` and the IDOR matrix.

## Stage 4 — the sizes into the measurements, on a yes (PWA, done)

- After «Готово», if the sizes were TYPED here (not read off the measurements, `fromMeasurements`), the
  line was not answered before (`measurementsDeclined`, `savedToMeasurements`) and the plan has
  measurements (PRO, temporarily FREE too — `TEMP_FREE_GETS_MEASUREMENTS_AND_ECONOMY`): a sheet
  «Зберегти розміри в заміри обʼєкта?» with the room name (prefilled with the estimate's name).
- «Зберегти в заміри» → `useMeasurementActions` (offline-first, the outbox entities that already exist):
  a new room, then ONE «Стіни» SURFACE element (a rectangle per laid wall, every opening taken out) and
  a «Підлога» one, in MM — the shape the object import writes. The layout is re-saved with
  `savedToMeasurements: true` in its draft.
- «Ні» re-saves the layout with `measurementsDeclined: true` (V203's column). Closing the sheet is NOT an
  answer: nothing is marked and the next save asks again.
- **The estimate line is never touched** — no `measurement_refs`, so its quantity cannot move. The other
  lines pick the new elements through the existing «Вибрати з замірів».
- Verified at 375 px (screenshot): a bottom sheet, both buttons in the thumb zone, the toast moved to the
  top while the sheet is open.
- Tests: `tileLayoutModel.test` (+3: when it is offered, the elements' exact payload, the round trip),
  `TileLayoutPage.test` (+2: yes → room + «Стіни» in MM + the flag; no → remembered, nothing written).

## The public page /kalkulyator-plytky (PWA, done 2026-11-12, owner's ask)

- A new section «Розкладка плитки по стінах» under the area calculator: `src/seo/tileLayoutPage.ts`, plain DOM
  over the SAME engine and model as the app (`lib/tileLayout.ts`, `tileLayoutModel.ts`), no server call.
- Format (the 18 library formats + «Свій розмір»), landscape/portrait, joint; surfaces as cards (width ×
  height, pattern, start, openings with a position); «Скласти з кімнати» builds four walls and the floor;
  live drawing, narrow strip per side with a one-tap fix, the hints; the total as a range with the spare and
  the by-area comparison at 10 %. Recomputes as the visitor types (inputs are never re-rendered, so focus
  stays).
- «Як ми рахуємо» gained a «Розкладка» paragraph (the factory-edge rule, the range, a third / 50 mm, the
  5 %, ≥ 2 spare). No computed figure is typed into the HTML — the section is empty until the script runs.
- Verified at 375 px (Playwright screenshots, no page errors, no horizontal overflow). `seoPages.test`
  pins the section, its script and that it imports the app's engine.

## The page answers in one voice (2026-11-13, owner: «все має бути динамічне»)

- **The layout follows the area calculator above:** its format (the eight sized formats; mosaic and
  «кабанчик» have no one size, so the layout keeps its own), its joint (blank = 2 mm) and its allowance
  (the by-area comparison uses the visitor's 5 / 10 / 15 %).
- **Build-time layout figures** (`src/seo/tileLayoutCases.ts`, run by the SEO plugin in Node): the first
  paragraph's «по стінах…» sentence and a second table «Ті самі розрахунки — по стінах» (bath, a 600×1200
  wall, a 600×600 floor) — the range with the spare, the by-area figure and its verdict, the narrowest
  strip. Change the engine and the next build prints the new figures; `seoPages.test` pins that the page
  holds exactly what the module computes, and that the bath's area and by-area figure are the snapshot's.
- **«Розкладено» and «за площею» now use the area as MEASURED** (walls less openings, joints included) —
  in the app too: the tiles' face area left a «rest» that was only grout, and compared against a by-area
  figure the top calculator does not use.
- Every text that describes the page now says what it does: meta / OG / Twitter / JSON-LD descriptions,
  `llms.txt`, the first paragraph (two ways to count), «Як ми рахуємо» (the area part is called the area
  part; a «Розкладка» paragraph), the area result links down to the layout, the table says it is by area,
  the CTA says the app lays out each estimate line.

## Moving the layout by hand (2026-11-13, owner's ask)

- Engine: start `MANUAL` + `manualOrigin` (mm, any value, taken modulo a step); a manual start on either axis
  makes both manual. Tests: the strip follows the origin, a whole step is the same layout, no origin = corner.
- «✋ Посунути розкладку вручну» hands the grid over from where it stands (the first drag moves nothing),
  then drag the tiles to move the grid or a door / window to move it (kept on the surface, 5 mm steps,
  `clampOpening`), ◀ ▶ ▲ ▼ nudges by 5 mm, «Зафіксувати» ends it. The app's drawing captures the pointer;
  the public page follows it on the window (its drawing is rebuilt per move). `touch-action: none` only
  while moving, so the page still scrolls under a finger otherwise.
- Verified in a real browser at 375 px (Playwright, mouse drag, both screens): the strip changed from 40 mm,
  the start became «Вручну», no page errors. `TileLayoutPage.test` pins the hand-over and a nudge.

## A line in the master's own words (2026-11-13, owner: «по максимуму передбачити»)

An imported «Укладання плитки на підлогу 60×60» bought nothing: no norm is keyed by that name. A line is
now resolved in one ladder (`MaterialCalculatorService#resolve`), the same for the calculation, the
availability probe and the public pages, and every rung but the first is REPORTED (`matches[]`):

1. **Its own name** — as before, nothing reported.
2. **His answer, V204 `material_norm_alias`** — (owner, wording key, unit) → a library key, or NULL =
   «не потребує матеріалів». No trade filter: he said what the job is. A chosen position that no longer
   buys anything falls through and is asked again. `PUT/DELETE /api/me/material-aliases`; only a library
   position of the SAME unit is accepted (400 `error.material-alias.unknown`).
3. **Spelling** — ґ/г, x/×/х in a format, apostrophes, punctuation (`MaterialAliasService.spelling`);
   taken only when exactly ONE library position of that unit spells that way, else nothing.
4. **The tile rule (option A)** — `TileFormats.libraryKeysFor`: a laying/cladding line of tile with ONE
   format (cm read as ×10 when a side is under 100) → «укладання плитки S×L», TILING norms only. Not for
   paving/clinker/mosaic/stone, not for a range («до 60×60»), not for two formats.
5. **Unmatched.** Only a CLOSE library name is offered, and only one: `CatalogMatcher.similarity` ≥ 0.5 on the
   name alone (the same-trade bonus breaks ties, never lifts a weak name over the bar). The screen asks
   «Це те саме, що «X»?» — «Так, рахувати» stores the alias, «Ні, це інше» stores NULL. A line with nothing
   that close is the master's own work: «Немає в наших нормах — матеріали допишіть самі» with «Додати
   матеріал вручну» (the object's shopping list, opened on its add form). Such a line opens the «Матеріали»
   door only when it has that one close question.

**Simplified on the owner's word (2026-11-13, «ми кожен раз все ускладнюємо»).** The first cut offered three
loose suggestions inline plus a searchable «Інша позиція…» picker; it put «штукатурка стін (до 2 см)» in front
of «Фарбування стін за 2 рази», the master tapped it, and the plaster's THICKNESS question then hid the result.
Matching his own work to ANY catalog position is the wrong idea: we do not guess, we say it is not counted.
So: the picker and `GET /api/material-norms/positions` are gone; a spelling or tile-format read (RULE) is
counted SILENTLY — the material rows already name their source lines; the card («Ці позиції ми не
рахуємо») lists only the close question, his own answer («Рахуємо як «X»» / not counted, with «Змінити» =
forget and ask again) and the lines we do not count.

PWA: `PositionMatchCard`. Tests: `MaterialAliasIntegrationTest`, `TileLayoutIntegrationTest`,
`TileFormatsTest`, `MaterialCalculatorPage.test`, `ShoppingListPage.test`.

## The layout screen speaks metres (2026-11-13, owner: «ти серйозно кажеш майстрові вказувати розмір у мм?»)

- Every size the master TYPES — a wall, a floor, a room, an opening — is in metres, comma or point
  (`sizeToMm`); a number above 20 cannot be metres here and is read as mm, and the field says so
  («Прочитали як мм: 3,6 м»). The tile's sides and the joint stay in mm, as tile is sold. The engine and
  the stored params keep mm. Both the app and /kalkulyator-plytky.
- A line naming a floor («…на підлогу», `lineIsFloor`) opens on «Підлога» (length × width), and the room
  builder ticks the floor for it; «Висота облицювання» (how high the tile goes, the room height for a whole
  wall) replaces «Висота плитки»; the card is «Розкладка плитки».
- The object's rectangular measured surfaces are offered with one tap even when the line names none.
- No red «Розміри поверхні…» while the sizes are still empty.

## Later, noted by the owner

- ~~The public SEO page /kalkulyator-plytky~~ — done above, ships with the layout.

## Not changed / confirmed

- The WORK position's quantity and price — never touched by the layout.
- The public calculator: diagonal only, as before.
- No storage and no screen yet: the engine is not reachable from the app until stage 3.

## Gotchas

- `PublicMaterialCalcRequest.TileLayout` already exists — the calculator's enum is `CourseLayout` so the
  two are never confused.
