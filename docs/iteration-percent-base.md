# Iteration — «%» becomes a percentage OF something

PWA `1.3.0 → 1.4.0`. Backend: **V88**.

## What was wrong

A `PERCENT` line multiplied like any other unit: `10 × 500 = 5 000 ₴`, printed as
**«10 % · 500 ₴/%»** — five hundred hryvnia for one percent. Nobody can quote that to a client.

A percentage is a share **of something**, and that something is one of three: a sum typed by hand,
another line, or the estimate's own subtotal.

## The decisions worth keeping

**A percent of a percent is forbidden.** That single restriction is the entire cycle protection: the
base picker offers ordinary lines only, so no chain can close on itself. No graph to walk, no cycle
detection to write and get wrong — just a filter on a dropdown.

**Three steps, one pass, and the order is the design.** Ordinary lines → percentages of a line or of
a hand-typed sum → percentages of the TOTAL. Several TOTAL lines are all measured against the **same**
base: compounding them would make the answer depend on which was typed first, which nobody could
explain to a client.

**The line amount is stored (`estimate_items.line_total`).** Six native aggregates — dashboard,
object economy, project cards — each compute a total with one `SUM`, and a percentage OF THE SUBTOTAL
cannot be expressed that way: the row depends on the total and the total on the row. The alternatives
were worse (SQL cannot self-reference; loading every estimate's items into Java turns one aggregate
into N).

Two things fell out of that as guarantees rather than side effects:

- **a read never recomputes**, so a SIGNED estimate cannot drift behind the client's back — the rule
  costs nothing because nothing recalculates on the way out;
- **«backend = frontend» became a comparison of one number** instead of two formulas.

The formula now exists in exactly one place per side. Before this, `quantity × unitPrice` appeared in
seven: four in Java, the portal, the PDF, and the PWA.

**A deleted base does not zero the line.** `ON DELETE SET NULL`, plus `base_detached` set by the
service, the amount kept, and the row saying «база видалена». The master is charging for that work;
zeroing it would be data loss dressed up as tidiness. Manual wins over automatic — the same rule
`quantityManual` follows in measurements.

## Two things the seed data forced

**Our own catalog carries percentages in the PRICE column.** V82 shipped nine PERCENT positions into
the live tiling catalog — «Укладання плитки по діагоналі (плюс % до м.кв.)» at 33, «на відкос» at 88,
«великим форматом» at 50 and 76. Those are percents. So adding one from the catalog, or through a
template, maps `defaultPrice` → `quantity` and defaults the base to `POSITION` («плюс % до м.кв.»
means "of the m² work this is an extra on"). Copying it into `unitPrice` would have produced
«база 33 ₴».

Templates matter more here than the catalog does: a bundle line carries **no price at all**, so
without the mapping a percentage line would arrive as «0 % від …».

**Two names still said «коефіцієнт».** Coefficients are a decision we took and rejected — «коефіцієнт
1,2» is entered as «+20 %», one mechanic instead of two — and a catalog that keeps offering the other
word invites the other mechanic. Renamed in V88.

⚠️ **The rename had to touch three tables at once.** `estimate_template_items` references positions
**by name** and looks the price up in the master's own catalog. Renaming one side only would have
silently priced six bundle lines at zero. The same migration also normalises whitespace on all three
(several names were seeded as «( плюс % до м.кв.» with a stray space), and `EstimateTemplateService`
now matches through one shared `nameKey` — three call sites used to disagree about it.

Measured before changing anything: 167 catalog names, 167 bundle lines, **all 167 matching exactly**.
Nothing was broken; the fragility was.

## The markup, landing exactly once

A percentage line in a marked-up duplicate cannot simply keep its percent — that is a hole in the
whole two-price mechanic. Шафа 5 000 (material, never marked up), монтаж «20 % від шафи» = 1 000,
markup +30 %: if the percent stays 20 %, the copy still charges 1 000 and the work the foreman meant
to sell dearer sells at cost. The more percentage work an estimate holds, the less of the mechanic
survives.

| base | what happens | why |
|---|---|---|
| material / `MANUAL` | the **percent** is raised: 20 % → 26 % | the base does not move, so the line must |
| marked-up work | the percent is **left alone** | the base already grew; raising both marks it up twice |
| `TOTAL`, WORK line | left alone | the works subtotal it measures already contains the marked-up works |
| `TOTAL`, MATERIAL line | left alone | the materials subtotal passes through at cost, so its percent passes through too |

`source_unit_price` on such a row holds the **original percent**, and the economy query reaches the
base through a `LEFT JOIN` to work out what the crew's sheet charged. Both shapes then report real
margin: material base 1 300 − 1 000 = **300**; work base 260 − 200 = **60**. Subtracting per unit
price would have reported zero for the second — a systematic, invisible understatement of the number
a master plans against.

For a `TOTAL` base the crew's amount is measured against the crew's **ordinary** subtotal. Deliberate:
folding other percentages in would make two TOTAL lines depend on each other, which is exactly what
the three-step pass refuses to do.

## Bug found while building it

Duplicating copied `percentBaseItemId` verbatim, so a percentage line in the copy pointed at a line
of the **parent** estimate. `EstimateMath` would find no such line in its own list, treat the row as
detached, and freeze it. Bases are now re-pointed at the copy that came from the same source line;
a base that did not make it into the copy marks the row detached rather than measuring against
nothing.

## Existing data

**Zero PERCENT lines existed in any estimate** — verified against production before the migration was
written. That is why V88 needs no rescue step: the backfill (`line_total = quantity × unit_price`) is
right for every row without exception, and no estimate, signed or not, changes by a hryvnia.

## The model, simplified — «Від позиції» / «Від кошторису», per type (2026-08)

PWA `1.6.1 → 1.7.0`. A follow-up wave settled the shape of the whole feature, after the first
negative-percent cut proved too loose to explain on site.

**«Своя сума» (`MANUAL`) is retired from the product.** A hand-typed base was never worth a mode of
its own. The editor now offers exactly two bases — **«Від позиції»** (`POSITION`, a markup on one
line) and **«Від кошторису»** (`TOTAL`). The enum value survives and `EstimateMath` still reads a
legacy `MANUAL`/null-kind row from `unit_price` (V88 shipped hours earlier, so any stray row keeps its
number), but nothing new is created with it.

**«Від кошторису» is measured against the line's OWN type.** A WORK percent measures the works
subtotal, a MATERIAL percent the materials subtotal — the split the master already reads on the black
summary card and in the PDF. Reading the *whole* subtotal instead was the «косячок» a master caught:
a preview off by exactly the other type's total. `EstimateMath.recalculate` step 3 now computes a
`worksBase` and a `materialsBase` and each `TOTAL` line takes its type's; the PWA mirror
`useEstimate.recomputeLines` and the `ItemForm` preview do the same, so «backend = frontend» holds.

**Direction is per base, and it is not called «націнка/знижка».**

- **«Від позиції» is a markup — always `> 0`.** No sign choice: it is the shape of the catalog's own
  «…(плюс % до м.кв.)» positions.
- **«Від кошторису» may be `+` or `−`, never `0`.** A `−` is a discount off that type's subtotal, its
  `line_total` negative, lowering the total / economy («дав знижку — менше заробив»). The master picks
  the sign with a bare **+/− toggle** — no «націнка/знижка» wording; the field stays a positive
  magnitude, because the mobile decimal keypad has no minus key. Editing reads the stored sign.

The guards, relaxed for `PERCENT` only and tightened to the new rule:

- **DB** — V29's `CHECK (quantity >= 0)` is `CHECK (quantity >= 0 OR unit = 'PERCENT')` in **V89**
  (kept — a `TOTAL` percent needs the minus). Every other unit still rejects a negative.
- **DTO** — `EstimateItemRequest.quantity` has no `@DecimalMin`; `isQuantityUsable()` requires
  `!= 0` for a `TOTAL` percent, `> 0` for «Від позиції» and every other line.
- **Duplicate** — `markedUpPercent` leaves every «Від кошторису» percent alone: a WORK one rides the
  works subtotal the markup grew, a MATERIAL one passes through at cost like the materials it measures
  (the copy is editable if the master wants margin on that specific line).

## Tests

- `EstimateMathTest` — the three steps, several TOTAL lines against one base, the live link, manual
  detach, a deleted base keeping its amount, per-line rounding (33,33 % three times is 99,99), a
  **negative percent lowering the total** (−15 % of 1 000 → −150, total 850), and **per-type scoping**
  (a WORK 10 % measures works, a MATERIAL −5 % measures materials — never each other's subtotal).
- `ObjectEconomyQueriesIntegrationTest` — both duplicate shapes, against real Postgres, because that
  is the only place this SQL can be verified.
- PWA `AddItemSheet.test.tsx` — the «Від кошторису» preview base is the same-type subtotal excluding
  existing % lines, the `−` toggle submits a negative percent, and a MATERIAL % ignores the works
  subtotal. `percentMath.test.ts` — the wording, including that «₴/%» appears in none of the kinds.
