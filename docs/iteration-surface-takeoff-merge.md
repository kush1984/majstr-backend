# Iteration: surface (площі) takeoff hardening — merge the archive's opening model

Follow-up to editor v2. A second agent's archived "album takeoff" feature (see the
`majstr.7z` review) carried a richer **surface** opening model than ours. We took the
useful, площі-relevant parts and folded them into our existing import + editor rather than
adopting its whole server-side/album pipeline. Electrical stays parked and out of scope.

- **Status:** 🔨 code complete; backend build on the user
- **Migration:** none (payloads + extraction only)
- **PWA:** 0.30.0

## 1. Openings that reach the floor break the skirting (`toFloor`)

Before, only a `двері` opening interrupted the plinth. Real rooms also have open passages
and floor-to-ceiling / panoramic windows that reach the floor — they break the skirting too,
while an ordinary window on a sill leaves it running underneath.

- Backend: `ProjectImportParseResponse.Opening` gained a **`Boolean toFloor`** (wrapper type
  — the VALIDATION.md Jackson-3 lesson: a primitive would fail `FAIL_ON_NULL_FOR_PRIMITIVES`
  on an older-shape payload). The schema (`OPENING`) and the extraction prompt now carry it;
  a door is forced `toFloor=true` regardless of the model (a door always reaches the floor).
- PWA: `ProjectImportOpening.toFloor?`. `buildPackage` plinth = `perimeter − Σ widths of
  floor-reaching openings` (`o.toFloor ?? o.kind === 'двері'` — the fallback keeps older
  payloads correct).

## 2. Window sills (Підвіконня) as an optional element

`buildPackage` now seeds a **`sill`** element (Σ window widths, м.пог), **OFF by default**
like the ceiling — only some trades fit/finish sills, but the running length is pre-computed
so a one-tap enable is instant. It commits as a length-mode LINEAR (the same shape as
plinth/reveals). New `ElementKind 'sill'`; `isLinearKind` is now the single source of truth
for "runs in м.пог" (plinth/reveals/sill) across value/payload/roomItems and the editor.

## 3. Interior doors deducted from BOTH rooms

An interior door between two rooms reduces the wall area of **both** finished rooms. Our
per-room, client-compute model already subtracts each room's own openings, so the fix is at
extraction: the prompt now tells the model to **list a shared interior door in both rooms'
openings**. No structural `room_a/room_b` schema change (that fit the archive's server-side
`RoomSurfaceCalc`, not our per-page merge) — the same outcome, far less surface area. Failure
mode is strictly ≥ before: if the model lists a door once, only that room deducts it (as
today); if twice, both do (correct).

## 4. Broader height conventions in the prompt

The plan prompt now reads an opening height from **`Нпр`**, **`Ндв`** (door leaf) or
**`Нвк`** (window) or a doors/windows spec — on top of the ceiling `H=`/`Н=` set added in the
editor-v2 pass. `Нпд` stays the window **sill** height, never the ceiling.

## 5. Honesty summary on the review («Джерела/Відсутнє» in spirit)

A compact coverage line at the top of the import review: **площа X/N · розміри Y/N · висота
Z/N** (green ✓ when full, amber • when partial). площа = the schedule area, розміри =
checksum-confirmed gabarits, висота = plan `H=` or the answered floor height. A metric below
its total is itself the honest "звірити" signal — complements, not duplicates, the amber
warnings block. Mirrors the Dublyany takeoff's «Джерела_і_статуси» first sheet, mobile-first.

## 6. Recognition prompt hardening (the archive's §2 + methodology Steps 1–2)

A second pass over the archive's `system-prompt-extraction.md` §2 and
`PROMPT-takeoff-electro.md` Steps 1–2 against ours found six real gaps. All six applied to
`COMMON_RULES` / `PLAN_PROMPT` — prompt-only, no schema or code change:

- **A. «5 000» = 5000 mm** — a SPACE is a thousands group in a dimension chain. This was the
  dangerous one: we already told the model «a comma is the decimal separator» with no space
  rule, so «5 000» could read as 5 or 5.000 → silently wrong gabarits → the checksum rejects
  them → the master types everything by hand. Both conventions are now stated side by side.
- **B. Self-check before answering** — `width × length ÷ 1e6` must match the room's table area
  within ±0.3 m²; mismatch → re-read the chain ONCE; still off → report the figures actually
  seen (never bend them), `confidence: low` + reason. Our client checksum only ever *rejected*
  unproven gabarits after the fact; this makes the model correct itself before replying.
  Reconciled with the existing «do not compute» rule, which now says: compute privately to
  check, never report a computed number as printed (a contradiction here degrades output).
- **C. Two sets of plans** — «як є» vs «після перепланування», often differing ONLY by the
  sheet title → the base geometry is the one AFTER remodelling (Dublyany had exactly this).
  Plus the general rule: the sheet's own title/stamp outranks the file name.
- **D. Photographed sheet** — read only printed labels/tables/symbols; never take a dimension
  off a photo (perspective distorts it); an unlabelled size is unknown, not an estimate.
- **E. A doors/windows spec table outranks a chain reading** (`high` vs `medium` confidence) —
  the cheap version of the archive's `from_spec`/`counted` provenance, with no schema change.
- **F. Mansard/slope, niche or ledge → the room's note** — our editor now builds FOUR straight
  walls, an assumption a mansard breaks; the master must be told to check on site.

Deliberately NOT adopted from their prompt: letting the model SUM contour segments into a
perimeter (ours returns the printed segments and **our code** sums them), and «height not
printed → take the neighbouring room's as an assumption» (we ask the master per floor instead).

## Deliberately NOT taken (out of scope / rejected)
- The whole server-side album pipeline (`RoomSurfaceCalc`/`ClaudeAlbumExtractor`/async job) —
  our client-compute model + per-page merge is cheaper and already shipped.
- Reveals as m² (depth × run) — reveals stay running metres, consistent with the archive.
- Electrical takeoff (`ElectroTakeoffCalc`) — electrical measurements stay parked.

## Tests
- PWA: `projectImportMerge.test` — `toFloor` plinth interruption (panoramic vs on-a-sill),
  sill element (Σ window widths, off, length-mode payload). `ProjectImportSheet.test` — the
  coverage summary renders; the 4-wall commit is unchanged (sill off → not committed).
- Backend (on the user): `ProjectImportServiceTest.planRoom…` now asserts `toFloor` parse
  (window false / door true, door forces it even with the field absent).

## Gotchas
- `toFloor` is a **wrapper `Boolean`**, deliberately (Jackson-3 primitive-null trap).
- The sill is OFF by default — it must never inflate the «Додати N кімнат · M позицій» count
  (`roomItems` skips disabled), same discipline as the ceiling.
- Shared-door "both rooms" is an **extraction** instruction, not merge logic — don't add
  cross-room dedup; each room owns its opening copy.
