# Three more per-position questions: LENGTH, REINFORCEMENT, CHASE_SECTION (V168), 2026-10-13

**Migration:** V168. **Source:** `TRADES-REVIEW.md` §1.8 and the open-questions item «The calculator
bases the review asked for and batch 1 did not add». **PWA:** 1.52.0.

## What and why

All three MULTIPLY by a figure the position does not carry, asked per POSITION, stored in
`estimate_material_param` (V142), bounded per question:

| Basis | Unit | Bound | Suggested | Where |
|---|---|---|---|---|
| LENGTH | m | 100 | 3,0 (a storey + overlap) | the sewer risers (current and V167-retired names) buy Ø110 pipe, the water riser buys PIPE_WATER |
| REINFORCEMENT | kg/m³ | 300 | **none** — the design's number | seven reinforced-concrete M3 positions buy REBAR |
| CHASE_SECTION | cm² | 200 | 4 (20×20 mm) | the electrical chase filling: 0,4 kg/m became 0,1 kg per cm² per metre |

«Area from mass» was dropped: METAL bundles carry primer and paint as their own m² positions, so a
norm on the kg position would buy them twice; the 127/t · 255/t rule is in their descriptions (V166).

## Backend

- `NormBasis` + three constants; V168 widens both basis CHECKs.
- `MaterialParamsRequest` gains `lengths` / `reinforcements` / `chaseSections` with their bounds;
  `StoredMaterialParams` the three maps; `MaterialParamService` stores and reads them.
- `MaterialCalculatorController` takes three more query params. They stay separate (one line can be
  asked two questions); `PerPositionAnswers` only GROUPS the six on the way into the service, which
  now keeps one `EnumMap<NormBasis, Map<UUID, BigDecimal>>` instead of a switch per basis.
- Contract snapshot refreshed and copied to the PWA.

## PWA

The per-position questions moved from three copies of state, seeding, suggestion and card code to
one `PER_POSITION` table (key, basis, prefill). The three V168 cards come from it; the old three keep
their DOM ids and behaviour (a SECTION suggestion still shows as a hint, not a pre-fill; a rebar
ratio has none). Card labels come from a `switch` of STATIC `t()` keys so `i18nKeys.test.ts` still
sees them. The arithmetic line names each new factor («× довжина 3 м», «× армування 120 кг/м³»,
«× переріз 4 см²»).

## Tests

- `MaterialCalculatorServiceTest` — LENGTH multiplies and is suggested; REINFORCEMENT asked with no
  suggestion, a stored ratio answers, 1200 is ignored; CHASE_SECTION 4 vs 16 cm².
- `MaterialParamsControllerTest` — each new bound; `MaterialParamIntegrationTest` — stored together.
- `LengthReinforcementChaseNormsOnLiveDataIntegrationTest` — the shipped norms.
- PWA `MaterialCalculatorPage.test.tsx` — the rebar card opens empty and sends `reinforcements`;
  the chase factor on the arithmetic line.
