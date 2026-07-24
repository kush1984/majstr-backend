# Iteration: project-documentation import → Заміри (archives / PDFs / photos)

- **Status:** 🔨 code complete; backend build on the user
- **Migration:** **V63** — `measurement_room.floor` (nullable VARCHAR(20), free text)
- **PWA:** 0.26.0 (+ the 0.26.1 fix pass below)

## 0.27.0 — the ROOT CAUSE: a 25-sheet PDF was one document

Verified against the real `Belgradska_1405.pdf` (25 sheets) by running its text layer
through the classifier:

| Finding | Consequence |
|---|---|
| «Обмірний план» is on **p.3 only**, and that page carries BOTH the drawing and the rooms table (11 rooms + «Загальна площа: 163,91») | The whole feature hinged on reaching one page |
| The rooms table **repeats on 10 other sheets** (furniture, wall finishing, electrics…) | That's where «Коридор ×3» came from — wrong sheets were parsed |
| The set has **no floor marker anywhere** (single-storey house) | Everything lands in «Без поверху» — by design, not a bug |
| `H=2850/2860/2880` are printed for ~half the rooms | Heights are asked only for the rest |
| Gabarits for the checksum are present: 13300×4615 = 61,38 ✓, 4990×3545 = 17,69 ✓ | The checksum can actually prove sizes |

**Fixes:**
1. **Per-page classification, stamp-first.** A page's own stamp beats a table it merely
   carries: «Відомість креслень» (the index, listing every title) and «План чорнового
   оздоблення/меблів/…» are noise even with the rooms table on them. After the fix, of
   25 pages exactly **one** is selected — p.3. Only selected pages upload (pdf-lib split).
2. **Auto-pick + auto-parse.** Found unambiguously → parsing starts immediately, no file
   screen. The review says «Розпізнано з: … · обрати інші сторінки»; the list is the
   FALLBACK (nothing found / more than 10).
3. **One combined call for a plan page** — the sheet goes as a native `document` block so
   the model sees the drawing AND the text table in one pass (a text-only route would
   parse the table and lose the geometry; two calls would double the cost and need
   merging). A plan PHOTO keeps the two-pass inventory→details flow: no printed table
   anchors it.
4. **Geometry in the schema:** `widthMm`, `lengthMm`, `cutWidthMm`, `cutDepthMm`,
   `ceilingHmm` per room. The prompt states explicitly that only «H=» is a ceiling height —
   «Нпр» is an opening height and «Нпд» a sill.
5. **Checksum (`checksum()`):** w×l must equal the table area ±2% → accepted, exact
   perimeter 2(w+l). If only w×l−cut matches → **proven L-shape**. Otherwise rejected:
   numbers are shown greyed («з креслення: 4,99×3,2 — не сходиться») with an «все одно
   взяти» button, never fed into a calculation.
6. **L-shape as a real shape** (`lshape`, A/B/a/b) in BOTH engines: area = A·B − a·b via
   the shared shoelace, perimeter = the bounding rectangle's 2(A+B) — the cut removes two
   segments and adds two identical ones, so walls and skirting are unaffected. Available
   in the manual shape picker too, not just the import.
7. **Progressive room card:** Ширина / Довжина / Висота / Периметр are ALWAYS visible —
   filled when recognised, empty as an invitation otherwise — each with its source badge
   («з документа» / «пораховано» / «введено вручну»). Typing a width derives the length
   (area ÷ width) → perimeter → and with a height the walls appear. Openings are an
   editable list («+ Проріз») driving netto walls and reveals; the walls line shows
   «брутто − прорізи = нетто», and says «прорізи не враховані» when none are known.
   Reveals now count doors too (2×h + w each).
8. **Coverings create nothing** — recognised, listed as skipped with the reason.
   Per-room height from «H=» wins over the per-floor answer.

## 0.27.1 — a SECOND real project (two floors, one sheet per file)

`Креслення друк.7z` — 44 files, one sheet each, two floors. Classification picked **6 of
44** (both floors' обмірний план + експлікація) and 38 were noise. Three real problems the
first project could not show:

1. **The schedule table is IDENTICAL on both floors' sheets** (the same 10 rooms,
   «Загальна площа 204,0») — only the room numbers marked on each sheet differ
   (1 поверх: 1 2 3 4; 2 поверх: 5 10 9 8 7 6, summing to 111,9 + 92,1 = 204,0 ✓). The
   merge key included the floor, so importing both sheets produced **20 rooms instead of
   10**. Fixed by asking the model for **`roomsOnThisSheet`** (the numbers actually drawn
   on that sheet) and keying rooms by **number+name, not floor**. A room is placed on the
   floor whose sheet MARKS it; a room no sheet marks keeps no floor rather than a wrong one.
   A nameless arrival (the plan gives only numbers) joins the room the schedule named,
   while two rooms with the same number but different names stay apart (per-floor numbering).
2. **Name vs sheet conflict.** «Коридор 2 поверху» (64,4 м²) is listed among floor 1's
   numbers — a double-height void drawn on the lower sheet. Per the user's call we do **not
   guess**: the room's own name wins, and the card carries a note «на аркуші "1" поверху —
   перевірте» so the master corrects it in one tap.
3. **The room schedule now goes as a document block too.** In this project the table's text
   order is scrambled — «4 7,3 … 8 17,4 … Спальня дитяча Вітальня / кухня» — so a text-only
   parse would silently mis-pair names with numbers. Coverings stay on the cheap text path
   (a plain table that creates nothing).

Also: the auto-pick now takes **one sheet per kind+floor** (this set has near-identical
`1_обмірний план 1п` and `7_обмірний план 1п` — 4 calls instead of 6); the rest stay
unticked in the list.

**Confirmed by this project:** its обмірний план has **no text at all** (bare dimension
numbers — no `H=`, no `Нпр/Нпд`, no room numbers), so vision is the only route for geometry
and the heights screen is genuinely needed. Nothing in the set names a floor for the rooms
themselves — exactly what `roomsOnThisSheet` now solves.

## 0.26.1 — the first real-project contact (7 fixes)

The feature met Belgradska_1405 + the 45-sheet archive and every default that was
guessed wrong surfaced at once:

1. **Floor priority** is now room name → sheet stamp (normalised to the short label)
   → filename; one document routinely holds BOTH floors («Коридор» + «Коридор 2
   поверху»), so the file floor is only the default. Review cards got an editable
   floor field + a mass «перенести обрані на поверх» action.
2. **No auto-ceiling** — a rectangular room's ceiling is a floor duplicate that
   doubled every total. It's derived but OFF; a review checkbox («стеля переважно
   потрібна на мансарді») opts back in. Already-imported ceilings are untouched.
3. **Totals = floor areas.** The rooms list header, per-floor and per-room figures
   show the «Підлога» item (fallback: the m² sum for plain rooms), with a
   per-room breakdown line («Підлога 26,5 · Стіни 61,2») instead of one mashed number.
4. **Nothing is silent:** the review aggregates what could NOT be computed —
   «стіни не пораховані: немає висоти для 2 поверху», «у документах лише площі —
   додайте обмірний план» — and a capability note BEFORE parsing says what the
   selected kinds will give (a coverings-only pick warns it yields areas only).
5. **Multi-page PDFs** (the real 45-sheet case): pages are classified CLIENT-side by
   their own text (pdfjs-dist, lazy) into per-page rows with type+floor ticks; only
   the selected pages upload (pdf-lib single-page split). Batch cap 10 per run with a
   Ukrainian message; the server page cap is now 10 and guards only direct API callers.
6. **7z unpacks in the browser** (7z-wasm, ~1.5 MB, loaded lazily only when a .7z is
   dropped; 100 MB cap, same entry filters as zip). Failure → a clear «розпакуйте і
   надішліть PDF або zip» message, never a dead end.
7. **Broken i18n keys** («shape.direct..hint») — the import wrote `mode:''` and
   `toDraft` kept it; payloads now write `mode:'d'` and `toDraft` normalises any
   unknown/empty mode to the variant default. A direct-area element also got a
   one-tap «📐 Задати розміри (a×b)» switch to a real rectangle. And the PWA now
   sends `Accept-Language` = app language on every request, so backend errors stop
   arriving in English on English-OS phones.

Import is online-only by design — an offline parse/commit refuses with a clear
message instead of queueing.

## What it does

The master gets a FOLDER of drawings from a designer (real case: 45 PDFs named
«1_обмірний план 1п.pdf», «3_експлікація 1п.pdf», «42_специфікація покриттів.pdf»)
and drops it into Majstr as-is (zip / several PDFs / photos). The system triages the
files, recognises the ~5 useful ones and creates rooms with a PACKAGE of measurements
(підлога / стеля / стіни / плінтус / відкоси), grouped by floor. What's missing is
asked minimally (ceiling height — once per floor).

## The two core decisions

1. **Text layer first, vision only as fallback.** Design-project PDFs carry a text
   layer — the schedule/specification tables extract EXACTLY with pdfbox, and the LLM
   only STRUCTURES the text (cheap text call, precise figures). A scan/photo falls
   back to the existing native `document`/`image` vision block. This removes the main
   recognition instability and most of the cost.
2. **The LLM never computes or invents.** It transcribes printed values (0 = "not
   printed" sentinel → null + low confidence server-side). Geometry is OUR code:
   perimeter (printed, or OUR sum of ≥3 printed wall segments), walls = P×H − openings,
   plinth = P − doors, reveals from window openings — derived client-side into payloads
   the server RE-computes on commit (`Shapes."direct"` for known areas, the existing
   LINEAR for runs). Unreadable notation (Нпд/Нпр) is transcribed as-is into notes,
   never interpreted.

## Classification is client-side (deliberate deviation)

The prompt sketched a server upload of everything; implemented instead: the archive is
unzipped IN THE BROWSER (fflate, ~8 KB) and classified by FILENAME there — of a
45-file archive only the ticked sheets are uploaded, one parse call each (95% of
masters are on phones, often mobile data). Server caps stay as the second line.
- Classifier (`src/lib/projectDocs.ts`): «обмірн» → PLAN_MEASURE, «експлікац» →
  ROOM_SCHEDULE, «специфікац/покритт» → COVERINGS, «розетк/вимикач/освітлен» →
  ELECTRICAL (a separate, parked step), furniture/elevations/plumbing/… → OTHER.
- **Floor comes from the FILENAME (`1п`/`2 поверх`/цоколь/мансарда), not the table** —
  the real archive's schedule is IDENTICAL on both floor sheets, so a table can never
  tell the floor. Sheet-stamp floor is the fallback for hand-assigned files.
- Zip guards client-side: entries listed WITHOUT decompression, only selected entries
  inflate, each capped by declared size (≤15 MB), ≤200 entries; names never touch a
  filesystem (traversal inert). 7z → friendly "not yet" (open question).

## Backend

- `Feature.PROJECT_IMPORT` (PRO+TEAM), same gate style as the other imports.
- `POST /api/projects/{id}/measurements/project/parse` (multipart: file + kind) —
  pdfbox text (≥150 chars) → text path; else native PDF document block / image block.
  ≤15 MB, ≤5 PDF pages (a bound set must be split — the picker sends sheets), magic-%PDF
  sniff, image types by name/content-type. File parsed and discarded.
- **Two passes for the measure plan via vision** («раз розпізнало все, раз одну
  кімнату»): pass 1 inventory (rooms only, completeness over detail), pass 2 details
  anchored to that inventory; a room pass 2 lost is re-added area-only with a warning.
  The text path doesn't need it — text is stable.
- Kind-specific prompts share one strict JSON schema (floors/rooms/coverings/totals/
  ceilingHeights/warnings); designer remarks («без запасу на порізку», «уточнити на
  місці») land in warnings verbatim.
- `POST .../commit` → `MeasurementService.createImported` — rooms(+floor) + items in
  one transaction, `result` recomputed server-side from payloads (same contract as the
  sketch commit).
- `Shapes."direct"` — a known area entered directly ({s}); mirrored in the PWA shape
  editor (equivalent-square diagram, area = s exactly), so imported items re-open
  editable like any other.

## PWA flow (`ProjectImportSheet`)

drop files → «візьму N із M» checklist (editable ticks; manual kind/floor for the
unrecognised; electrical marked «окремий крок») → sequential parse with progress →
merge by room number/name (schedule gives name+area, plan gives perimeter/openings) →
ceiling height asked once per floor when absent (blank = skip walls) → review cards
grouped by floor: each room's package with editable values (an edited number becomes a
`direct` payload — what the master confirmed is what's computed), missing elements
explained («потрібен периметр»), low confidence highlighted, «дані з проєкту,
уточніть на місці» honesty line, reserve % (default 0), coverings as a tick-in
reference list → «Додати N кімнат (M елементів)».

**Cross-check:** Σ recognised room areas vs the schedule's «Загальна площа»; >5% gap →
a loud «частина кімнат не розпізналась» warning.

## Floors in Заміри

`measurement_room.floor` is an ATTRIBUTE (free text), not a hierarchy level. The
rooms list groups by floor with per-floor totals only when at least one room has a
label — objects without floors render exactly as before. The add-room modal gained an
optional floor field.

## Not changed / confirmed

- Manual measurements, sketch/receipt/estimate imports, unit-filtered substitution
  into estimates, the PRO gate — only ADDED to.
- Electrical from plans stays a separate parked step; LED metres still never estimated.
- Files are never stored; Sentry never sees file contents.

## Gotchas

- `MIN_TEXT_CHARS = 150`: below that a "text layer" is just a stamp — vision instead.
- The uploaded basename must keep its real extension — image media types resolve from
  the filename (PDFs are magic-byte-sniffed regardless).
- `direct` areas scale by the payload unit² like every shape — import always writes
  `unit:"M"`.
- Multipart ceiling raised to 15 MB/18 MB (`application.yml`) — per-import caps still
  enforce their own tighter limits in code.
