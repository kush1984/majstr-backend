# Iteration — calculator review, step 1 (V227)

**Status:** DONE (2026-11-27) · **Migration:** V227 · **PWA:** 1.95.0

## Why

The owner asked for a full review of the material calculators («це гроші майстрів/клієнтів»). The
review read the engine (`MaterialCalculatorService`), the four public calculators, the tile layout,
and all 1 076 library norms. The engine held up; a handful of points bought the wrong amount. This
step fixes the two that were rules, not figures. The rest stay queued in open-questions
«Calculator review 2026-11: what is queued».

## 1. A norm may say «no allowance» (V227)

`material_norm.waste_percent` is now NULLABLE: **NULL = the master's allowance** (every old 0 became
NULL — library rows and masters' forks alike, so no figure moved), and **0 = this material takes
none**. Before, 0 meant «his», so no norm could say «nothing is lost here».

`Bucket.countedPiece` (no allowance for a whole number of pieces per piece of work, V186) now treats
SET and POINT like PIECE: one «монтаж фурнітури відкатних воріт» bought two gate kits, eight
«виводи по точках» nine wall elbows.

## 2. Ready-mix concrete: +1,5 %, not +11,65 %

The 16 `CONCRETE_READY` library norms carried ДСТУ Б Д.2.2-6:2016's loss in the coefficient (1,015)
AND the master's 10 %. Now the coefficient is the placed volume (1 m³/m³, 0,1 per м.п. of a 20×50
plinth, 0,001 per m² per mm) and the norm's own allowance is 1,5 % — visible on the screen.

## 3. A 3D fence counts from its SIDES (NormBasis.SIDES)

0,4 panel + 0,4 post a metre with 10 % bought 3 panels for 5 m and 44 posts for 100 m. The owner:
«помилка в безпечний бік — це все одно помилка». A panel (2,5 m) does not bend round a corner and a
gate breaks the run, so the m.p. of the line cannot say either.

- New question **«Сторони паркану»** per position: «25 + 15 + 25; 10» (`;` = a gate). Parsed by
  `FenceSides` (sides by «+», space or «, »; decimal comma kept; ≤ 50 sides, each ≤ 1000 m).
- Panels = Σ ⌈side ÷ 2,5⌉; posts = panels + one per run. Neither takes an allowance. Clamps stay
  per metre.
- Schema: `material_norm.qty_per_run` (SIDES only, CHECK), module = `baseline_param` 2,5;
  `estimate_material_param.text_value` (the answer is text; `value` NULL exactly for SIDES, two
  CHECKs); SIDES added to both basis CHECKs.
- Stored canonical («25 + 15 + 25; 10») and shown back that way; unreadable text = 400
  `MATERIAL_PARAM_INVALID` with the format in the message. Not on the query string — the store only.
- A master's own forked panel/post norm keeps counting by the metre, as he set it.

PWA: `FenceSidesAsk` card on the materials screen; the arithmetic row reads «сторони разом 75 м,
панель 2,5 м = 32».

## 4. PWA aside: the sum in an estimate row

A row with a comment (the (i)) was narrower than its neighbours, so its sum sat left and wrapped
onto its own line. In the inline-editing row the (i) now sits on the NAME line beside «⋯», so every
figures line is the same width. The read-only (signed) row is unchanged — owner: leave as is.

## Tests

`FenceSidesTest`, `NormOwnAllowanceOnLiveDataIntegrationTest` (V227 on a seeded DB with a master's
fork), `MaterialCalculatorServiceTest` (set/point, own 0 vs NULL, fence sides), `MaterialParamIntegrationTest`
(sides stored/refused/cleared), Builder/Metal norms tests; PWA `MaterialCalculatorPage.test.tsx`.
