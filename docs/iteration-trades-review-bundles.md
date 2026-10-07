# Trade-by-trade review, batch 3 — a default bundle is a JOB, 2026-10-07

**Status:** built, `./gradlew build` green (Docker up, all ITs ran). Migration **V148**
(`V148__bundles_as_job_sequences.sql`). PWA: version bump only.
Source: `C:\Work\prompts\TRADES-REVIEW.md` §1.3-§1.5 and §4 item 4, the nine per-trade reports'
«Шаблони» sections, and a second pass over every default bundle in all nine trades against the V145
snapshot plus V146's bundle edits.

## Goal — the owner's words

«треба якось так зробити, щоб шаблони допомагали майстрови вести ремонт, щоб все йшло одне за
одним» and «не роби маленьких шаблонів — таких по 2-3 позиції, нам такого не треба». The catalog
dumps («великі») are split into a few real sequences — «трохи розбити», not shredded. Positions a
sequence needs are added («що треба то додавай»).

## How it was built

1. Three read-only analysis agents, one per three trades, each producing a «було → стало» table, the
   proposed sequences with exact names, and the catalog positions those need. Every line not marked
   NEW was validated by script against the snapshot catalog (same trade, same unit).
2. The owner approved the shape, then six rulings (below), all as recommended.
3. `gen/parse.py` → `gen/transform.py` (applies the rulings, maps renames, prices) →
   `gen/gen_sql.py` turned the analyses into V148's data block; the SQL around it is hand-written.
   The generator lived in the session scratchpad and is not part of the repo — V148 is the artifact.

## The owner's six rulings (2026-10-07)

1. A DRYWALL construction bundle ends at the boards; the joints come from the Q1-Q4 finish-level
   bundle applied with it — which removes the double putty «Стеля» + Q3 used to price.
2. One wording for protection and debris in every trade: «Захист підлоги картоном», «Фасування
   сміття в мішки», «Винесення та вивезення будівельного сміття», «Прибирання приміщення після
   робіт». TILING's «Укриття плівкою…» and PAINTER's «Збирання сміття в мішки після демонтажу» leave
   the bundles and stay in the catalog.
3. No bundle carries «Витратні матеріали 13 %», a conditional «%» or «Гарантійний повторний виїзд».
4. Apartment water is distributed through a COLLECTOR (new position).
5. One «Резервне живлення: генератор, АВР, інвертор» bundle; chases cut in BRICK.
6. BUILDER's foundation stays a rollup; piles / ФБС / monolithic / mineral-wool facade get no
   bundle this round.

## What V148 does

| | Count |
|---|---|
| Default bundles before → after (nine trades) | 115 → 102 |
| Rewritten in place, same name | 54 |
| Renamed in place (id kept) | 30 |
| Born | 18 |
| Deleted | 31 |
| Bundle lines | 1254 |
| New catalog positions | 141 |
| New `material` rows | 6 |

Per trade: DRYWALL 7 → 9, TILING 12 → 12, PAINTER 3 → 10, FLOORING 16 → 12, BUILDER 25 → 18,
DEMOLITION 10 → 9, ELECTRICAL 18 → 13, PLUMBING 17 → 12, METAL 7 → 7.

**Deleted** (31): the five dumps («ЕЛЕКТРИКА» 99, «САНТЕХНІКА» 104, «ПІДЛОГА» 25, «КЛАДКА» 8,
«УСІ ПЛИТОЧНІ РОБОТИ» 167), BUILDER's other upper-case menus («ПОКРІВЕЛЬНІ РОБОТИ», «КЛАДОЧНІ
РОБОТИ», «БЛАГОУСТРІЙ ТЕРИТОРІЇ», «ФУНДАМЕНТ», «ГІДРОІЗОЛЯЦІЯ», «СХОДИ», «ЗВАРЮВАЛЬНІ РОБОТИ»,
«МОНТАЖНІ РОБОТИ»), and the fragments folded into fuller sequences.

**Renamed in place** rather than deleted and recreated, so `template_default_override.template_id`
— a master's hide or fork — follows the bundle he already acted on (e.g. «Котельня» → «Котельня під
ключ», «Звукоізоляція та утеплення» → «Звукоізоляція стін і стелі», «Ламінат укладання» → «Підлога в
кімнаті під ключ (ламінат)»).

### The bundle shape every sequence follows

- Protection first (indoor jobs), then the demolition the job needs, rough work, finishing, and
  the same debris tail: bags → carry-out → (load / container in DEMOLITION) → cleanup.
- ≥ 6 lines; the Q1-Q4 finish levels are the one deliberate exception (Q1 = 4) because they are
  applied ON TOP of a construction bundle.
- No mutually exclusive alternatives (three trays, «до 50 м²» + «від 50 м²», five panel sizes);
  the typical one stays, the master adds a variant himself.
- A material is consumed in ONE place — no rollup beside its parts.

### How the lines are written

Lines of a kept bundle are matched by `lower(trim(name))` + unit and keep their ids (an offline
replay naming a default's item id still lands, V143); unmatched lines are deleted, duplicates
collapsed, `sort_order` rewritten whole, missing lines inserted. Every statement filters
`owner_id IS NULL AND is_default` — forks are untouched.

A deleted default is safe for everyone: a fork survives as an owned template (the override row
cascades away), a hide simply has nothing left to hide, and no estimate references a template.

### Catalog positions (141)

Every line a sequence needs that the trade did not ship. Where another trade already ships the same
job, the row is a **verbatim copy** — name, unit, price, description — so a two-trade master owns one
row, and the trade-less norms on those names apply at once. Prices are the reports' ranges (midpoint)
or the sibling the report named; zero is allowed only in TILING/PAINTER
(`SeedCatalogInvariantsIntegrationTest.thePriceCheckStillForbidsZero`), so the copies of the 0 ₴
cleanup / debris / door-protection rows take 40 ₴/м², 600 ₴/м³ and 200 ₴/шт elsewhere.

Categories: protection → «Підготовка та захист» (BUILDER: «Підготовка»); debris and cleanup →
«Організаційні послуги» (DEMOLITION «Сміття»; DRYWALL «Підготовка та захист», because DRYWALL's
category set is pinned by `CatalogOrderOnLiveDataIntegrationTest`). ELECTRICAL gets three new
categories the report asked for: «Вимірювання», «Підключення техніки», «Розумний дім».

Fan-out: masters with the trade get the rows (same dedup key as the unique index; a name arriving
under two of his trades lands once, under the trade V118 ranks first), an undismissed COUNT notice
is topped up or one is queued, `last_synced_catalog_version` advances. The catalog version is
`MAX(added_in_version) + 1` (the admin editor stamps versions too). V118's ranking is re-run
verbatim.

### Norms that ride along

- **Ceiling wool** (completes V146 §2): «Звукоізоляція стелі мінеральною ватою» is the position
  V146 had nowhere to move the wool to. It gets the wall wool's norm; the ceiling frame position
  loses its own MINERAL_WOOL row.
- **Reveal primer once** (V145's rule): «Обезпилення та грунтування укосів перед фарбуванням» now
  stands before every «Фарбування укосів» in a bundle, and the painting and both putty-on-reveal
  positions lose their PRIMER_DEEP row. The analysis only asked for the norm half; three painter
  bundles painted a reveal with no priming step, so the step went in too.
- **FLOORING stays fully answered** (V147's invariant): the 14 new FLOORING positions each get a
  norm or a «consumes nothing» verdict — engineered board + parquet adhesive, PE film, parquet
  filler and primer (V147's own figures), joist antiseptic (розрахунок 0,2 l/m²), cold-weld glue
  (0,0025 l/lm), crack resin (0,1 kg/lm), geotextile 1,10 (trade-less, BUILDER ships it too).
  Verdicts: skirting and linoleum removal (trade-less, DEMOLITION ships the names), door-frame
  trimming.
- **Debris bags — the owner's variant A**: «Фасування сміття в мішки» buys 1 DEBRIS_BAG per line
  unit, trade-less, so every trade's bag line answers.

Only shipped norms (`owner_id IS NULL`) are touched (B-108).

### Self-checks (own work only, B-50)

Every line of every written bundle resolves to a catalog position of its own trade; every bundle
holds exactly its declared sequence in order and nothing more; every declared bundle exists; every
retired one is gone; nothing written is under six lines (Q-levels excepted); the ceiling frame buys
no wool; V118's rank invariants. A missing wall-wool norm is a WARNING (not this migration's data).

## Tests

- New `BundlesAsJobSequencesOnLiveDataIntegrationTest` — migrate to V147, plant four masters, finish
  migrating: dumps gone; every default line resolves; no fragment; no retired wording or `PERCENT`
  line; protection/cleanup order; the collector; the rafters; DRYWALL construction leaves joints to
  the Q-level; a fork of a deleted dump survives with all 104 lines; a renamed bundle stays hidden
  for its forker; one name under three trades lands once; a self-priced row is neither overwritten
  nor doubled; one notice + synced version per master; the ceiling wool; reveal primer; bag norm.
- `TradeReviewBatchOneOnLiveDataIntegrationTest` — renamed bundles; the ceiling now has its wool
  position; the chase-before-start rule moved from the retired dump to the apartment bundle.
- `FlooringNormsOnLiveDataIntegrationTest` — 54 + 14 positions, still all answered but the two; an
  eighth verdict.
- Six history tests (`CatalogCleanupOnLegacyData`, `DrywallCatalogRebuild`, `DrywallPricesAndGaps`,
  `DrywallQualityLevels`, `PainterCatalogRebuild`, `TilingCatalogRebuild`) now upgrade to **V147**,
  not the head: they pin THEIR history's outcome (catalog sizes, versions, notices, bundle shapes),
  which V148 legitimately rewrote everywhere. V148 on live data is pinned by the new test above.

## Not changed / confirmed

- No catalog position renamed or deleted; no master's own row, fork or estimate touched.
- No Java change. The PWA has no hard-coded default bundle name (one historical comment in
  `src/api/estimates.ts` mentions the retired tiling dump as an example; left as is).

## Gotchas

- **A later rename must rewrite the bundle lines in the same migration** — a bundle line resolves
  its price by NAME, so renaming a catalog position without its bundle lines drops them to 0 ₴.
  Several lines here ride names the reports want renamed (plumbing chases, «Установка кранів»,
  «Установка змішувача прихованого типу для душа (біде)»).
- `EstimateTemplateService` drops a repeated name inside one bundle on apply, so a second pass of
  the same work (a second dust-off, a second priming) is entered as QUANTITY, never as a second line.
- History ITs that migrate «to the head» are re-pinned by every migration that legitimately changes
  what they count; capping them at the version they describe is what keeps them honest.
