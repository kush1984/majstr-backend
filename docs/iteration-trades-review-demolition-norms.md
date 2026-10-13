# Trade-by-trade review, batch 7 — DEMOLITION's material norms, 2026-10-13

**Migration:** V154. **Source:** `C:\Work\prompts\trade-review\08-DEMOLITION.md` §3, re-read against
the V153 library. **PWA:** no source change (version bump only).

## Goal

Demolition consumes almost nothing; it produces debris, and the bags for it are already answered by
«Фасування сміття в мішки» (V148, one bag per bag billed). So this batch is mostly recorded verdicts,
plus the dust curtain and the entrance-door protection.

## Owner rulings (2026-10-13), all as recommended

1. Bags stay on «Фасування» alone (the review's variant A). Bags per m² on the demolition positions
   (variant B) would count them twice, and every figure there assumes a thickness and a substrate no
   name carries. The report's §3.2 table (bags per m²) is a candidate hint for that position's
   description — a catalog change, not a norm.
2. The dust curtain takes 4 m² of film and 7 m of tape (a 0,9×2,1 doorway with a 30 cm overlap on
   three sides).
3. Fixture removals and openings stay unnormed.

## What V154 does

| Section | What |
|---|---|
| 1 | «Пилозахисна завіса з плівки на дверний проріз»: PE_FILM 4 m² + TAPE_MASKING 7 m |
| 2 | «Захист вхідних дверей картоном» re-filed from PAINTER to `trade = NULL` (forks too, B-35 / B-107): DEMOLITION ships it as well |
| 3 | 35 «consumes nothing» verdicts — 31 under DEMOLITION, 4 trade-less because another trade ships them (old wiring, old pipes, whitewash, wallpaper) |
| 4 | Self-checks: keys name live positions, no two-trade ambiguity, nothing left under PAINTER, 32 DEMOLITION positions |

Coverage: **44 of 55** positions answer. No dictionary row is added. The two trade-less verdicts on
positions other trades ship lift PLUMBING to 61 of 176 (old pipes) and ELECTRICAL to 93 of 177 (old
wiring); their live-data tests carry the new figures.

Unnormed on purpose: removing a WC, washbasin, bath, shower, mixer, radiator, towel rail or boiler
(the pipe-end caps depend on the pipes, as V152's fittings), and cutting or widening an opening (the
lintel's size is not in the name).

## Tests

- `DemolitionNormsOnLiveDataIntegrationTest` — coverage 44; the curtain; the door protection is
  trade-less; bags only on «Фасування»; fixtures and openings unnormed; 31 + 4 verdicts; zero waste.
- `MaterialCalculatorIntegrationTest` — DEMOLITION joins `NORMED_TRADES`.
- `PlumbingNormsOnLiveDataIntegrationTest` 60 → 61, `ElectricalNormsOnLiveDataIntegrationTest` 92 → 93.
