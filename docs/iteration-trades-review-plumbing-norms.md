# Trade-by-trade review, batch 5 — PLUMBING's first norms, and the STEP basis, 2026-10-13

**Migration:** V152. **Source:** `C:\Work\prompts\trade-review\07-PLUMBING.md` §3, re-read against
the V151 library. **PWA:** 1.51.0 — the «Матеріали» screen asks a fourth question (loop spacing).

## Goal

PLUMBING ships 176 WORK positions and had no norm of its own; the inspection hatch it shares with
DRYWALL was its only answer. Plumbing is fixtures and fittings, so the honest norms are: running
material (a metre of work is a metre of pipe), one fitting per position where the fitting is not the
client's appliance, and underfloor heating, whose pipe follows the loop geometry.

## The STEP basis

Underfloor pipe per m² falls as the loop spacing grows (Purmo: 9,5 / 6,5 / 5,0 m/m² at 100 / 150 /
200 mm), and every basis so far multiplied. The review offered three positions «…крок 100 / 150 /
200 мм» or a dividing basis. The owner asked a sharper question — whether such variants should be a
figure on one position rather than several positions — which is exactly what the per-position
questions already are on the calculator screen (THICKNESS, SECTION). So:

- `NormBasis.STEP` — asked per POSITION in millimetres, pre-filled from `default_param`, stored in
  `estimate_material_param` like the others; **amount = quantity × qty_per_unit ÷ mm**, so the
  coefficient is the figure at a 1 mm spacing (pipe 975 = 6,5 × 150, clips 2100 = 14 × 150).
- Bound 500 mm (`MAX_STEP_MM`, `MaterialParamsRequest.steps` `@DecimalMax("500")`); out of range is
  ignored and the card asks again, like every other question.
- A third per-position query param `steps` (never folded into `thicknesses` — one line can carry
  both questions). `StoredMaterialParams.steps`; `MaterialSourceLine.param` carries the spacing and
  the PWA writes it AFTER the coefficient as a division: «20 м² × 975 ÷ крок 150 мм = 130».
- V152 widens both basis CHECKs (`material_norm`, `estimate_material_param`).

The owner's broader idea — enter the figure on the estimate LINE instead of on the calculator screen
— is recorded in open-questions as its own iteration (same storage; price does not follow the figure).

## Owner rulings (2026-10-13), all as recommended

1. Clips and sewer clamps ship with the usual flat's diameter assumed: PP-R Ø20 1,7 / 1,8 per metre
   (ДСТУ-Н Б В.2.5-35), sewer Ø50 2,0 (Ostendorf HT). The master forks if his pipe differs.
2. Underfloor heating is one position with a STEP question, suggested 150 mm.
3. Mounting kits are normed at one per position (WC frame, flush plate, thermostatic head, radiator
   legs); the appliances themselves are not.

## What V152 does

| Section | What |
|---|---|
| 1 | STEP basis: both CHECKs widened, column comment updated |
| 2 | 20 dictionary rows (pipes, insulation 2 m, clips, wall plate, sewer pipe/clamps/tee/compensator/adapter/revision/check valves Ø50/Ø110, kits, UFH pipe/clip/insulation 10 m² roll) |
| 4 | PLUMBING norms on 29 positions |
| 5 | 26 «consumes nothing» verdicts: pressure and flood tests, start-ups, meter sealing, septic commissioning, first heat-up, balancing, every chasing position, niches, septic earthworks, threading, removing and refitting a radiator |
| 6 | Self-checks: keys name live positions, no two-trade ambiguity, STEP suggestions inside the bound, 55 normed positions |

Coverage: **60 of 176** positions answer (55 + the trade-less hatch, protection, debris and cleanup).

Notable choices:
- A riser buys one Ø110 tee and two Ø110 clamps (cast iron → plastic adds two adapters); the pipe is
  not normed — its height is not in the name. The compensator is bought on its own position only,
  since «Заміна стояків» carries both.
- The underfloor position also buys system insulation 1,0 m² and a damper strip on the PERIMETER; a
  FLOORING damper norm in the same estimate shares the one perimeter (`PerimeterDemand::larger`).

Unnormed on purpose: fittings, manifolds, boiler / radiator connection sets, chase filling, the
riser pipe, outdoor sewer pipe, thread sealant and lubricant, radiator brackets (bundled), appliance
hoses/siphons, the appliances, and the demolition positions (DEMOLITION's batch).

## Tests

- `MaterialCalculatorServiceTest` — STEP is asked with its suggestion; it divides (20 × 975 ÷ 150 =
  130, ÷ 100 = 195); a stored spacing answers and rides back; 1500 mm is ignored.
- `MaterialParamsControllerTest` — `steps` 150 saved, 600 → 400.
- `MaterialParamIntegrationTest` — a spacing is stored beside a thickness on the same line.
- `PlumbingNormsOnLiveDataIntegrationTest` — coverage 60; appliances unnormed, kits normed; clip
  figures; UFH STEP norms give 6,5 m and 14 clips at 150; STEP bound; riser vs compensator; 26
  verdicts; dictionary and zero waste.
- `MaterialCalculatorIntegrationTest` — PLUMBING joins `NORMED_TRADES`.
- `RequestContractSnapshotTest` — `MaterialParamsRequest.steps`; the snapshot is copied to the PWA.
- PWA `MaterialCalculatorPage.test.tsx` — the spacing card pre-fills 150, sends `steps`, remembers
  `{ steps }`; the arithmetic line reads «975 ÷ крок 150 мм».
