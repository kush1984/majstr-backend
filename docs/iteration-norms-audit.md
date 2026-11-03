# Iteration — norms audit, priority 1 (V186)

**Status:** DONE (2026-11-03) · **Migration:** V186 · **PWA:** 1.69.1

## Why

The owner's audit of all 1 075 library norms (`C:\Work\prompts\norms-audit-summary.md`, kept outside
the public repos) found the core sound and three priority-1 defects that corrupt the shopping list
today. This round fixes those three. Priorities 2–5 (wrong product, packaging, number
inconsistencies, missing companion materials) stay open — see open-questions «Norms audit:
priorities 2–5».

## 1. A piece per piece of work took the master's allowance (code)

`MaterialCalculatorService.Bucket#add`: a material with no package was grown by the master's
allowance and rounded UP to whole units, so one breaker × 1,10 = 1,1 → **2**, twelve → 14, two WC
collars → 3. 80 library norms match (PIECE position, PIECE material, no `package_size`, a whole
`qty_per_unit`, QUANTITY basis, no own `waste_percent`).

Rule (`countedPiece`): exactly those take **no** allowance. Fasteners per m² or per metre, and
anything sold in a pack, keep it. A norm's own `waste_percent` still overrides everything.
Chosen over the audit's option (a) — a NULL-vs-0 waste column — because it needs no migration and
no per-row data decision.

## 2. Two floor layers (V186, library rows only)

Checked against manufacturers' sheets by a research agent (Ceresit CN 83/87/178, Knauf Боден,
Kreisel/Nanofix, Sikafloor-263 SL / -161, Mapefloor I 300 SL, Mapei Primer SN) and approved by the
owner:

| Position | Before | After |
|---|---|---|
| «Наливна підлога — вирівнювальний шар 10–50 мм» | 1,8 kg/m²·mm, 5 mm suggested | 1,8, **20 mm** suggested (flat practice 15–30 mm) |
| «Нанесення епоксидного покриття» — coating | QUANTITY 1,0 kg/m² | **THICKNESS 1,0 kg/m²·mm**, 2 mm suggested |
| — new material `QUARTZ_SAND` «Пісок кварцовий (для епоксидної підлоги)», 25 kg bag | — | **THICKNESS 0,6 kg/m²·mm**, 2 mm |
| «Ґрунтування основи під епоксидну підлогу» | 0,4 kg/m² | unchanged (Sika 0,35–0,55, Mapei 0,3–0,7) |

Resin : quartz ≈ 1 : 0,6, inside Sikafloor-263 SL's 1 : 0,5–1 : 1; 1,6 kg/m²·mm of mix.

**Masters' forks are not touched** (owner's ruling): nothing is renamed or re-keyed, so a fork keeps
answering with his own figure. Preconditions WARN and skip (V176's lesson).

## Not changed

The two thin self-levelling positions keep 5 mm. The public screed calculator does not use these
positions. Catalog rows untouched — no V118 re-ranking.

## Gotchas

* **Local DB only:** the unmerged bot's migration sits in the local Flyway history. It was moved
  V183 → V185 → **V1000** so that no new master migration lands «below» it again; each new master
  migration is applied locally once with `SPRING_FLYWAY_OUT_OF_ORDER=true`. Prod is unaffected.
  At the merge the bot takes the **next free number** (V187 as of V186), and the local row moves
  to it.
