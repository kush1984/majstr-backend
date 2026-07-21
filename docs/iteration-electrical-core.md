# Iteration: electrical core — points off a plan (LLM) + chase metres (deterministic)

> **⚠️ STATUS: PARKED (UI-disabled) — 2026-07-21.** The whole feature below is built, wired and
> green (backend + PWA + tests), but the **entry points are hidden** behind a single flag,
> `ELECTRICAL_MEASUREMENTS_ENABLED = false` in
> `majstr-pwa/src/features/measurements/MeasurementsSection.tsx`. Reason: after building points →
> cable/chase split → plan-fed calculator → 2D drawn bus, the **right product shape still needs
> more thought** (how points, rooms, cable and chase best combine for a real electrician's
> workflow), and a more important task took priority. Nothing was deleted — the calculator, plan
> sheet, `PlanEditor`, measurement types (`ELECTRICAL_POINTS`/`SHTROBA`/`CABLE`), the parse
> endpoint and V60/V61 all remain. Flip the flag to `true` to bring the block back. The площі block
> still filters electrical items out, so any already-saved electrical items are simply hidden, not
> lost. Re-open this decision in [open-questions.md](open-questions.md) («Electricians ask LLMs for
> chase/cable METRES»).

Two halves, deliberately split by how reliable each is:
- **A. Points** — Claude vision counts DISCRETE symbols against the plan's legend. A reading
  task, like a receipt. Reliable.
- **B. Chase metres (штроби)** — computed by ordinary code from points the master enters
  (bus + drops). Never estimated from the drawing.

- **Status:** 🔨 Backend complete; PWA partial (see "Remaining"). Backend build on the user.
- **Migration:** **V60** — widens the `measurement_item` CHECKs for the new types + `PIECE`.

## The decision this iteration is built on

A real electrician fed a full project PDF (`Belgradska_1405.pdf`, a multi-sheet set) to
ChatGPT and Gemini and asked for **chase lengths / how much cable he needs**. Both will
happily answer with a confident number. That number cannot be trusted: it requires reading
geometry at scale off a drawing, and being wrong there is invisible — the total looks
plausible and flows straight into a quote.

That user story is the clearest argument for the split, not against it:
- what the master *asked for* (metres) is what an LLM is worst at;
- what he *actually needs* (a defensible number) comes from counting points reliably and
  then computing the run with arithmetic he can see and check.

So: the model counts, the calculator measures, and the wall diagram shows where every metre
came from. If we ever show a chase length the master can't trace, we've become the thing we
were trying to beat.

## Model choice (why new measurement types, not a separate entity)

`ELECTRICAL_POINTS` (unit **шт**) and `SHTROBA` (unit **м.пог**) are new
`MeasurementType`s — ordinary `measurement_item` rows inside rooms.

Substitution into estimate lines already works purely **by unit** (`MeasurementPicker`
filters `item.unit === line.unit`; `sumForRefs` re-checks it server-side). So a new type
gets the picker, the server-authoritative `result`, the PRO gate and the room tree for free.
A separate "electrical specification" entity would have meant re-building all of that.

Consequence handled: `MeasurementsResponse` gained **`pieceTotal`** — without it a count of
sockets would have been summed into the object's m² figure.

## A. Points off a plan
- `ElectricalPlanService` — the **fourth** prompt on `ClaudeEstimateExtractor` (estimate,
  receipt, sketch were the first three), reusing its one Anthropic client via `requestJson`.
- `POST /api/projects/{id}/measurements/electrical/plan/parse` (multipart). Parse only:
  nothing is written, the file is discarded. The confirmed counts are committed through the
  **existing** add-measurement-element endpoint — no second write path to keep in sync.
- **PDFs go to Claude as a native `document` block** (new `pdfContent` helper). The deploy
  has no poppler, and Anthropic renders PDF pages itself — so no server-side rasterising.
- The prompt names the **real legend wording** taken from actual Ukrainian project plans
  («Вимикач прохідний 1 кл.», «Розетка ТВ», «Вбудований світильник на 2 лампи», «Бра»,
  «Вивід живлення», «ЛЕД підсвітка», «Точка під'єднання ЛЕД», …), so the model matches
  labels it will really see instead of inventing categories.
- Hard rules in the prompt: count discrete symbols only; never measure anything; LED strip
  is drawn as lines → only flag `ledStripPresent`, never estimate its length; unsure → the
  closest legend entry with `confidence: low` + a note; a type it can't count → `count 0`,
  low confidence, never a guess.
- Server-side: a missing/zero count is forced to `confidence: low` rather than trusted.

## B. Chase calculator (deterministic, no LLM)
Formula (`MeasurementCalc.shtroba`), all lengths in **millimetres** — that's how plans
annotate (`h=300` socket, `h=900` switch, `h=2600` A/C outlet):

- bus level = `busLevel` when the bus runs along the top, else `0` (floor);
- a point's drop = `|bus level − its height| × count`;
- the bus itself = the **furthest** point's along-the-wall distance, counted **once**
  (variant A: one shared horizontal);
- room total = bus + Σ drops, then `× (1 + reserve%)`;
- result in **м.пог** (mm ÷ 1000).

Bus-from-top vs bus-from-floor is a **per-room** choice (ground floors are usually chased
from the ceiling, upper floors from the floor). `WallDiagram` redraws the wall to the
entered numbers, and the editor shows **bus and drops separately** — never a black-box total.

Deviation from the prototype (`shtroba-calculator.jsx`): the prototype used the **ceiling
height** in **cm** as the bus level. Here the bus level is its own parameter in **mm**, per
the prompt and per the plans — the bus is a chosen chase height, not the ceiling.

## Real-world input that shaped this

| Source | What it gave |
|---|---|
| 4 sheets of a design project (sockets/lighting, 2 floors) | The legend wording now in the prompt; heights annotated in **mm**; notes confirm heights are «від рівня чистової підлоги» — the same datum the calculator uses |
| `Belgradska_1405.pdf` (electrician's own set) | The user story above; also a practical warning (below) |

**Multi-sheet sets are the norm, not single sheets.** `Belgradska_1405.pdf` is a whole
project (tens of pages: plans, sections, visualisations), and its text is not extractable —
subsetted fonts (`RDQDGF+FuturaPT-Demi`) with ToUnicode maps. Two consequences:
1. Recognition must be **visual** (Claude renders the PDF), which is what we do — text
   scraping would fail on exactly this kind of file.
2. Sending a whole set is wasteful and risky: the model may count across the wrong sheet
   (e.g. mixing floors). **Page selection is needed** — see open questions.

## PWA
Types (`pieceTotal`, the two payloads), `WallDiagram`, both editors inside
`MeasurementItemForm`, `electricalPlanApi`, i18n (uk+en), and `ElectricalPlanSheet` — the
plan review screen: pick PDF/photo → the counts come back as editable rows (low-confidence
ones highlighted with the model's note, LED flagged) → commit.

The commit goes through the **ordinary add-element mutation** as an `ELECTRICAL_POINTS`
item, so there is one write path, not two. That needs a room; when the object has none the
sheet creates «Електрика» first instead of dead-ending on an empty tree.

`pieceTotal` shows in the object and room totals only when non-zero, and the item row now
resolves its unit through `units.{unit}` — the old two-way ternary would have printed «шт»
counts as m².

**Placement (revised per master feedback).** «План» next to «ескіз» in one row confused
(«що за план, що за ескіз»), and electrical isn't «площі по кімнатах». So the «Заміри» tab
is now **two labelled blocks**:
- **«Кімнати · площі»** — the area/room workflow (sketch recogniser + rooms). Rooms show
  only non-electrical items; block-A linear total excludes SHTROBA (split by TYPE, since a
  chase is also м.пог). The electrical bucket room is hidden here.
- **«⚡ Електрика»** (electrician-only: `me.trades` includes `ELECTRICAL`) — object electrical
  totals (шт + м.пог), the electrical rows flattened across rooms, and the two tools:
  **«Розпізнати план»** (points) + **«Калькулятор штроб»** (opens `MeasurementItemForm` pinned
  to SHTROBA via the new `allowedTypes` prop, into the «Електрика» room, created if none).

This block is the deliberate **seed of a future «Калькулятори» hub** (tiler glue, plaster mix,
etc. would join as sibling entries) — so it generalises without a repaint. No backend change:
electrical stays `ELECTRICAL_POINTS`/`SHTROBA` measurement rows that substitute into estimate
lines by unit. `MeasurementItemForm` gained `allowedTypes` so «площі» offers only area types.
Tests: `MeasurementsSection.test` (areas-vs-electrical split + trade gate).

**Mobile:** the electrical block's two tools sit on a 2-column 44px row; area recogniser is a
full-width button. Measured at 375px — no overflow.

## Phase A — plan → rooms + heights + chase draft (per master feedback)

Real plans annotate **everything in text** (heights «h=900», dimension figures in mm), so the
model **reads printed numbers** — a reading task, still not pixel-measuring. Delivered:

- **Backend contract** — `ElectricalPlanParseResponse` is now `rooms[]`, each `{ name (or "" →
  «Кімната N»), approxWidthMm (largest printed room dimension, mm — seeds the bus length),
  points[] }`. `ElectricalPlanService` prompt+schema rewritten to group by room, read h= and
  the room width, default-name rooms, and flag grouping/width as the least-reliable (draft,
  low-confidence). Rule refined: reading a PRINTED figure is allowed; MEASURING (pixels/scale)
  is not.
- **PWA `ElectricalPlanSheet`** — multi-room review (editable rooms/points/width, per-point h=
  shown, low-confidence highlighted). On commit each room is created (default-named when blank)
  with its `ELECTRICAL_POINTS` and — toggleable, default on — a **chase draft** `SHTROBA` seeded
  from the read heights (drops) + width (bus): `busLevel 2600`, kind mapped from the legend
  wording, `x = width`. The master then refines on `WallDiagram`; the metres are always
  recomputed deterministically.
- **PDF page selection** — `pdf-lib` (dynamic-imported) reads the page count; a multi-page set
  prompts for pages («3» / «3-4» / «1,3,5»), extracts just those client-side, and sends only
  them — so the model never counts across the wrong sheet. Closes the multi-sheet open-question
  for the common case (thumbnail preview is a later nicety).
- Tests: `ElectricalPlanSheet.test` (rooms grouping, low-confidence/LED, commit creates room +
  ELECTRICAL_POINTS + SHTROBA), `pdfPages.test` (`parsePageRange`). tsc + full vitest + build green.
- Deferred: Phase B (explicit bus size/entry side) and Phase C (2D draggable room schematic).

## Rework after a real-plan test — cable ≠ chase, explicit bus, flat points (Phase B folded in)

A real electrician's plan (`24_вимикачи і розетки 1п.pdf`) exposed three wrong assumptions in
Phase A. The rework (this is where the model above is *actually* correct — Phase A's rooms +
`approxWidthMm` bus are superseded):

1. **Cable and chase are TWO separate estimate entities**, not one. Cable (кабель) is a
   **MATERIAL** (metres of wire, unit **м**); a chase (штроба) is **WORK** (unit **м.пог**).
   They must be priced on different lines, so they are two `MeasurementType`s — new
   **`CABLE(Unit.M)`** beside `SHTROBA` — computed from **one shared `ShtrobaPayload`**:
   - **CABLE** = `busLength` + Σ **every** drop, then `× (1 + reserve%)` — the wire reaches
     every point and needs slack.
   - **SHTROBA** (chase) = (`busLength` if `busChase`) + Σ drops whose point is flagged
     `chase` — only what is actually **cut**. No reserve.
   `MeasurementCalc.compute` gained a `CABLE` case; both read the same payload. The unit
   bucketing in `MeasurementService` now sends **`M` to no room total** (cable is an electrical
   figure surfaced in the ⚡ block, never folded into the m² area). **V61** widens the two
   CHECKs (`type` +CABLE, `unit` +M).
2. **The bus length is EXPLICIT** (`busLength`, set by the master), never guessed off the
   drawing — that guess was the wrong «магістраль 1385» (it had taken the room width). Per-room
   `busChase` (a ceiling bus isn't cut) and per-drop `chase` (an un-plastered wall is wired but
   not chased) let the master include exactly the величини that apply — the `ShtrobaPayload`
   `x` field is **gone**.
3. **The plan returns a FLAT point list (variant 2)** — no room grouping, no size reading (LLM
   geometry is unreliable). `ElectricalPlanParseResponse` is now `points[]`; the prompt/schema
   count symbols + read printed `h=` only. The reviewed points **seed the calculator directly**
   (each point → a chased drop, kind mapped from the legend, height read) — there is no separate
   «чернетка»; titles are **«Калькулятор штроб / кабелю»**.

PWA: `MeasurementItemForm`'s SHTROBA/CABLE editor is the calculator — explicit `busLength`,
`busChase`, per-drop `chase`, and a live breakdown of all four величини (bus, all drops,
**cable**, **chase**). Saving a new calculator writes **both** a SHTROBA (work) and a CABLE
(material) item from the one payload (`MeasurementsSection.saveItem`); editing either later lets
them diverge (accepted for now). `WallDiagram` drops the `x` positioning (even, schematic
spacing; uncut bus/drops drawn dashed). `ElectricalPlanSheet` is a flat review that hands the
points to the section via `onApply` → saves the counts + opens the seeded calculator.
Tests updated: `MeasurementCalcTest` (chase vs cable, per-drop/bus exclusion, reserve),
`MeasurementItemForm.test` (explicit bus + per-drop chase), `ElectricalPlanSheet.test` (flat +
seed), `MeasurementsSection.test` (both entities in the ⚡ block).

## Phase C — measure the bus off a drawn plan (2D editor)

The explicit `busLength` field got a second, visual way to set it: a **top-down room plan the
master draws on**, and the bus length is **measured off the drawing** (same honesty as the rest —
the master draws a line, we measure it; nothing is inferred from an image).

- `src/lib/planBus.ts` — pure geometry: normalised `[0..1]` coords, `busLengthMm(path, W, L)`
  (each segment scaled by the room's real width/length, so a diagonal reads correctly), plus
  `defaultBus`/`defaultPoints`. Unit-tested (`planBus.test.ts`).
- `src/components/PlanEditor.tsx` — a to-scale SVG room rectangle with a **draggable bus polyline**
  (first vertex = the entry/щиток) and **draggable reference points** (coloured by drop kind).
  Touch-first: pointer events (mouse+touch), `touch-none` so a drag doesn't scroll the sheet,
  20 px invisible hit targets, `w-full` responsive canvas.
- In `MeasurementItemForm` the bus has a **[Вручну | На плані]** toggle. «На плані» shows the room
  W/L inputs + the editor + a live «довжина: X м»; the drawn length then feeds the calc and is
  saved as `busLength` (`effectiveBusMm`). The elevation `WallDiagram` stays for the drops.
- **Not persisted (deliberate, no backend change):** only the resulting `busLength` number is
  saved; the drawn geometry is a transient aid (re-opening an item shows the number in «Вручну»).
  Persisting the plan would need the payload/record extended — a future step if masters want it.
- Tests: `planBus.test`, `PlanEditor.test` (renders room + entry + a dot per point). tsc + full
  vitest + build green.

The whole electrical iteration (points → cable/chase split → plan-fed calculator → drawn bus) is
now in place; the LLM still never produces a length.

## Not changed / confirmed
- Existing measurement types, shapes, substitution, unit filter, the three existing LLM
  imports — electrical only ADDS types and a prompt.
- The LLM never produces a length: not chase, not cable, not LED strip.
- PRO-gated through the existing `MEASUREMENTS` feature; owner-scoped; the plan file is
  discarded after parse.

## Gotchas
- `MeasurementCalc.compute` switches exhaustively on `MeasurementType`, so adding a type is
  a compile error until it's handled — deliberate.
- V60 had to widen **both** CHECKs: `type` (new kinds) and `unit` (`PIECE` was not allowed
  on `measurement_item` before).
- Heights are millimetres end-to-end. Mixing in centimetres (as the prototype did) silently
  changes every chase total by 10×.
