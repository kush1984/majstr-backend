# Trade-by-trade review, batch 8 — METAL's first material norms, 2026-10-13

**Migration:** V155. **Source:** `C:\Work\prompts\trade-review\09-METAL.md` §3, re-read against the
V154 library. **PWA:** no source change (version bump only).

## Goal

METAL was the last trade with no norm at all. Fabrication is a drawing — how much steel and of which
section is the specific gate, grille or truss, never the position name — so the honest answers are
the finishes, the sheet goods a fence or canopy is clad with, and the bought pieces a position names
one at a time. With V155 every trade has norms.

## Owner rulings (2026-10-13), all as recommended

1. Concrete for a post is a fixed figure per post (fence 55 kg, gate 140 kg), not a depth question:
   THICKNESS is bounded at 150 mm and a post hole is a metre deep.
2. Metal enamel ships at 0,20 kg/m² for two coats and is not rescaled by the wall-paint habit (its
   code does not start with `PAINT_`, the reasoning that keeps ENAMEL_WOOD out, B-49).
3. The sliding-gate hardware kit and the post caps are one per position (as V152's mounting kits).
4. Fabrication, welding by the hour and the welded fence frame stay unnormed.

## What V155 does

| Section | What |
|---|---|
| 1 | 14 dictionary rows: metal primer and enamel, profiled sheet, roofing screw, cellular polycarbonate (12,6 m² sheet), thermo washer, connector profile (6 m), 3D fence panel / post / clamp, dry concrete M300 (25 kg), base-plate anchor, sliding-gate kit, post cap |
| 3 | METAL norms on 11 positions: primer 0,10 kg/m²; enamel 0,20; fence sheet 1,05 + 6 screws; canopy sheet 1,10 + 8 screws; polycarbonate 1,10 + 4 washers + 0,48 m connector; 3D fence 0,4 panel + 0,4 post + 1,2 clamps per metre; concrete 55 / 140 kg per post; 4 anchors per plate; one gate kit; one cap |
| 4 | 7 «consumes nothing» verdicts: galvanizing and powder coating (services), taking steel and an old fence down, cutting, bending, drilling |
| 5 | Self-checks: keys name live positions, no two-trade ambiguity, 18 normed positions |

Coverage: **19 of 56** positions answer (18 + the trade-less cleanup).

Unnormed on purpose: every «Виготовлення…», welding by the hour, the welded fence frame, erecting
steel by the kg, hanging gates / wickets / grilles / canopies / railings, canopy posts (concrete or
anchors), the sliding-gate footing, gate automation (the client's equipment), blasting and degreasing.

## Tests

- `MetalNormsOnLiveDataIntegrationTest` — coverage 19; primer and enamel, no `PAINT_` code; cladding;
  concrete per post on QUANTITY; anchors; fabrication and welding unnormed; 7 verdicts; 14 dictionary
  rows; zero waste.
- `MaterialCalculatorIntegrationTest` — METAL joins `NORMED_TRADES`.

## V156 — the bundles

The report's §2 was written on V145; V148 had already rebuilt all seven METAL bundles in the order it
asked for (foundations → fabrication → cleaning → primer → paint → erection → cladding / hardware).
Of its four new bundles, V156 ships only **«Антресоль / майданчик»** (7 lines: embedded parts →
mezzanine → cleaning → primer → paint → erection → base-plate anchors). The swing gate with a wicket
already shipped in V148; the 3D-mesh fence (4 lines) and the stainless railing (2) fall under V148's
floor of six lines and are left to the master. Test: `MetalMezzanineBundleOnLiveDataIntegrationTest`.
