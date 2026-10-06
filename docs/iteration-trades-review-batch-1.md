# Trade-by-trade review, batch 1 — what buys the wrong quantity today, 2026-10-06

**Status:** built, `./gradlew build` green (Docker up, all ITs ran). Migration **V146**.
Source: `C:\Work\prompts\TRADES-REVIEW.md` — a trade-by-trade read of the default catalog, the
bundles and the material norms across all nine trades, written against a clean DB at V145.

**Scope the owner picked:** §4.1 (*errors that buy the wrong quantity today*) **plus** the
calculator-engine gaps of §1.8. Catalog additions, renames, the trades that still have no norms at
all, and the remaining bases (LENGTH, rebar kg/m³, inverse-to-step, chase cross-section) are later
batches and are deliberately absent.

Two owner rulings shaped the data half:

- **Grout — «гібрид» (§3.1).** Grout stays on the LAYING positions. A grouting step that FOLLOWS
  laying becomes a recorded «consumes nothing» verdict, not a deleted row. «Заміна затірки швів» and
  «Заповнення товстого шва напівсухою сумішшю» keep their own norm, because nothing laid them.
  Wide-joint positions keep theirs too.
- **Primer — «ґрунт один раз», extended (§3.3).** The deep primer belongs to the standalone
  «Грунтування» position (V145's ruling). Product primers — бетоноконтакт, кварцґрунт — stay on
  their own positions, because they are a different product doing a different job. The five tiling
  bundles that primed nothing get a «Ґрунтівка поверхні» step, and PAINTER gets a priming step per
  stage rather than a primer row per work.

---

## The shape this batch has

Almost every item is one of three things:

1. **A figure written for one geometry and applied to every geometry** — 0,4 kg/m² of grout on a
   100×100 and on a 1,2 × 2,4 m slab; 2,1 lm of UD per metre of PERIMETER on a wall, where the
   track is the frame's top and bottom and scales with the AREA.
2. **A quantity that is right for the product next to the one on the row** — 0,2 kg/m² is a
   DISPERSIVE glue's rate, and `WALLPAPER_GLUE` is a powder that makes paste for ~30 m² out of a
   0,3 kg pack. Twenty times too much.
3. **A sequence that reads fine as a list and is wrong as an order** — a system started before it
   held pressure, a «комплекс» rollup priced beside its own parts, five tiling bundles that stick
   tile to an unprimed base.

---

## V146 — eleven sections

| § | What | Rows touched |
|---|---|---|
| 1 | UD on a WALL is `QUANTITY` 0,7 lm/m² (Knauf W623), not `PERIMETER` 2,1 | 2 positions |
| 2 | Mineral wool bought twice — on the WALL frame, and only there | 1 delete |
| 3 | Grout: the hybrid ruling + every format's own geometry + the stairs | 3 verdicts, 18 figures |
| 4 | Tile adhesive: the class (C1→C2) and the notch the work needs | 5 positions |
| 5 | Wallpaper glue 0,2 → 0,009; the dispersive glue gets a material that is one | 2 figures, 4 materials |
| 6 | Primer: the right product, and «once» extended to the running metre | 1 move, 3 deletes |
| 7 | Ten positions two trades ship, re-filed to `trade = NULL` | 10 name keys |
| 8 | Five tiling bundles never primed anything | 5 inserts + 1 swap |
| 9 | A «комплекс» position inside a bundle of its own parts | 2 deletes, 1 rename |
| 10 | A system is started AFTER it has held pressure | 2 swaps |
| 11 | Every bundle line this migration wrote resolves to a catalog position | `RAISE WARNING` |

### §3 — grout is geometry, and the formula is the manufacturer's

V145 made grout `baseline_param`-aware and left two flat bands (0,4 small, 0,15 large). V146 puts
every ordinary format through Mapei Ultracolor's own figure instead:

```
kg/m² = (A + B) / (A × B) × C × D × 1,6
```

so a 100×100 goes UP to 0,5 and a 300×300 is no longer the same number as a 300×600. The three
LINEAR_METER stair rows follow the FLOOR format they are laid with (0,25), and a plank is grouted
along its SHORT side — a 150 mm board's joints do not disappear because the tile is long.

«Під цеглу» was the one row V145 got backwards: it is a 240×71 brick format on a 10 mm masonry
joint, not CE 43's 10×10 tile at 5 mm, and the same formula puts it at 2,9 kg/m² with
`baseline_param = 10`.

### §7 — ten re-filings instead of a catalog addition

A position two trades ship is re-filed to `trade = NULL`, never duplicated (V137's rule). Ten more
name keys needed it. **This is also why V146 INSERTs no `catalog_templates` row at all** — any
migration that does must re-run V118's ranking verbatim, and the cheaper substitute for «BUILDER
has no norm for this» was to let the TILING norm answer for both trades.

---

## §1.8 — the calculator engine

### Per-position waste

`effectiveWaste` moved ahead of the bucket loop and became the per-norm **fallback**. Each amount is
now grown by its OWN norm's `waste_percent` as it arrives (`Bucket#add`), and the line reports what
the bucket came to. The old code took the MAXIMUM allowance in the bucket and applied it to
everything in it — the flat wall's 100 m² of adhesive bought for a loss only the stair nose has.

The reported percent is a blend **only when the bucket genuinely mixes allowances**. While every
position shares one figure — which is every shipped norm today, all at 0 — `blendedWaste` hands that
figure back **untouched**, so the master reads his own number and not a division's rounding of it.
(An early version ran `stripTrailingZeros()` over the blend and would have put `1E+1` in the JSON.)

### The grout exemption at 5 mm

`WIDE_JOINT_MM = 5`. A norm whose `baseline_param` is 5 mm or wider ignores the `TILE_JOINT_MM`
habit entirely — it is not merely cancelled out, the habit is never read. «Мій шов у плитці» is a
2-3 mm answer about ordinary tiling; reading it onto a 10 mm masonry joint says the master lays brick
cladding to his bathroom habit, and every such norm already carries the only joint it can sensibly
have.

### `MAX_PERIMETER_M` is finally applied

V145 gave each question its own bound and wired two of the three. The perimeter — the one answer
asked ONCE for the whole estimate — was bounded in a constant nothing read. Out of range is now
IGNORED exactly as a per-position answer is: the card asks again instead of putting a six-digit coil
of profile on the list. The constant is `public` so the test can name it rather than re-typing 1000.

### `MaterialNormService`

- `fork()` copies `baselineParam`. A fork that dropped it would be a number with no record of what
  it assumed, and the habit would rescale it from the product-wide 2,5 the moment forks start being
  rescaled at all.
- `own()` matches the trade **first and then gives up on it** (review B-107). A fork stranded under
  the old trade used to be invisible here: the save wrote a SECOND fork, the unique index let it in
  because the trade differed, and from then on two owned rows answered for one norm. One stranded
  row that keeps being found is a bad state we can repair; two are a bad state the read path has to
  guess between.

### A false claim in a javadoc

The `PAINT_CODE_PREFIX` block said V138's figures were ONE-coat rates. 0,22 and 0,20 л/м² are
**two-coat** figures, which is why `PAINT_COATS = 3` is one and a half times the base and not three
times it.

---

## Tests

- `MaterialCalculatorServiceTest` — per-position waste (two norms, 5 % and 20 %, one material: 129
  and not the maximum's 144, reported as 7,5 %), the one-shared-allowance case that must stay
  byte-identical, a norm's own allowance overriding the global one, the habit rescaling from the
  norm's own baseline, the 5 mm exemption (asserted as `verify(..., never())` on the pref — the
  habit is not read), and the perimeter bound.
- `MaterialNormServiceTest` — `baselineParam` on the fork, the stranded-fork fallback, the exact
  match still winning over it, and «restore» finding the stranded one.
- `TradeReviewBatchOneOnLiveDataIntegrationTest` — live assertions for all eleven V146 sections off
  the shared container. Every value was cross-checked against the V145 snapshot before being
  written. Includes the hard half of §11's WARNING: every bundle line V146 wrote resolves to a
  shipped catalog position (a miss applies the line at 0 ₴ **silently**, V112).
- `TradeReviewBatchOneForkMigrationOnLiveDataIntegrationTest` — a throwaway DB seeded at V145 with
  seven forks, then migrated to head: four still carrying the shipped figure (they follow), three
  carrying the master's own (they stay), one of which is a material move as well, so it exercises
  B-108 and B-35 at once.
- `NormDataCorrectionsOnLiveDataIntegrationTest` — the V145 assertions V146 moves, plus the
  orphan-position check rewritten (the old one grouped `material_norm` and kept groups with
  `count(*) = 0`, which no group can have — it was green against any data whatsoever).

---

## Not changed / confirmed

- **Короїд does NOT get its quartz primer back.** The primer ruling is about the deep primer; a
  quartz primer under a decorative plaster is a product decision on its own position, and короїд's
  own position is where it belongs.
- **The CEILING frame position keeps its mineral wool.** The plan was to delete the duplicate from
  both frame positions; the snapshot proved the library has no ceiling wool position for the wool to
  move to, so deleting it there would have bought nothing at all. §2 deletes the WALL row only, and
  a self-check asserts the ceiling still has exactly one.
- **«Покрівля двоскатна» loses its rollup without yet gaining «Монтаж кроквяної системи»** — the
  replacement is a catalog addition, which is a later batch.
- **«Дикий камінь» uses 1,35 kg/m²/mm, not the review's 1,3** — matching V137's two existing
  THICKNESS adhesive rows, because a third number for the same bed depth is a disagreement, not a
  refinement.
- **B-107's data half is not fixed forward.** V143's `RAISE EXCEPTION` guarantees no fork was left
  stranded when it applied, so there is no row for V146 to repair. This assumes V143–V145 actually
  applied on prod; the code half (`own()`'s fallback) is what protects the NEXT re-filing.
- **Still open from FIXES-4 §4:** B-111 (`ProjectPhotoService` blob leak on rollback), B-112
  (`MaterialParamService` concurrent-first-save 500) and the `GKL_SHEET` pref bounds.

---

## Gotchas

- **A «consumes nothing» verdict is BOTH nulls together** — `material_id IS NULL` **and**
  `qty_per_unit IS NULL`, or V127's `material_norm_qty_check` refuses the row.
- **`estimate_template_items` has no owner column.** The fork-safe filter is `is_default = TRUE AND
  owner_id IS NULL` on the PARENT template. There is also no unique constraint on `sort_order`, so a
  bulk `sort_order + 1` to open a gap is safe.
- **A figure statement is `WHERE owner_id IS NULL OR qty_per_unit = <old>`; a material MOVE is
  unconditional.** A norm is a (material, coefficient) pair, and stranding half of it is worse than
  moving a figure the master did not ask to move.
- **A deletion inside a bundle leaves a hole in `sort_order`** unless the sequence is renumbered —
  §9 repairs it with `row_number()`, and the test asserts the sequence is exactly `0..n-1`.
