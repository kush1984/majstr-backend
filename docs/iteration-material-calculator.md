# Iteration (PLAN): material calculator — «скільки цього купити»

**Status:** **PLAN ONLY.** Nothing is built: no code, no migration, no test. Written 2026-09-01 at
the user's request («давай формуй план») so the thinking does not live only in a chat.
**Source:** the competitor scan in [open-questions.md](open-questions.md) → «Material calculators
(quantity → how much material to buy)», itself opened after a master named «ПРОраб» (АПК) and
«Смета М2». Material calculators are ПРОраб's centre of gravity and the widest functional gap
between us and it; «Смета М2» reaches the same place from the other end — by the time a measurement
is finished it has issued a «счёт на черновые материалы».
**Migrations it would need:** two, numbered from whatever is highest when the work actually starts
(**V117** today) — one seeding MATERIAL positions into the default catalog, one for the norms table.

> The honest headline, before anything else: **the code is maybe a fifth of this feature. The rest
> is consumption norms.** This is the drywall-catalog iteration again — the hard part was never «how
> do we store it», it was «where do numbers we are not ashamed of come from».

---

## 1. Decide first what the calculator PRODUCES

Three different answers get conflated:

| | what it answers | who reads it |
|---|---|---|
| **(a) a shopping list** | «скільки чого купити» | the master, at the counter |
| **(b) MATERIAL lines in the estimate** | «за який матеріал я виставляю рахунок» | the client |
| **(c) a cost forecast** | «скільки я на це витрачу» | `Прибуток` |

**Build (a); make (b) one button over the same result.** The lines have somewhere to land already —
`type = MATERIAL` and the existing batch add — so (b) costs a button, not a feature. (c) then arrives
for free later by comparing the plan against the act receipts that already exist. One feature,
displayed three ways, rather than three features.

## 2. Where the quantity comes from

Best to worst, and all three should work:

1. **The estimate's WORK lines** — the quantity is already there and already agreed with the client.
   This is the main path.
2. **Заміри** — when there is no estimate yet.
3. **Typed by hand** — always available.

This is the point where we beat ПРОраб on shape rather than on content: its calculators are an
island, so the master enters the same area twice. Ours must be an **action over what is already
computed**, never a second data-entry surface.

## 3. The model: a norm hangs off a catalog POSITION

Two possible shapes:

**A — a tech card per position.** «Монтаж перегородки ГКЛ в 1 шар, м²» → ГКЛ 2.0 м²/м², профіль ПН
0.7 м.п./м², ПС 2.0 м.п./м², саморізи 34 шт/м², стрічка 1.2 м.п./м², шпаклівка 0.4 кг/м². A works
estimate then yields its materials with no further input.

**B — standalone calculators**, one screen per material (ПРОраб's shape).

**Take A.** What makes it viable *now* is V116: a catalog position's name already names its
**variant** («в 1 шар» / «в 2 шари», «під фарбування Q3» / «Q4»), because the catalog convention is
that a position names the work **and its result**. So most of the parameters a standalone calculator
would have to ask for are already answered by which position the master picked — a direct dividend of
the drywall rebuild.

B is also precisely the structure the master called broken: a set of flat, mutually disconnected
screens («логічна цепочка як дерево програми просто відсутня»).

**Keying.** A norm keys on the position **name** (`lower(trim(name))`), the same key the codebase
already joins a template item to a master's catalog price by. Known hazard, inherited from that
choice: **a rename must carry the norms with it** — V116 proves renames happen.

## 4. The prerequisite that cannot be skipped: we have no MATERIAL catalog

The default catalog is **works-only (V81)**. Material positions exist only where a master typed them
himself. But a norm has to point at something: «профіль ПС 50/50, м.п.» must BE a position, or there
is nowhere to hang a price and nothing to add to an estimate.

So chunk one is **seeding a per-trade material catalog — brand-free and priceless**:

- brand-free because the catalog rule holds here unchanged («Шпаклівка фінішна, кг», never
  «Knauf Fugenfüller»); a standard may be cited in `description`, a brand may not name a job;
- **priceless on purpose.** Material prices are not ours to state, they move weekly, and a wrong one
  is worse than none. This is also the exact complaint «Смета М2» collects — except they ship no
  prices *and* no positions, so the master types the whole list from zero.

A pleasant consequence worth stating out loud: **this calculator becomes the thing that finally
populates a master's material catalog.** It tells him what he needs, he prices it once, the rows are
his catalog from then on — and they start feeding `price_insight_candidate` like everything else.

## 5. Where the value actually is

Three things, none of which is the multiplication:

**Waste (`запас`).** A norm without it is a lie. It differs per material (плитка 5–10 %, шпалери
15 %, ламінат по діагоналі 15 %, ГКЛ ~10 %), so the default belongs **in the norm row**, and the
master edits it inside the calculation.

**Packaging.** «Треба 47.3 кг шпаклівки» is not an answer. **«4 мішки по 15 кг»** is — that is the
question a person actually has at the till. A material carries an optional package (`pack_qty` +
label); rounding **up** to whole packages is the headline figure, net is the small print. No package
known → show the net quantity and say so. Degrade, never fail — the same ladder discipline as
`FiscalQrService`.

**Visible arithmetic.** No magic number ever: «20 м² × 2.0 м²/м² = 40 м² + 10 % = 44 м² → 15 листів
по 3 м²». Same rule as the act's «ДОВІДКОВО» block and the QR read — we show what was multiplied,
because the master is entitled to reject the norm.

## 6. Invariants and traps

- **Units must match, and a mismatch REFUSES.** A norm assumes the work's unit. If the master keeps
  «Монтаж перегородки» in м.п. and the norm is written per м², the honest output is «не знаємо», not
  a guessed conversion.
- **One material, several works.** Putty arrives from both the drywall and the painting lines.
  Aggregate by material + unit, but **keep the contributions** — otherwise 60 кг appears from
  nowhere.
- **The material may already be in the estimate.** If he added «Ґрунтовка 20 л» by hand, adding again
  double-buys. Show «вже є в кошторисі: 20 л» and offer to top up — the same courtesy as
  `disabledNames` in `CatalogPicker`.
- **A missing price is a normal state, not an error.** Quantities render, sums appear as prices are
  filled in.
- **Coverage must be stated.** We will not have a norm for every position. «Норму знаємо для 12 з 19
  позицій» plus the names of the other 7 — otherwise the master reads the list as complete and
  under-buys. This is the single most likely way to hurt somebody with this feature.
- **A norm is a suggestion, not a promise.** Every quantity is editable before it becomes anything.

## 7. UI shape (mobile-first — the master is on a phone)

An action on the estimate (⋮ → «Розрахувати матеріали») opening a sheet: the grouped list, each row
editable (quantity, waste), the derivation readable per row, and two actions at the bottom —
**«Додати в кошторис»** and **«Список покупок»** (share / PDF). No new tab, no new navigation. The
object-level view (all estimates at once) is the same computation aggregated, and comes later.

## 8. Backend or frontend — and why there must be no third mirror

Norms are shared seeded content, exactly like the catalog: a table, a migration, a catalog-version
bump, notices. So **the backend computes**, and the formula is **not mirrored** into the PWA. Two
mirrored formulas already cost us a standing "change both sides together" rule (`EstimateMath` ↔
`useEstimate`, `MeasurementCalc` ↔ `measurementCalc.ts`); a third is not worth a millisecond of
latency.

The good property that falls out: **the calculation writes nothing.** It is a derived read; adding
the chosen rows goes through the existing add-items path. No new offline entity, no new idempotency
key, no new write guard. The price is that the calculation needs the network — acceptable, and the
same bargain the AI flows and the ДПС lookup already make. Say it plainly in the UI when offline.

## 9. Staging

- **Cut 0 — no code at all.** Get the master's own norms for 10–15 positions, in the same way the
  drywall PDF arrived: position name **exactly as in the catalog** → material → per unit → waste →
  package. This is the highest-value action available today and it costs nothing.
- **Cut 1 — one trade, end to end.** Seed the material positions + the norms for **DRYWALL** (its
  catalog is the freshest, V116/V117, and there is a live source for it), the derive endpoint, the
  sheet, «Додати в кошторис».
- **Cut 2 — PAINTER and TILER.** Pure content; the code is the same.
- **Cut 3 — the list as a document**: share it with the client (when the client buys the material),
  and tie **plan ↔ fact** against the act receipts. This is the part nobody else can copy — we are
  the only one of the three with a receipt → act → economy chain.
- **Cut 4, possibly never.** Standalone calculators for a master with no estimate. Only on
  demonstrated demand.

## 10. Decisions the master owns (do not guess these)

1. **Whose norms?** Mine from handbooks, or his from practice. Recommendation: **his** — the value is
   in his numbers, and it is the same method that made the drywall catalog good.
2. **Who buys the material** — master or client? It decides whether these rows belong in the estimate
   at all or only in a list.
3. **Which trade first.** Recommendation: DRYWALL.
4. **FREE or PRO.** No model call is involved — it is arithmetic over seeded data, so by the
   fiscal-QR precedent («nothing runs, so there is nothing to gate») it is FREE. Against that: the
   norms are content we invest in. A possible middle: the calculation free, the shopping-list PDF
   PRO.

## 11. Deliberately not copied

- **ПРОраб's laminate layout scheme** — a drawing tool: a lot of work, little value next to norms.
- **Separate works / materials estimates** (ПРОраб) — we carry `type` on the line and group in print;
  two documents would double the signing and portal surface for the same money.
- **ДБН/ДСТУ resource norms in full.** We are not building an ERP. A short, honest, editable set of
  norms for the trades we actually seed beats a complete one nobody trusts.

## 12. The risk, in one line

**A norm we invented, that the master then billed a client for, is worse than no norm at all.** Hence:
his numbers, visible arithmetic, editable before use, and stated coverage.
