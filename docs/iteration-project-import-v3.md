# Iteration: project import v3 — tiling the sheet, then merging the readings

> **Retrospective doc.** Written during the 2026-07-27 catch-up from `fa83d8e` (+ the PWA-side
> `bounding-box` verdict in the working tree). ~1400 lines.

- **Status:** ✅ shipped (PWA merge refinement still uncommitted at time of writing)
- **Migration:** none
- **PWA:** `projectImportMerge` gained the `bounding-box` verdict

## The symptom, and why the cause was not obvious

The master's report was precise and repeatable: **room names and areas come back correct, every
dimension comes back 0.** Reported on real albums (Дубляни, Белградська).

The cause, measured rather than assumed: a designer's sheet is A3 and the dimension chains on it
are set in **8 pt**. A provider handed a PDF page downscales it to roughly 1568 px on the long
edge — which leaves those digits about **10 px tall**. They arrive in runs («535 800 925 1180»)
where one misread digit silently becomes a wrong wall. The rooms table on the same sheet is 10 pt
and reads fine. That asymmetry is exactly the reported symptom, and it explains why "the model is
bad at drawings" was the wrong conclusion.

## `SheetTiler` — render it ourselves, at a resolution we choose

Instead of handing over the PDF page, the server rasterises the sheet and sends it as **four
overlapping quarters at 200 DPI**. Each fragment gets its own 1568 px budget, so the same digits
arrive **25–30 px tall**. Nothing is invented and nothing is enhanced — it is the same page, just
not thrown away before the model sees it.

**The overlap (8 %) is load-bearing:** a dimension chain sitting on a seam would be cut in half in
both neighbours. At 8 % it lands whole in at least one fragment.

## `SheetMerge` — folding several readings of one sheet

Pure map-to-map functions on the schema shape, **deliberately** — this is the step where a bug
silently produces a plausible-looking wrong wall, so it has to be testable without spending a
model call. Every rule answers one question: given two readings of the same figure, which
survives?

- **The whole-page pass owns the table.** Room numbers, names, areas are set at a comfortable size
  and read reliably; a fragment sees only part of the table, or none of it.
- **Fragments own the geometry.** That is the entire reason they exist.
- **Disagreement is never resolved silently.** If both readings produce a figure and they differ
  by more than 2 %, the whole-page value stays **and** the field is marked uncertain — the master
  gets a number plus a reason to check it. Picking a winner by rule would be guessing with extra
  steps.
- **A room only a fragment saw is kept, with a warning naming it.** Dropping it is how a room
  disappears from an estimate without anyone noticing.

## PWA side: the `bounding-box` verdict

Related and worth recording because it follows the same "keep what is usable" principle.

The checksum used to have two outcomes for gabarits that disagree with the printed area: L-shape
(if the cut was read) or **reject everything**. But the perimeter of an L-shaped room **equals**
the perimeter of its bounding rectangle — the cut removes two segments and adds two identical
ones. So walls, plinth and reveals are all correct from w×l even when nobody transcribed the cut.
Only the **floor** is wrong, and the floor is the one figure the schedule already gives us.

Rejecting threw away correct geometry because one of its uses was wrong. Now:
`{ kind: 'bounding-box', missingAreaM2 }` keeps the geometry, takes the floor from the schedule,
marks the cut uncertain and tells the master which corner to check. Guarded by `MAX_PLAUSIBLE_CUT`
(0.4) — above that the "cut" is too big to be a cut and is almost always a chain misread from the
room next door, which would produce walls for a room that does not exist.

## Testing

`SheetTilerTest`, `SheetMergeTest` (225 lines — the merge rules are where the risk is),
`ProjectImportTextTest`, and the expanded `ProjectImportServiceTest`. The PWA side has 28 tests in
`projectImportMerge.test.ts`.

## Gotchas
- Don't "optimise" the overlap away. Zero overlap means a chain on a seam is lost twice.
- Don't make `SheetMerge` resolve disagreements by picking a winner. Marking uncertain is the
  feature — the whole import review is built on showing the master what to check.
