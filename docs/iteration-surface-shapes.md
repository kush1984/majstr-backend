# Iteration: complex shapes in a SURFACE measurement (drawn schemas)

A SURFACE element stopped being "length × width". It is now **Σ planes − Σ openings**,
where each plane is a chosen **shape** (rectangle / trapezoid / mansard / triangle /
cut corner) drawn as a **schematic** whose letters *are* the input fields. A mansard
ceiling is 2 rectangles + a triangle — a sum of simple planes, not a magic figure.

- **Status:** 🔨 Code complete — PWA green (tsc / tests / build); backend build on the user.
- **App version:** PWA `0.10.2 → 0.11.0` (headline capability → minor).
- **Migrations:** **none** — the payload is JSON text; old rows read as before.
- **Not PRO-gated separately:** shapes ride inside the existing `MEASUREMENTS` gate; the
  single-line calculator stays free (it always was).

## Recon (confirmed before coding)

- Measurements are merged (`013ab6c`, V46). SURFACE payload was
  `{segments:[{l,w}], openings:[{w,h,n}]}` → **extended**, not replaced.
- The single-line calculator and the SURFACE editor **already shared** one engine
  (`computeMeasure` in `MeasureCalculator.tsx`, imported by `MeasurementItemForm`), so
  there was one place to graft shapes into — no duplicate geometry.
- **The prompt's "reference implementation we already have" is not in either repo** —
  it lives at `C:\Work\prompts\area-calculator.jsx` (a standalone `.jsx` prototype). The
  user supplied it; its geometry matches the prompt's spec exactly and was ported as-is.
- SPEC §G2 warns "don't model complex geometry automatically (mansards) — doomed and
  over-complicates the UI" and prescribes mode 2 = *add surfaces piece by piece*. This
  iteration **is** that mode 2, with parameterised shapes: the master picks each plane.

## Design

### Geometry: shoelace over built vertices, never per-shape formulas
`area = |Σ(xᵢ·yᵢ₊₁ − xᵢ₊₁·yᵢ)| / 2` on vertices built per shape. One code path for every
shape, including skewed ones (the `sss` triangle places its apex from the side lengths and
shoelaces it — no separate Heron branch). The per-shape "Площа = …" strings are shown to
the master as a **hint**, not used to compute.

### The server stays the source of truth
Ported to `Shapes.java` rather than trusting a client-computed number. The codebase
invariant is "the client never sets `result`" and `MeasurementService.sumForRefs`
recomputes estimate quantities server-side precisely so a forged number can't become
money. Storing a front-computed area would break that. Cost: the geometry exists twice
(TS + Java) — already true of `computeMeasure` ↔ `MeasurementCalc.surface`, and the
prompt requires front/back parity anyway. **Both sides compute in `double`** so they agree
to the last rounded digit; the backend tests pin the *same numbers* as `shapes.test.ts`,
so a drift fails on both sides.

### One unit per element, not per plane
The reference calculator has a single мм/см/м switch, and so does an element here: the
unit applies to its planes **and** its openings. A per-plane unit would let a master enter
a 300×250 **cm** wall next to a 0.9×1.4 **m** window and silently subtract 12600 m².
Default is **metres** — what every dimension in the app meant before units existed, so an
existing habit can't produce a wrong area.

### Backward compatibility without a migration
A segment with **no `shape`** is a pre-shapes rectangle in metres; a **missing `unit`** is
metres. Old payloads therefore compute exactly as before. On open, the editor maps a legacy
`{l,w}` to a rectangle plane (`planeFromLegacy`), so a save silently upgrades the row.

## What shipped

**Shared module (PWA)** — `src/lib/shapes.ts`: `shoelace`, the `SHAPES` registry
(shape → variants → fields/build), `Plane`/`PlaneDraft` (+ `toPlane`/`toDraft` so inputs
can hold `""`/`"1,5"` while geometry gets numbers), `planeAreaM2`/`planesAreaM2`,
`planeFromLegacy`. Pure — no React, no i18n (labels are keys).

- `src/components/ShapeDiagram.tsx` — the SVG: polygon scaled to fit, letters on the
  midpoints pushed outside the centroid, vertices A/B/C…, dashed height with a right-angle
  marker. Before anything is typed it draws the **reference outline dimmed** instead of an
  empty box, so the letters teach from the first render.
- `src/components/ShapeInput.tsx` — shape chips, mode toggle (mansard sym/asym, triangle
  bh/sss), letter-badged fields, warn/note, "скіс для звірки", per-plane area.
- `MeasurementItemForm` — SURFACE = unit switch + plane cards (add/remove) + openings +
  **intermediate sums** (Σ planes, − openings) before the result.
- `MeasureCalculator` — same planes in the line calculator's *area* mode (shared module,
  no duplicate); *length* mode untouched. `computeMeasure` retired in favour of
  `planesAreaM2` + `sumLengths`/`openingsAreaM2`.

**Backend** — `Shapes.java` (mirror), `MeasurementCalc.surface` → Σ planes(×factor²) −
Σ openings(×factor²); `SurfacePayload` += `unit`, `Seg` += `shape`/`mode`/`values`.
Invalid dimensions → `MeasurementException` → **400 `error.measurement.invalid`**, not
stored.

**Tests** — `shapes.test.ts` (shoelace, every shape + both modes, ok/warn conditions, unit
conversion, legacy, comma decimals), `MeasureCalculator.test` (lengths/openings),
`MeasurementItemForm.test` (SURFACE plane payload + legacy read-back),
`MeasurementCalcTest` (+11: every shape, mixed planes, legacy coexistence, rejects).

## Deviations from the prompt (deliberate — flag if unwanted)

- **400, not 422**, for invalid shape params: `MeasurementException` already maps to 400
  and the sibling validations (negative dimension, unit mismatch) are 400. A 422 for this
  one case would need a new exception + handler + message and be inconsistent.
- **Units are per element, not per plane** — see above (the 12600 m² footgun).
- The reference's pre-filled default dimensions became **outline proportions only**; fields
  start empty (the project's empty-first discipline: no silent wrong number).

## Not changed / confirmed

- PARTITION and LINEAR untouched — they have their own faces/sides constructors.
- Measurement model (rooms/items/refs), substitution, unit filter, selection memory,
  PRO gate, sign/portal — all untouched.
- No new units on estimate lines: a SURFACE is still M2.

## Gotchas

- **`shapes.ts` and `Shapes.java` must move together.** The tests pin identical numbers on
  both sides; that's the tripwire.
- JSX gotcha: `<span>{tag}</span>{desc}` renders `textContent` "hвисота" (no space) — the
  label needed an explicit `{' '}`, which also fixes the accessible name.
- **Round once, at the end.** `planesAreaM2` sums unrounded and rounds the total, because
  the backend adds exact BigDecimals and clamps once. Rounding per plane (the obvious first
  cut) drifts: two 0.0005 m² planes → 0.002 on the front vs 0.001 on the server.
  `planeAreaM2` still rounds, but it's display-only (the per-plane figure).
