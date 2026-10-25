# Packages on the shopping list, and the plaster and screed calculators

**Status:** stage 1 and the norms round shipped 2026-10-23 (backend V174 + V175, PWA 1.60.0);
stage 2 (backend PLASTER + SCREED) done, not committed; stage 3 (the pages) next. **Source:** `C:\Work\prompts\calc-packages-and-new-calculators-prompt.md`
plus the owner's norms review the same day. **Migrations:** V174, V175.

## Stage 1 — the list keeps the package

The result screen said «6 × мішок по 25 кг»; the same line on the shopping list said «150 кг», and
the list is what goes into the shop.

- **Frozen on the row** (`shopping_list_item.package_size` / `package_name`, CHECK both-or-neither),
  like the name and unit: the dictionary may change, the master's list may not.
- **Written on the server only.** `MaterialCalculatorService.toShoppingList` resolves the package
  with the calculator's own `packageSize(material, sheetArea)`; the sheet takes the master's
  `GKL_SHEET` habit, which is OWNER-wide (`master_material_pref`), not per estimate, so nothing is
  taken from the client. Read only when a sheet is on the list.
- **`applyCalculated`** stamps a new row and refreshes the package on every rewrite — an `edited`
  row included: its quantity stays the master's, the package follows the recalculation. A
  hand-written row (`addManual`) has none.
- **The count is never stored** — derived from the row's CURRENT quantity on every read:
  `packageCount` in `src/lib/packages.ts` (`Math.ceil(q / size - 1e-9)`, now shared with the
  calculator screen) and `ShoppingListPdfService.packageCount` (`divide(size, 0, CEILING)`). A
  top-up row counts its difference («ще 1 × мішок по 25 кг»); the recalculation's offer carries its
  own count (`shopping.suggestionWithPackages`).
- **Backfill** from the dictionary by `material_id`, bought and cleared rows included (a caption),
  EXCEPT `GKL_SHEET` — the next recalculation fills it.
- **PDF:** a second line in the quantity cell; still no price.

### Confirmed, not changed

- `material.package_unit` equals `unit` wherever a package exists — no conversion is needed.
- Neither the list screen nor the PDF merges rows across estimates; the server merges only within
  one apply and only for one material, so two different package sizes can never be summed. Two
  estimates calculated before and after a sheet-habit change are two rows, each with its own size.
- The home card and the calculator screen are unchanged apart from the shared helper.
- `ShoppingListItemResponse.packageSize`/`packageName` are OPTIONAL in `api/types.ts`: a phone's
  offline cache predates them.

### Tests

`ShoppingListIntegrationTest` (freeze, manual = null, edited keeps the number and takes the new
package, the CHECK, the backfill re-run on real rows), `MaterialCalculatorServiceTest` (dictionary
package, the master's sheet), `ShoppingListPdfServiceTest` (caption, corrected quantity,
2,1 / 0,3 = 7); PWA `packages.test.ts`, `ShoppingListPage.test.tsx` (with/without, corrected,
top-up, bought, offer, old cache).

## Norms round — before the pages (owner's review against the technical sheets)

- **P0, the public partition.** The «перегородки 2 сторони» positions are normed per m² of
  SHEATHING, both faces in it (V127 decision 1), while the page asks for the partition's own area.
  `PublicMaterialCalculatorService` now sends `area × 2` for PARTITION; norms and the app are
  untouched. The page's area field reads «Площа перегородки (з одного боку), м²» with «Довжина ×
  висота.» when the partition is chosen (`data-for` takes a list of jobs). `seo-calc-cases` needed
  no edit: «Перегородка 12 м²» was the right label all along, the figures behind it were not.
- **V175, library rows only:**
  - TILE_ADHESIVE_C2 8,5 → 7,0 for 600×600, 800×800, 1000×1000 (both keys); 600×1200 and up keep 8,5;
  - PLASTER_GYPSUM 1,0 → 0,9 in every position;
  - recorded as checked, not changed: PLASTER_GYPSUM_MACHINE 0,95 and cement-lime 1,4 / 1,6 (the
    open question is resolved).

  Sources are in the migration header; `AdhesiveAndPlasterNormsOnLiveDataIntegrationTest` pins all of it.
- **Snapshot rows that move** once the backend is deployed and `seo:snapshot` re-run (nothing else
  on the two live pages changes — the tile lead example is 300×600, C1):

  | Case | Before | After |
  |---|---|---|
  | Перегородка 12 м², один шар | ГКЛ 15 м² (5 листів), CW 14, UW 5, стрічка 7 | ГКЛ 27 м² (9), CW 27, UW 10, стрічка 14 |
  | Перегородка 12 м², два шари | ГКЛ 27 м² (9), CW 14, UW 5, стрічка 7 | ГКЛ 54 м² (18), CW 27, UW 10, стрічка 14 |
  | 20 м² керамограніту 600×600 | клей C2 200 кг (8 мішків) | 175 кг (7 мішків) |

  Screws and dowels stay one package each (1000 / 100 pieces cover the doubled count).

### Carried into stages 2-3

- PLASTER `CEMENT_LIME` → «Штукатурка маякова цементна» (1,6, hand-applied), not the PAINTER 1,4 one.
- Cement screed thickness bound **10-100 mm** (was 30-100); empty still suggests the position's 40,
  shown on the page as the figure for a screed over insulation.
- «Як ми рахуємо», screed: minimum cement screed 10 mm on a rigid base, 40 mm over insulation, 25 mm
  above underfloor-heating pipes; movement joints in rooms over 20 m².
- «Як ми рахуємо», plaster: gypsum on walls 8-50 mm in one coat, on a ceiling up to 15 mm; thicker
  goes in several coats.

## Stage 2 — the public PLASTER and SCREED calculators (backend)

`PublicCalculator { TILE, DRYWALL, PLASTER, SCREED }`; new inputs `plaster`, `method`, `screed`,
`thicknessMm` (≤ 150, the library bound), `withMesh`, `withFilm`; `perimeter` is reused by SCREED.
The request names the calculator, the server names the positions:

| Variant | Position | Mix | Per mm | Empty field |
|---|---|---|---|---|
| Gypsum, by hand, ≤ 20 mm | Штукатурка стін ручна до 2см | PLASTER_GYPSUM (30 kg) | 0,9 | 15 |
| Gypsum, by hand, 20-50 mm | Штукатурка стін ручна від 2см | 〃 | 0,9 | — |
| Gypsum, by hand, > 50 mm | …ручна понад 5 см (у кілька шарів, по сітці) | 〃 | 0,9 | — |
| Gypsum, machine, ≤ 20 / > 20 mm | Штукатурка стін машинкою до 2см / понад 2см | PLASTER_GYPSUM_MACHINE (30 kg) | 0,95 | 15 |
| Cement-lime, by hand | Штукатурка маякова цементна | PLASTER_CEMENT_LIME (25 kg) | 1,6 | 20 |
| Cement screed, area ≤ 20 / > 20 m² | Цементно-піщана стяжка до 20м2 / понад 20м2 | SCREED_CEMENT (25 kg) | 2,0 | 40 |
| Self-levelling | Вирівнювання підлоги самовирівнюючим розчином | SELF_LEVELLING (25 kg) | 1,8 | 5 |

- **Bounds per kind** (400, Ukrainian, one key each): gypsum 5-60, cement-lime 5-40, cement screed
  10-100 (owner's ruling: 10 mm on a rigid base), self-levelling 2-30. A machine on cement-lime is a 400
  (the library has no such position).
- **An empty thickness is the position's `default_param`** (`MaterialCalculatorService.librarySuggestion`)
  — without it a THICKNESS norm asks and buys nothing, which is what the public door did before.
- **Primer is its own line** — no plaster/screed position carries one since V145: «Грунтування основи
  перед штукатуркою, армуванням» (0,15 l/m²) and «Ґрунтування основи під стяжку» (0,2 l/m²). A test
  pins exactly one primer line per answer.
- **Mesh and film** (cement only, else 400): «Армування підлоги сіткою під стяжку» 1,1 m²/m², «Плівкова
  ізоляція під стяжку» 1,15 m²/m², in m² (no package). **Damper tape** only when a perimeter is given:
  «Монтаж демпферної стрічки», 1,05 m per m. **Beacons have no norm** and are not counted.
- **«Своя норма»** now covers the plaster and screed mix, in kg/m² **per mm**, bounded 0,1-10× ours;
  DRYWALL is refused with `error.public-calc.own-norm-unsupported` (renamed from `own-norm-tile-only`,
  no alias — nothing else read it).
- **A zero-quantity line is filtered** on the public door; none of the four calculators produces one today.
- `request-dtos.json` refreshed — copy it to the PWA with stage 3.

Answers from the code (10 % allowance):

| Case | Mix | Primer |
|---|---|---|
| Кімната 34 м², гіпсова, вручну, 15 мм | 510 кг (17 × 30 кг) | 10 л (2 каністри) |
| Ванна 18 м², цементно-вапняна, 20 мм | 650 кг (26 × 25 кг) | 5 л |
| 150 м², гіпсова, машиною, 15 мм | 2370 кг (79 × 30 кг) | 25 л |
| 40 м², гіпсова, 30 мм | 1200 кг (40 × 30 кг) | 10 л |
| Стяжка 15 м², цементна, 50 мм | 1650 кг (66 × 25 кг) | 5 л |
| Наливна 20 м², 5 мм | 200 кг (8 × 25 кг) | 5 л |
| Стяжка 15 м², порожня товщина (→ 40), сітка + плівка, периметр 16 м | 1325 кг (53 мішки); сітка 19 м², плівка 19 м², стрічка 19 м | 5 л |

## Stage 3 — the two pages (PWA 1.61.0)

`/kalkulyator-shtukaturky` (`data-calculator="PLASTER"`) and `/kalkulyator-styazhky` (`SCREED`), built
like the tile page: static HTML, the form read by `src/seo/calcPage.ts`, the table and the lede's
example printed from `calc-snapshot.json` (taken from `https://api.majstr.pro`, `seo:check` green).

- **Form ↔ request.** Plaster: area, `plaster`, `method` (the fieldset is `data-show="plaster:GYPSUM"`,
  and no `method` is SENT for cement-lime even if «Машиною» was ticked before), `thicknessMm`, waste.
  Screed: area, `screed`, `thicknessMm` (hint per kind: cement 10-100, self-levelling 2-30 — the
  backend's bounds, not the prompt's «від 30»), mesh/film ticks (cement only, sent as `false` when
  unticked), the optional perimeter (→ damper tape). An empty thickness is left OUT of the request,
  so the server takes the position's own suggestion.
- **`data-show="<radio>:<values>"`** is the new generic show/hide; the drywall page keeps its own
  `data-for`.
- **«Своя норма з мішка»** is the tile mechanics, on the line bought by a THICKNESS norm (the mix,
  whatever its name), labelled «кг/м² на 1 мм» with «поділіть на 10» (8–9 for plaster, 18–20 for
  screed). The mix is listed first. A refusal is blamed on the norm only when the recount came from
  that block — a bad thickness typed after an own norm stays on the form.
- **Example ids** are `plaster-room` / `screed-room`, not the prompt's `room` twice: `SEO_EXAMPLE`
  ids are global across pages.
- **«Як ми рахуємо» says what is NOT counted:** beacons (both), the plaster mesh a thick coat needs in
  its first layer (sheets: past 20-30 mm), corner beads, insulation under a screed. The thickness
  ranges quoted are from the datasheets in `Materials calculators/` (gypsum from 5 mm, up to ~30 mm a
  pass; cement screed 10-100 mm), no brand named. No norm figure is typed on a page.
- Everywhere the pages are listed: vite input, `sw.ts` `NAVIGATION_DENYLIST`, sitemap (lastmod
  2026-10-25), `llms.txt`, robots comment, all four footers, the landing footer (+ two i18n keys),
  `redirects.test.ts`, OG cards (`og-shtukaturky.png`, `og-styazhky.png`), PWA `CLAUDE.md`.
  `request-dtos.json` copied over from stage 2.
- **Analytics:** the pages send no events (as before) — nothing to extend.
- Deferred: the putty calculator (stage 4) — open question.

