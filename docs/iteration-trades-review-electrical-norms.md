# Trade-by-trade review, batch 6 — ELECTRICAL's first material norms, 2026-10-13

**Migration:** V153. **Source:** `C:\Work\prompts\trade-review\06-ELECTRICAL.md` §3, re-read against
the V152 library. **PWA:** no source change (version bump only).

## Goal

ELECTRICAL had no norm and the dictionary held no electrical material. The honest answers are per
unit of WORK — a metre of laying is a metre of cable and conduit, a box is a box, a breaker a
breaker. Cable per POINT stays impossible (no Ukrainian norm quantifies the bend/drop allowance).

## Owner rulings (2026-10-13), all as recommended

1. Cable at 1,0 m per metre of laying, as a generic «Кабель мідний» / «Кабель силовий»; the
   cross-section is the master's at the merchant.
2. «Заробка штроб» at 0,4 kg/m of gypsum, the usual 20×20 chase with a Ø16 conduit.
3. Modular devices at one per position.

The electrical measurement calculator (V60/V61) is UI-disabled, so the cable norm is the only cable
source today; re-enabling it must reconcile the two (open-questions, electrical measurements item).

## What V153 does

| Section | What |
|---|---|
| 1 | 39 dictionary rows: building gypsum (5 kg), flush box, junction boxes (flush / surface), copper / power / UTP / coax cable, conduit Ø16 / Ø20, metal hose, HDPE cable pipe, trunking (2 m), cable lug, five panel enclosures (one per size), 20 modular devices |
| 3 | ELECTRICAL norms on 48 positions |
| 4 | 41 «consumes nothing» verdicts: all chasing and drilling, box bores, insulation / earth / loop tests, continuity checks, layout, set-up of cloud video / Wi-Fi / smart hub, the panel schedule, removing a fitting |
| 5 | Self-checks: keys name live positions, no two-trade ambiguity, 89 normed positions |

Coverage: **92 of 177** positions answer (89 + the trade-less organizational rows).

Choices worth knowing:
- One material per panel size, so two different enclosures never merge into «×2» on a list.
- Both spellings of one device (two RCD names each) buy the same material.
- An unqualified «реле напруги» is the single-phase one.
- «Установка модульного імпульсного реле, таймера або фотореле» is unnormed: three devices in one
  name (the rule that kept BUILDER's mixed masonry-reinforcement rows unnormed).

Unnormed on purpose: socket / switch mechanisms and lights (the client's choice), equipment chosen
with the client (stabiliser, ATS, UPS, inverter, generator, EV charger, boiler, meters, intercoms,
cameras, routers), heating mats and film (fixed-area kits), mat adhesive (TILING covers the bed),
wire connectors, surface clips and saddles (positions do not say surface vs chase), comb busbars and
DIN rail, panel-niche gypsum, retro wire, soldering, and the wiring demolition shared with
DEMOLITION.

## Tests

- `ElectricalNormsOnLiveDataIntegrationTest` — coverage 92; cable and conduit per metre; box and
  chase gypsum; devices one per position and two spellings agree; five panel materials; the client's
  and the circuit's items unnormed; 41 verdicts; 39 dictionary rows; zero waste.
- `MaterialCalculatorIntegrationTest` — ELECTRICAL joins `NORMED_TRADES`.
