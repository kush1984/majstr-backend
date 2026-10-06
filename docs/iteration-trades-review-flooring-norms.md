# Trade-by-trade review, batch 2 — FLOORING's first material norms, 2026-10-06

**Status:** built, `./gradlew build` green (Docker up, all ITs ran). Migration **V147**.
Source: `C:\Work\prompts\trade-review\04-FLOORING.md` §3, read against a clean DB at V145.

FLOORING ships **54 WORK positions and, until V147, exactly ZERO norms**. «Матеріали» answered
nothing at all for a floor layer — which is also why the whole set lands in ONE migration: V138
settled that partial coverage is worse than none, because the screen switches ON and offers a buying
list of one line out of forty.

**Scope:** the review's §4 item 1 — norms for every FLOORING position that can have one, plus the
dictionary rows they point at. Deliberately out: §3.3's eighteen new positions and the new
LINEAR_METER damper-tape row, because each needs a `catalog_templates` INSERT and therefore a
verbatim re-run of V118's ranking. That is a batch of its own.

---

## How the 54 positions are accounted for

| | Positions | Rows |
|---|---|---|
| Normed under `trade = 'FLOORING'` | 40 | 58 |
| Normed at `trade = NULL` (BUILDER ships them too) | 5 | 6 |
| «Checked, consumes nothing» — a recorded verdict | 7 | 7 |
| **Deliberately unnormed, and named in a self-check** | **2** | 0 |
| | **54** | **71** |

Plus **36 new `material` rows**; ten shipped codes are reused rather than re-added
(`PRIMER_DEEP`, `SELF_LEVELLING`, `SCREED_CEMENT`, `XPS_BOARD`, `MINERAL_WOOL`, `DOWEL_NAIL`,
`ANCHOR_WEDGE`, `SEALANT_SILICONE`, `SEALANT_ACRYLIC`, `ENAMEL_WOOD`).

### The two that stay unnormed, on purpose

- **«Машинна стяжка самовирівнююча»** — the name describes either a semi-dry screed mixed on site
  from cement, sand and fibre (the dictionary holds none of the three) or a machine-poured anhydrite
  floor at 30-40 mm. Two incompatible products behind one name is a RENAME first and a norm second.
- **«Монтаж та виготовлення ніші під плінтус прихованого монтажу»** — the niche profile and its
  filler belong to a system we do not name.

Both are asserted **by name** in the migration and in the IT, so a third one appearing means a
position fell out of the set by accident rather than by decision.

---

## The three decisions that depart from the review

### 1. Waste stays at 0 — the review asked for 5

The review (§3.1, §4 item 6) asked for `waste_percent = 5` on every covering, because the old bucket
applied the **maximum** allowance in it to everything in it: one diagonal position bought the whole
flat's laminate at the diagonal rate. **V146 fixed that in the engine** — each amount is now grown by
its OWN norm's allowance.

With the bug gone, writing 5 here would do harm instead. A norm's own `waste_percent` **OVERRIDES**
the master's `WASTE_PERCENT` habit, so a shipped 5 would silence the figure he set himself on the one
material where his own cutting habit matters most. Every shipped norm carries 0 for exactly that
reason, and these do too.

What DOES belong in the coefficient is the **layout surplus**, because it is a property of the
pattern and not of his hand: «укладка ламінату проста» is 1,00 and «по діагоналі» is 1,05, «художній
паркет» is 1,10, and his own allowance multiplies on top of all three. The **underlay stays at 1,00
in both** laminate positions — it is butt-jointed and does not care which way the planks run.

### 2. The five shared positions are re-filed to `trade = NULL`, not via `catalog_items`

V118's seed array lists BUILDER before FLOORING, so for a master who has both trades these rows land
in his catalog under BUILDER and a FLOORING-filed norm would never be reached. The review asked for a
V132-style re-filing of his `catalog_items`; V137 established the cheaper answer and V146 used it ten
more times — a trade-less norm answers for whichever trade the line ended up under and touches none of
his own copies.

The five: «Монтаж терасної дошки», «Фарбування терасної дошки», «Армування підлоги сіткою під
стяжку», «Вирівнювання підлоги самовирівнюючим розчином», «Цементно-піщана стяжка понад 20м2».

### 3. Two figures disagree with a shipped one, and two deliberately do not

- **`PRIMER_DEEP` at 0,20 l/m²** on «Грунтовка підлоги підготовчі роботи» and «Грунтовка під стяжку»,
  against the shipped 0,15. A floor drinks more than a puttied wall: Kreisel 375 is 0,15-0,2 on a
  cement-sand base and ≥ 0,3 undiluted on concrete. **Two substrates, not a disagreement** — unlike
  V146's stone-cladding bed, where the substrate was identical and a third number would have been one.
- **`SEALANT_ACRYLIC` at 0,05 l/lm** on the two «Підрізка ламінату … защільнення» positions, against
  PAINTER's shipped 0,025. An 8×6 mm expansion gap holds ~48 ml per metre; 0,025 is a 5×5 bead in a
  crack. V146 §3 settled that one figure for every geometry is the bug and not the fix.
- **`SEALANT_SILICONE` stays at the shipped 0,025** — a threshold joint IS that bead's geometry.
- **The screeds reuse TILING's coefficient AND its suggested depth** — 2,0 kg/m²/mm at 40 mm, 1,8 at
  5 mm. A second suggested depth for the same layer would be a disagreement, not a refinement.

---

## The bases

- **`DAMPER_TAPE` is `PERIMETER`.** The tape runs the room outline, so the M2 position's own area says
  nothing about how much is bought, and a perimeter is asked once for the whole estimate.
- **Four `THICKNESS` norms**, each with a `default_param`: the two self-levelling rows at 5 mm, the two
  cement screeds at 40 mm. «Стяжка з керамзитом» asks about the **keramzit bed** (0,0011 m³/m²/mm —
  1 l per mm plus 10 % for compaction, suggested 50 mm) because that is the layer whose depth actually
  varies; the 40 mm topping above it is a fixed `QUANTITY` row at 80 kg/m².
- Everything else is `QUANTITY`. A chase's cross-section and the inverse-to-step bases the review
  wants are still open — «Заливаня штроби в стяжці» ships 5,0 kg/lm on a stated 50×50 mm assumption.

---

## The dictionary

Packaging follows V127's rule: the smallest size commonly on the shelf, and **absent where we cannot
pick it**, because rounding up to a package the master does not need is the error HE pays for. Every
**covering** is therefore without one — a laminate pack is 1,5 to 2,6 m² depending on the product, so
the answer rounds to whole square metres and says nothing about packs. (A «pack area» habit beside
`GKL_SHEET` is the eventual fix; it is not this migration.)

Proven packages only: `PARQUET_ADHESIVE` 13 kg, `ADHESIVE_FLOOR_DISP` 3 kg, `MOUNTING_ADHESIVE`
0,31 l cartridge, `PARQUET_LACQUER` 4,95 l, `DECK_OIL` 0,7 l, `EPOXY_COATING` 20 kg and `PU_TOPCOAT`
7,5 kg as «комплект A+B», the four skirting planks at 2,5 lm, `SKIRTING_FOAM` at 2, `THRESHOLD_PROFILE`
at 0,9.

**No code carries the `PAINT_` prefix, deliberately.** That prefix is what the `PAINT_COVERAGE` habit
rescales, and the habit is the master's answer about HIS WALL PAINT. A parquet lacquer, a deck oil and
a 2K polyurethane have coverages of their own, read off their data sheets — V145 settled the same
point for `ENAMEL_WOOD` and `VARNISH_CLEAR`.

Five codes the review listed are **not** added: `ENGINEERED_BOARD`, `PLYWOOD`, `COLD_WELD`,
`PARQUET_OIL`, `DRY_FILL`. Each belongs to a §3.3 position that does not exist yet, and a dictionary
row nothing points at is a row the shopping list can never offer.

---

## Sources behind the figures

Sika SikaBond-54 Parquet + Wakol MS 230 (parquet adhesive 1,0 kg/m²), SikaBond-130 Design Floor
(dispersion 0,30), Sikafloor MultiDur ES-14 N (epoxy primer 0,40), Sikafloor-263 SL (1,00),
Sikafloor-304 W (PU 0,13), Bona Traffic HD (lacquer 0,11/coat), Bona Mix & Fill Plus (0,10), Tarkett's
Ukrainian linoleum instruction (+8 cm each way, glued over the whole area), EGGER JUST clic! (PE film
over the whole floor turned up the wall → 1,15), Orac Decor + Arbiton (one 310 ml cartridge per 7-8 m;
fixings every 40-50 cm → 2,5/lm), Cezar 60 (a 2,5 m plank → 1,05 for corner cuts), Kreisel 375 (floor
primer band), and NORMS-SUMMARY §2.7/§2.8 for the screeds.

The **one figure with no source** is «Художній паркет» at 1,10 — an artistic layout wastes like a
herringbone, and the migration says so. A number the master can correct beats a blank he cannot.

---

## Self-checks (V147 §6)

All six `RAISE EXCEPTION`, and all six are about **V147's own work** (review B-50) — the name keys
were typed here against a catalog snapshot, so a key resolving to nothing is a typo of mine and not a
rename an admin made between two deploys.

1. every FLOORING key names a live FLOORING WORK position of the same unit;
2. every trade-less key names a position **somewhere** (the section-4 move is what could strand one);
3. no `THICKNESS` norm is missing its `default_param`;
4. no `(name_key, unit)` is normed by two trades — **including a trade-less row sitting beside a
   traded one**, which `count(DISTINCT trade)` alone would not see;
5. 47 distinct normed FLOORING positions, so a half-applied `VALUES` list cannot pass quietly;
6. the unnormed set is **exactly** the two expected names — asked as a set difference, not as a
   sorted string, so the answer does not depend on how the database collates Ukrainian.

---

## Tests

`FlooringNormsOnLiveDataIntegrationTest` — 17 assertions off the shared container, against the schema
at head. The load-bearing one is coverage: every FLOORING position has an answer except the two named.
Also pinned: the catalog is still 54 positions (V147 inserts none), the seven verdicts carry BOTH
nulls, **no flooring norm carries a non-zero waste**, the diagonal/straight laminate pair with its
shared 1,00 underlay, the five shared positions filed at NULL **and** the library fact that makes that
the right filing, no position normed by two trades, the two deliberate disagreements beside the
shipped figures they disagree with, the screed coefficients and depths, each new code existing exactly
once, each reused code reused rather than re-added, no `PAINT_` prefix, packaging either known or
absent, and V133's `package_unit = unit` rule.

---

## Not changed / confirmed

- **No `catalog_templates` row is inserted.** §3.3's A1-A18 and the new LINEAR_METER damper-tape row
  (N38) wait for the batch that re-runs V118's ranking; the existing M2 damper-tape position keeps the
  PERIMETER norm that is the only sensible reading of it.
- **No master's copy is touched.** No `catalog_items` re-filing, no fork rewrite — V147 only inserts,
  so B-108 and B-35 have nothing to reach here.
- **«Монтаж утеплювача» is read as the between-the-joists job** and takes mineral wool at the 1,05 three
  other positions already carry; the review's §1b rename would make that explicit.
- **The hidden skirting ships as a complex AND its two parts**, and each is normed for what its own
  name says. An estimate carrying all three double-buys, which is a CATALOG problem (§1b) and not
  something a coefficient can repair.
- **`WOOD_SCREW` at 15/m² and `DECK_CLIP` at 18/m² assume a 0,5 m and 0,4 m joist step** — stated in
  the migration, because the master forks the row if he works to a different one.

---

## Gotchas

- **A norm's own `waste_percent` beats the master's habit.** That is the whole reason this batch ships
  zeros — a shipped allowance is not a floor under his figure, it replaces it.
- **`material_package_name_check` pairs the NAME with the SIZE** (V127) and V133 pins
  `package_unit = unit`, so «we know the package» is three columns that stand or fall together.
- **`ux_material_name_spec` is `UNIQUE NULLS NOT DISTINCT (name, spec)`** — two new materials with no
  spec may never share a name, and neither may collide with any of the 61 already shipped.
- **`string_agg` has no order unless you give it one**, and a Ukrainian sort order is not something a
  migration should bet on — §6f asks its question as an `EXCEPT` instead.
