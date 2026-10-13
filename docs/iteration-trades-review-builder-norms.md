# Trade-by-trade review, batch 4 — BUILDER's first material norms, 2026-10-13

**Migration:** V151. **Source:** `C:\Work\prompts\trade-review\05-BUILDER.md` §3, re-read against
the V149 library. **PWA:** no change — the calculator screen already renders any trade's norms.

## Goal

BUILDER ships 229 WORK positions and had no norm of its own: a builder's «Матеріали» answered only
the few positions it shares with PAINTER, TILING or FLOORING. The report proposed a first set for
masonry, concrete, screed, ETICS facade, waterproofing, roofing and paving.

## What changed since the report

The report was written against V145. V146 §7 had already filed the seven shared PAINTER/TILING norms
trade-less (facade mesh, quartz primer, короїд, facade paint, scaffolding, dry waterproofing); V147
did the five shared screed/deck ones; V146 §9 and V148 removed every «комплекс» from the bundles.
V148 also put three FLOORING-normed positions into BUILDER's screed bundle, where a FLOORING norm is
never reached — V151 re-files them.

## Owner rulings (2026-10-13), all as recommended

1. Masonry mortar is cement + sand (M100, РЕКН-6 6-47-18), not a bagged mix.
2. Slag block ships: 63 blocks per m³ is exact geometry, the mortar is an estimate.
3. «Кладка стіни з керамоблока 38х60х20» stays unnormed until the name says which block.
4. «Армування кладки (сіткою, арматурою)» [м.п.] and «…сіткою арматурою» [м²] stay unnormed — two
   materials in one name; rebar waits for a «kg per m³» parameter.
5. «комплекс» rollups stay unnormed — beside their own parts they would buy everything twice.

The owner also confirmed the preview's reading of a norm: nothing in the catalog or the bundles
changes; only the calculator starts answering.

## What V151 does

| Section | What | Rows |
|---|---|---|
| 1 | Dictionary: AAC block + glue, common/facing brick, ceramic stone 2НФ, slag block, wall tie, cement, sand, crushed stone, ready-mix, ETICS adhesive, facade EPS/wool, steel-pin dowel, corner bead, starter profile, bitumen mastic/primer/roll, roof membrane, batten, OSB, underlay, shingles, PVC membrane, drip edge, paving | 28 materials; `MESH_FIBERGLASS` gains its 55 m² roll |
| 3 | BUILDER norms | 63 positions |
| 4 | Positions PAINTER/TILING ship too, at `trade = NULL` (plus the ETICS base coat on the facade mesh, which nobody bought before) | 10 positions |
| 5 | «Consumes nothing» — the masonry mortar step (the masonry rows buy it) and earthworks | 13 |
| 6 | Three FLOORING screed norms re-filed to NULL, forks included unless a trade-less twin exists (B-107) | 3 |
| 7 | Self-checks | — |

Coverage: **106 of 229** BUILDER positions answer. The other 123 are piece goods sized per job,
roof coverings bought by the slope layout, the timber frame, formwork, rebar, rollups, fences,
gates and stairs, PU/polyurea, and paving mortar/kerbs (ДСТУ Б Д.2.2-27 not read yet).

### Deliberate differences from the report

- **Sand cushion suggests 150 mm, not 200** — THICKNESS is bounded at 150 mm per question (review
  round 2 §2), so a 200 suggestion is refused the moment it is pre-filled and the position asks
  forever. Self-check 7d and an IT now guard every shipped suggestion against its bound.
- **Clinker cladding takes TILING's «під цеглу» figures** (C2 6,0, grout 2,9 at a 10 mm baseline) —
  V146 had already corrected the report's 5,2.
- **The corner bead is bought once**, on «Монтаж кутника з сіткою…», not also on «Армування укосів»,
  because the facade bundle carries both.
- **The levelling course under the first AAC row** is a 2 cm cement bed by the wall width (SECTION,
  0,3 m) — a calculation the report left open.
- **Path mesh reuses `MESH_SCREED`** instead of a new road-mesh code: it is the same 100×100 sheet.
- **Profiled membrane, rebar, masonry mesh strip and warm mortar** were left out of the dictionary:
  no position they would answer survived the rulings.

## Tests

- `BuilderNormsOnLiveDataIntegrationTest` — coverage 106; the ruled-out positions have no norm; the
  13 verdicts; brick/AAC/facade figures; the corner bead once; clinker grout baseline; every
  suggestion within its bound (THICKNESS ≤ 150, SECTION ≤ 5); no shipped waste; shared positions
  trade-less and really shared; the three re-filed screed norms; dictionary and packages.
- `MaterialCalculatorIntegrationTest` — BUILDER joins `NORMED_TRADES`: every shipped BUILDER
  position in one estimate buys something and the coverage names BUILDER.

## Not changed / next

- Catalog, bundles, prices, masters' copies and their forks — untouched (only the trade of a fork
  in section 6 moves with its shipped row).
- Next for BUILDER: a «kg per m³» parameter for rebar; paving mortar and kerbs from ДСТУ-27; the
  renames the rulings depend on (керамоблок 380, masonry reinforcement split into mesh and rebar,
  армопояс in m³); duplicate positions (the norms ride both spellings until then).
