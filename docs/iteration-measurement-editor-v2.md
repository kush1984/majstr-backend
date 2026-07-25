# Iteration: measurement editor v2 (real per-wall geometry) + room drag-drop

From detailed field feedback on the project-import review: the imported package hid
its geometry behind a single «Стіни» number and a «direct area», values were lost on a
shape switch, and there was no way to move a room between floors on a phone. Reworked the
import package into real, editable elements; made room re-flooring drag-and-drop; and
folded the research's recognition-robustness notes into the extractor prompt.

- **Status:** 🔨 code complete; backend build on the user
- **Migration:** none (no schema change — payloads only)
- **PWA:** 0.29.0

## 1. The import package is real geometry now, not a summary

Before, a recognised room produced one lumped `Стіни` element (perimeter × height − openings
as a single figure) and floor/ceiling as a `direct` area. The master couldn't see or edit the
dimensions behind those numbers.

`projectImportMerge.ts` gained a `PackageElement` model (`buildPackage` / `buildRoomPackage`).
Every element is a **real shape with visible fields**:

- **Floor / ceiling** — a rect `a×b` (width×length) when the gabarits were read; when only a
  document **area** is known (no sizes), the fields start **empty** with a one-tap «взяти площу»
  that commits the doc area as a `direct` payload. Ceiling still starts OFF (a floor duplicate
  for a rectangular room).
- **Four separate walls** — «Стіна 1…4», each its own `run × height` rect. Confirmed gabarits
  seed the runs `[w, l, w, l]`; without them the runs are **empty to measure on site** (height
  pre-filled where known). Openings sit on wall-1 and are subtracted from its net area. This is
  the master's ask verbatim: «стіна права, стіна ліва… всі вони разом із підлогою і стелями
  мають мати видимі поля для підрахунку площі».
- **Plinth / reveals** — a plain running **length** (metres), committed as a **length-mode
  LINEAR** payload (`mode: 'length'`, backend `MeasurementCalc.linear`). This retires the old
  hack that abused the reveal-sides formula (`{height:0, width:len, sides:{top:true}}`), which
  is what made the editor show a nonsensical «Сторони відкосу · Верх» for a skirting.

`ElementEditor` (in `ProjectImportSheet.tsx`) renders each element with its own enable
checkbox, name, dimension fields, and live m²/м.пог. `elementPayloadV2` maps each to the exact
payload the server recomputes (`rect` for surfaces, `direct` for a taken area, length-mode
LINEAR for plinth/reveals; **null** for an empty element so it's simply skipped — never a
guessed value). `roomItems(elements)` emits only enabled, resolvable elements.

**Consequence, stated honestly:** without confirmed per-room gabarits the walls now import as
four *empty* walls to measure, instead of one perimeter×height total. That's the deliberate
trade — real per-wall figures the master measures on site, no invented split — and it matches
the «порожні поля коли величина не прочитана» decision.

## 2. Shape switch keeps what you typed

Switching a plane's shape/mode in `ShapeInput` used to wipe the entered numbers
(«переключився і всі дані стерлись»). Added a component-local `useRef` cache keyed by
`shape:mode`: `switchTo` stashes the current values before switching and restores them (or a
fresh empty set) on the way back. Live while the plane's editor is open.

## 3. Drag a room to another floor (touch + mouse)

`MeasurementsSection` groups rooms by floor. Each room card now has a grip handle that starts a
**Pointer Events** drag — one code path for touch (the phone, the primary case) and mouse. A
floating label follows the finger (`pointer-events-none` so `elementFromPoint` sees the floor
group under it); the hovered group highlights; on release the room is re-floored via the
existing `updateRoom` mutation. The handle only appears when there's more than one floor group;
the rename dialog's floor field stays as the keyboard fallback.

## 4. Recognition robustness (from the research subagent)

Folded the sub-agent's findings into the extractor `COMMON_RULES` / `PLAN_PROMPT`
(`ProjectImportService`), no code path change:

- **Vision-first** — real ArchiCAD/GSPublisher PDFs often carry Cyrillic CID fonts whose text
  layer doesn't extract; the prompt now says to read the pixels, never a text transcription.
- **Number formats** — comma decimal separator; ignore a stray superscript «²»/«2» and
  «мм»/«mm»/«м» suffixes.
- **Broader ceiling notation** — «H=», «H », «H-», Cyrillic «Н=», optional space, optional
  trailing «*». Still explicitly **excludes** «Нпр=» (opening height) and «Нпд=» (window sill),
  which remain opening/sill — the hard-won Belgradska rule.

## Tests
- PWA: `projectImportMerge.test` rewritten for the v2 model — `buildPackage` (gabarits → 4
  walls; area-only → taken floor + empty walls; gabarits-no-height → walls wait on height),
  `elementValue` / `elementPayloadV2` / `roomItems`. `ProjectImportSheet.test` commits the
  full 4-wall package (floor rect + Стіна 1…4 + плінтус + відкоси). tsc + full vitest (252) +
  production build green.
- Backend (on the user): existing `MeasurementCalcTest` covers length-mode LINEAR;
  `ProjectImportServiceTest` mocks the extractor so the prompt edits don't touch it.

## Gotchas
- The four walls share `kind: 'wall'` but have distinct keys `wall-1…4`; only `wall-1` carries
  the openings, so the net-area subtraction happens once.
- An enabled-but-empty element renders (inviting input) yet commits nothing — `elementPayloadV2`
  returns null and `roomItems` skips it, so the «Додати N кімнат · M позицій» count stays honest.
- Pointer drag needs `touch-action: none` on the grip (Tailwind `touch-none`) so the browser
  doesn't hijack the gesture for scrolling; the ghost must be `pointer-events-none` or
  `elementFromPoint` returns the ghost instead of the floor group.
