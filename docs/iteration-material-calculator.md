# Iteration (PLAN): material calculator — «скільки цього купити»

**Status:** **CUT 1 SHIPPED** (2026-09-07, V126) — the shopping list, the material dictionary, the
norm schema, the master parameters and the `LITRE` unit; see
[iteration-shopping-list.md](iteration-shopping-list.md) for what actually landed. **The rest of this
document is still PLAN**: no norms are seeded and nothing derives a material quantity yet. Written
2026-09-01 at the user's request («давай формуй план») so the thinking does not live only in a chat.
**Source:** the competitor scan in [open-questions.md](open-questions.md) → «Material calculators
(quantity → how much material to buy)», itself opened after a master named «ПРОраб» (АПК) and
«Смета М2». Material calculators are ПРОраб's centre of gravity and the widest functional gap
between us and it; «Смета М2» reaches the same place from the other end — by the time a measurement
is finished it has issued a «счёт на черновые материалы».
**Migrations it would need:** two, numbered from whatever is highest when the work actually starts
(**V125** today; so the calculator starts at V126) — one seeding MATERIAL
positions into the default catalog, one for the norms table. §16 would add two more.

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
- **Cut 2b — FLOORING** (added 2026-09-07, at the master's request). Norms are the simplest of the
  four and are pure geometry: laminate = area x waste (5 % straight, 10-15 % diagonal - the figure
  §5 already cites), underlay ~1.0 m2/m2, damper tape = perimeter, screed = thickness x density.
  Exactly one parameter lives outside the position name (screed THICKNESS), which is the §14.2
  single-parameter case, not a new mechanism. The catalog is already the right shape («Ламінат
  укладання», «Ламінат по діагоналі», «Кварцвініл SPC ламінат», «Машинна стяжка», «Лінолеум
  побутовий», plus the whole plinth group in м.п.). §11's refusal stands unchanged: we compute the
  QUANTITY, we do not draw a layout scheme.
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

---

## 13. Cut 0, half two: what the online survey actually returned (2026-09-07)

Run at the master's request («а ти не можеш десь в інтернеті пошукати готових розрахунків»). This
section records the survey **as evidence**, per the sourcing rule in open-questions.md. Nothing here
is a norm we ship.

### 13.1 The finding that matters most: the sources are not independent

The numbers agree almost perfectly across every Ukrainian page found — and that agreement is
**worth much less than it looks**, because they are one source echoed. `alkiv.ua`'s table names
its materials «Фугенфюллер», «Мульти-фініш», «Тифенгрунд» — those are Knauf PRODUCT names, not
material categories. The table is a Knauf system card retyped by a reseller. `482.com.ua` and
`bm.kiev.ua` are shop calculators over the same systems.

So the survey yields **one voice (Knauf), not three**, and it is a voice with an interest: a system
card is dimensioned for that manufacturer's profile spacing, that manufacturer's putty coverage,
and it exists to sell a complete kit. This is exactly what the open-questions item warned about
(«most of those services are selling one manufacturer's material»).

**Consequence for the iteration: the survey settles the SHAPE, not the NUMBERS.** The master's own
figures stay the source of truth; these become the pre-filled candidate he corrects, which is
cheaper for him than recalling a list from zero.

### 13.2 What the shape gives us for free

- **Which materials belong to which work** — the hard half of a tech card, and it is stable across
  every source: sheet, ПН/ПС profile, screws, joint tape, sealing tape, dowels, putty, primer,
  finishing compound, mineral wool.
- **Waste is conventional, and it is already partly baked in.** Published sheet norms carry the cut
  loss inside the figure (2.0 net → 2.1 published). Sources state 10–20 % deviation on real objects,
  and **+20–30 % profile** on a partition with doors or niches. Our model keeps waste as its own
  editable column, so a published figure must be **un-baked** before it is stored, or the waste is
  counted twice.
- **Package sizes** (the §5 «4 мішки по 15 кг» half): sheet 1.2×2.5 = 3.0 м²; profile 3 m / 4 m;
  screws 1000; dowels 100; joint tape roll 45 m; sealing tape roll 30 m; putty sacks 25 kg / 10 kg.

### 13.3 Candidate numbers, per m² of partition surface (для перевірки майстром)

Single-layer, both faces («Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар», M2):

| Матеріал | Норма | Джерело / розбіжність |
|---|---|---|
| ГКЛ 12.5 мм, м² | **2.0** нетто (публікується як 2.1 із підрізкою) | Knauf 2.0 / alkiv 2.1 |
| Профіль ПН (UW), м.п. | **0.7** | збіг; 1.3 якщо висота > довжини листа |
| Профіль ПС (CW), м.п. | **2.0** | збіг (крок 600 мм) |
| Мінвата, м² | **1.0** | збіг |
| Саморізи ГКЛ, шт | **34** | alkiv 34 |
| Стрічка для швів, м.п. | **2.2** | 1.1 на сторону |
| Шпаклівка для швів, кг | **0.9** | 0.45 на сторону |
| Ущільнювальна стрічка, м.п. | **1.2** | alkiv |
| Дюбель 6/40, шт | **1.5** | alkiv 1.5 / 482 ~1.8 |
| Ґрунтовка, л | **0.2** | 0.1 на сторону |
| Фінішна шпаклівка, кг | **1.2** | збіг |

Two-layer («…в 2 шари»): ГКЛ **4.05**, шпаклівка для швів **1.5**, саморізи **14 + 30** (перший
і другий шар окремо), решта без змін.

### 13.4 The unit-mismatch invariant is not hypothetical — it fires on row one

§6 says a norm whose unit does not match the work's unit must REFUSE. Our own catalog produces that
case immediately: **«Заповнення та армування стиків ГКЛ» is `LINEAR_METER`**, while every published
joint-tape norm is «2.2 м.п. на 1 м² площі». Those are different questions. For that position the
norm must be written per м.п. OF JOINT (tape 1.0 м.п./м.п. + putty per м.п.), not converted from the
m² figure. Same for «Проклеювання склополотном примикань і кутів» and «Заповнення стиків ГКЛ
паперовою стрічкою високої щільності», both `LINEAR_METER`.

**Sources surveyed:** alkiv.ua (full tables), 482.com.ua (calculator coefficients + pack sizes),
bm.kiev.ua, budiak.kiev.ua (table is an image, unreadable), knauf.ru (unreachable from here;
figures reached via search snippets only).

## 14. Survey round two: TILING and PAINTER (2026-09-07)

### 14.1 These trades have a FORMULA, drywall had a TABLE — and that is a real difference

The drywall survey (§13) returned one manufacturer's assertion, echoed. Tiling and painting return
something better: a **published formula over measurable inputs**.

- **Tile adhesive** = (notch size ÷ 2) × the mix's kg/m²-per-mm. The notch is geometry, not a claim,
  and the density is on the sack. Cross-check tables agree because they are all evaluating the same
  arithmetic, not copying each other.
- **Paint** = покриваність ÷ сухий залишок × 100, both printed on the tin.
- **Grout** = (A+B)/(A×B) × thickness × joint width × density × 1.6 — pure geometry of the joint.

**Consequence:** the «invented norm» risk is materially LOWER here than for drywall, because the
result is auditable by anyone with the packaging in hand. The visible-arithmetic rule (§5) pays off
doubly — we can show the actual formula, not just the multiplication.

### 14.2 The new problem: a parameter the position name does NOT carry

§3 rests on «V116 made a position's name state its variant, so the parameters are already answered».
That premise **holds for tiling adhesive and breaks for grout and paint.**

It holds beautifully for adhesive: our TILING catalog already names the FORMAT —
«Укладання плитки 600х600», «1200х600», «1200х200», «1500х1500», «Укладання плитки мілкоформатної»,
«Укладання мозаїки», «Укладання керамограніту від 15 мм». Format is the dominant driver of adhesive
consumption (30×30 → 2–3 кг/м²; 60×60 → 3.8–5.4; >60 см → 4.6–6.7), and it picks the notch by
convention. So format → notch → kg/m² resolves off the name alone.

It breaks for:
- **Grout** — needs the JOINT WIDTH (2 мм vs 3–5 мм roughly doubles the figure). No position names it.
- **Paint** — needs the NUMBER OF COATS and the specific paint's coverage (140–200 г/м² across
  types). «Шпаклювання фінішне (2–4 рази)» names a RANGE, which is not a number.
- **Waterproofing** — 2.5–3.5 кг/м² cement 2K vs 1.5–2 polymer: a material-class choice, unnamed.

**Resolution (consistent with the rest of the plan, not a new mechanism):** a norm may declare ONE
optional parameter with a default. The default computes immediately so the master is never blocked;
the parameter is editable in the sheet and appears in the visible arithmetic
(«шов 3 мм → 0.4 кг/м²»). What it must never do is pick silently and render a bare number.
A norm with more than one such parameter is a sign the CATALOG POSITION is underspecified — the
fix belongs in the position name (the V116 move), not in a growing parameter form.

### 14.3 PAINTER: the unit-mismatch invariant is the dominant case, not an edge case

V99 split most painter positions into м.п. and м² twins («Армування стін скловолокном (склохолст)
(м.п.)» / «(м²)», «Базове шпаклювання під скловолокно (м.п.)» / «(м²)», «Грунтування (м.п.)» /
«(м²)», «Вирівнювання стін (м.п.)» / «(м²)»). Every published paint/putty norm is per м². So for the
м.п. half of each pair a norm either does not exist or must be authored separately per м.п.

This makes §6's «units must match, and a mismatch REFUSES» the **normal** path in PAINTER, not the
exception. Coverage reporting («знаємо норму для 12 з 19 позицій») therefore has to be honest and
prominent here or a master reads a half-empty list as complete.

### 14.4 Candidate numbers (для перевірки майстром)

TILING, per m²:

| Матеріал | Норма | Ключ |
|---|---|---|
| Клей, кг | **2–3** (30×30) · **3.8–5.4** (60×60) · **4.6–6.7** (>60 см) | формат із назви позиції |
| Затирка, кг | **0.2** (30×30, шов 2 мм) · **0.3–0.5** (шов 3–5) · **1.0–1.5** (мозаїка) | формат + ШИРИНА ШВА (параметр) |
| Ґрунтовка глибокого проникнення, л | **0.1–0.2** на шар | — |
| Бетоноконтакт, кг | **0.15–0.5** | — |
| Гідроізоляція обмазувальна 2К, кг | **2.5–3.5** за 2 шари | клас матеріалу (параметр) |
| Гідроізоляційна стрічка, м.п. | периметр **+10 %** | — |

PAINTER, per m² per coat:

| Матеріал | Норма | Примітка |
|---|---|---|
| Ґрунтовка універсальна/глибокого проникнення | **0.06–0.16 л** | ~8 м² з літра |
| Бетонконтакт | **0.15–0.25 кг** | по гладких основах |
| Шпаклівка стартова, шар 1 мм | **1.1–1.3 кг** | по бетону |
| Шпаклівка фінішна, шар 1 мм | **0.5–1.0 кг** | шар зазвичай ≤ 1 мм |
| Фарба водоемульсійна | **0.14–0.16 кг** | на ОДИН шар |
| Фарба акрилова | **0.13–0.20 кг** | на ОДИН шар |
| Запас | **10–15 %** | конвенція, повторюється в усіх джерелах |

### 14.5 A lesson from our own history: do not put the pack in the NAME

The pre-V81 material rows (V13/V35) were shaped «Клей для плитки стандартний 25 кг», unit `PIECE`;
«Затирка для швів цементна 2 кг», `PIECE`; and several carried a brand outright («Клей для плитки
Ceresit CM 11 25кг», «Гідроізоляція Ceresit CR 65 5кг»). Two defects in one row: the brand names the
job, and the package is baked into the NAME, so a 10 kg sack of the same material is a second
position and a norm in kg cannot point at either.

The seeded material catalog must therefore store **«Клей для плитки, кг»** with the package as DATA
(`pack_qty = 25`, label «мішок»), never «Клей 25 кг, шт». This is what §5's package rounding needs
to work at all, and it is why the V81 rows could not simply be un-deleted.

**Sources:** kreisel.ua, keramaexpert.ua, plytochnyk.com.ua, mira.ua (tile); tikkurila-shop.com.ua,
novatorstroy.com, talanx.com.ua, poznayka.com.ua (paint/primer/putty).

---

## 15. Parameters are ENTERABLE — the master's rule (2026-09-07)

Decided by the master, in his words: «параметри які нам потрібні для точного розрахунку, то треба їх
мати можливість ввести, якщо є щось дефолтне, то обираємо то, але з попередженням і нехай майстр при
потребі собі змінить».

This upgrades §14.2 from a proposal to a rule, and adds the warning, which §14.2 did not have:

1. **A norm may declare parameters that the position name does not carry** — grout JOINT WIDTH, paint
   COAT COUNT, screed THICKNESS, waterproofing MATERIAL CLASS.
2. **Every parameter has a default, so the sheet computes immediately.** The master never faces an
   empty form: he opens it, a number is already there.
3. **A default is announced, never silent.** The row says which default was assumed («шов 2 мм —
   за замовчуванням»), visibly, next to the arithmetic §5 already requires. A quantity resting on an
   assumed parameter must be distinguishable at a glance from one resting on the master's own input.
4. **Every parameter is editable in place**, and editing it recomputes that row (and only the rows it
   feeds) — no save, no round trip through a settings screen.
5. **Once edited, it is remembered for that estimate** (see §16 — the chosen parameters live on the
   saved list), so the second calculation on the same object does not re-ask.
6. **The §14.2 discipline still holds as a design smell test**: if a position needs more than one or
   two such parameters, the CATALOG POSITION is underspecified and the fix belongs in the name, not in
   an ever-growing parameter form.

## 16. Where the shopping list LIVES (proposal — 2026-09-07)

The master's question: «якщо для прикладу майстер рішить не додавати ці розрахунки в кошторис — треба
це десь зберігати, той список що купити». He is right, and it **changes §8**: §8 concluded «the
calculation writes nothing», which was true only while the list existed for the length of one sheet.
A list he can come back to is a write, and it needs an entity.

Keep the two halves separate, because only one of them writes:

- **Deriving stays a pure read.** `POST …/materials/derive` takes the lines and the parameters and
  returns rows. No persistence, no idempotency key. Everything §8 said about the calculation itself
  survives.
- **Saving the list is an explicit, separate action.** «Зберегти список» — one more write path, not a
  side effect of opening the sheet.

### 16.1 It belongs to the ESTIMATE (master's ruling, 2026-09-07)

The first draft of this section proposed an object-level list. **The master overruled it**, and his
reasoning is the product's own order of operations: «ти ж складаєш кошторис і тоді вже по кошторису
рахуєш матеріали». The estimate is the thing that exists first; the materials are counted **off** it;
so the list is that estimate's own by-product and belongs beside it.

**Proposed: `material_list` (one per estimate) + `material_list_item`, V126 + V127.**

One list per estimate, not many. A pile of dated lists is the failure mode — the master would not
know which one is current. A second calculation **merges into** the existing list; it does not create
a sibling.

The object-level route is not rejected, only postponed: computing for a WHOLE object off complete
Заміри is §2's second input and is now logged as its own OPEN item («Calculate materials for a WHOLE
OBJECT from Заміри»). It needs a definition of «заміри повні» that does not exist, and without one the
list silently under-counts — §6's worst failure.

Two consequences of the estimate scope:

- **A signed estimate is frozen, the list is not.** The list is not part of what the client signed —
  it is the master's buying note — so `requireNotSigned` does **not** guard it. This is the one place
  the list deliberately does not inherit the estimate's immutability.
- **`duplicate` does not copy the list.** A «клієнтський варіант +15 %» is a pricing copy, not a
  second shopping trip.

### 16.2 The list is NOT money — it never touches the economy (master's ruling)

Stated plainly because it is the single easiest way to wreck this feature: «сам список це не чек, воно
на якусь економіку точно не має впливати».

- **Nothing on the list posts an `ObjectExpense`, ever.** Not on save, not on «куплено».
- **The list is not an act receipt** and shares no code with `work_act_receipt`. Money still enters
  exactly where it enters today — by fact, through a receipt attached to an act, which then reaches
  `Прибуток` through the existing chain.
- **`Прибуток` therefore does not change** when a list is saved, edited or fully ticked off. A plan
  is not a fact.
- The `bought` tick means «я це купив», **not** «ось скільки я за це заплатив». It carries no amount.
  A list row has a quantity and a unit; it has no money on it at all.

Plan↔fact (§9 Cut 3) is a **comparison**, not a merge: it reads the list on one side and the act
receipts on the other and shows the difference. Neither side writes to the other.

### 16.3 Rows are snapshots, exactly like `estimate_items`

Name, unit, quantity, `pack_qty`, pack label and the derivation text are **copied by value, no FK to
the catalog** — the same precedent as `source_unit_price`, `base_origin_label` and
`estimate_items.description`. A catalog rename or a norm revision must not silently rewrite a list the
master already took to the shop.

Each row carries:

- `source` — `CALCULATED` or `MANUAL` (he must be able to add «пакет саморізів» by hand; the list is
  what he buys, not only what we derived);
- `edited` — set the moment he changes a derived quantity;
- `bought` + `bought_at` — the tick at the till. This is the thing that makes it a shopping list
  rather than a printout, and it is the single most-used write in the whole feature;
- the derivation, frozen as text, so the row still explains itself after the estimate changes.

### 16.4 Recalculation MERGES and shows a diff — it never overwrites

The estimate will change after the list is saved. The rule is the one
`catalog_update_notices` already established: **we compute the difference and he accepts it**; nothing
in his list changes behind his back.

- a new material → offered as a new row;
- a changed quantity on a row he never touched → offered, marked;
- a changed quantity on a row where `edited` or `bought` is set → **shown, never applied**;
- a material that disappeared from the estimate → shown as no-longer-needed, never deleted.

### 16.5 The parameters live on the list

§15's chosen parameters are stored on `material_list` (joint width, coat count, screed thickness…),
so the second calculation on the same estimate reuses his answers instead of re-asking and re-warning.
This is the whole reason §15.5 can promise the answers are remembered — read «for that
estimate», since §16.1 put the list there rather than on the object.

### 16.6 The honest costs

- **A new offline entity, eventually.** Ticking «куплено» happens in a hardware store, which is
  exactly where there is no signal. That is a real outbox entity (`materialListItem`, update-only) and
  it is the one piece §8's «no new offline entity» promise genuinely loses. **Phase it:** Cut 1 ships
  the list online-only, the offline tick is a named follow-up, not silently dropped.
- **A second place materials can live** — the estimate (billing) and the list (buying). They are
  different axes and both are legitimate, but the UI must never let the master read one as the other.
  «Додати в кошторис» and «Зберегти список» are two buttons doing two different things, and doing both
  is normal, not a double count.
- **A FREE-plan surface with rows in it.** FREE everywhere per the master's decision; no limit is
  proposed, but the list is per-estimate and merges, so it cannot grow without bound.

**Status: proposal. Not approved, nothing built.**

## 17. Where the ENTRY POINT goes — not the FAB (proposal, 2026-09-07)

The master's constraint: «у наш фаб добавляти ще одне меню це смерть, там вже і так їх стільки є що
капець, скоро не буде на екран телефона влізатись». Measured, he is right and closer to the edge than
it feels:

`EstimateEditorPage` already renders up to **8** `FabAction`s — додати позицію, вибрати, дублювати з
націнкою, диктування, чек, поділитися, PDF, зберегти як шаблон. Each is `min-h-[44px]` with a `gap-2`,
so the open stack is ~52 px per row: **~416 px of pills**, plus the 56 px button and the 80 px
`bottom-20` offset ≈ **550 px**. On a 375×812 phone with browser chrome that leaves roughly 140 px.
A 9th action eats most of it; a 10th overflows — and the container is `fixed`, **not scrollable**, so
it clips at the TOP, which is where `«＋ Додати позицію»` sits. The most important action in the app
is the first thing that silently leaves the screen. §7's «⋮ → «Розрахувати матеріали»» is therefore
withdrawn.

**Proposal: the entry point already exists and is empty — the «Матеріали» row of the bottom summary
sheet.**

`MobileSummarySheet` is the dark bar pinned to the bottom of the estimate screen. Pulled up, it shows
`TypeBreakdown` for **Роботи** and for **Матеріали**. That materials row renders **unconditionally**,
which after V81 means the normal estimate shows a row reading «Матеріали 0» that does nothing at all.
That row becomes the door: tap it → the materials sheet (derived rows, parameters, «Додати в
кошторис», «Зберегти список», and the saved list on return).

Why this and not a menu entry:

- **It costs zero new navigation** — no FAB action, no tab, no route in the bottom nav. The one thing
  the master said he cannot afford.
- **It is already about materials.** The master is not learning a new place; he is finding out that a
  row he has seen a hundred times is tappable. A «0» that turns into «порахувати» is a better teacher
  than a menu item he must first go looking for.
- **It is in the thumb zone by construction** — the summary bar is the bottom of the screen.
- **The saved list has an obvious way back.** After §16 the list persists, so «where do I re-open it»
  is a real question; the same row answers it, and can carry the state («12 позицій, 5 куплено»).

Costs, stated honestly:

- **Discoverability is weaker than a named menu item.** Mitigation: the row shows an affordance when a
  calculation is possible (a chevron and «порахувати» instead of a bare «0»), and it is the kind of
  thing one line in the release note fixes permanently.
- **`TypeBreakdown` is shared with the estimate card**, so only the MATERIAL instance inside the sheet
  becomes interactive — an optional callback, not a rewrite. The WORK row stays inert.
- **Desktop needs its own answer**: the summary sheet is `lg:hidden`. On desktop the same action goes
  where there is room, and the FAB pressure does not exist there.

Rejected alternatives: a 9th FAB action (measured above); a new tab on the object screen (it is not
object-scoped — §16.1); a new bottom-nav destination (the same «смерть» one level up).

**Status: proposal. Not approved, nothing built.**

## 18. Rules fixed in prompt v4 (2026-09-07) — binding, not proposals

The external prompt file (`C:\Work\prompts\materials-calculator-prompts-v4.md`) is the build
instruction; these are the rules it hard-codes that this plan must not contradict later.

**A recalculation REPLACES its own contribution.** The list is object-scoped while the calculation
is estimate-scoped, so a row records `source_estimate_id`. Re-running estimate E rewrites only E's
own CALCULATED rows; rows from another estimate, manual rows, and rows the master edited are left
alone. Without this the master who fixes a typo and recalculates gets double the material — and
recalculating is an action we offer ourselves.

**A bought row is never touched by a recalculation** — not its quantity, not its flag. A larger new
figure becomes a separate delta row. «Куплено 12 шт» that silently becomes «18 шт» answers neither
"did I buy it" nor "how much is left".

**«Очистити куплені» hides (`cleared_at`), never deletes.** A deleted bought row is re-added by the
next recalculation as unbought, and the master buys it twice. The hidden row keeps participating in
the merge as bought, which is what blocks the re-add.

**A norm is found by name and unit; `trade` FILTERS the answer.** The key is `(name_key, unit)`;
`trade` cannot carry it, for three reasons that are all in the repo: `estimate_items.trade` is
nullable by design (V125 — ADDENDUM and hand-typed lines), V118 stores a position shared by two
trades once under whichever claimed it first, and both the V125 backfill and
`EstimateService.resolveTrade` (line 1231) *derive* trade from `(name, type, unit)` — it is a
property of the position's identity, not part of it. What it DOES do is decide which of the key's
answers may be used: the position's own trade, or a norm filed under no trade at all. An ambiguous
hit on a position that names no trade is skipped; nothing is picked at random. (§25 replaced the
original fallback — «name and unit alone whenever the trade misses» — which was this section's
reading and turned out to sell a painting section's materials off one drywall line.)

**`name_key` is `EstimateTemplateService.nameKey`**, extracted to a shared place and used by both
the price resolution and the norms. Two private notions of "the same name" would drift silently.

**A master-level parameter must be a HABIT.** Tile size, sheet size, waste percent qualify; a room's
perimeter does not — it has no meaningful default, and §15's "a default is announced" would make us
announce another flat's number as this master's answer. Object properties live on the calculation,
empty by default; until entered, whatever depends on them goes to the coverage report.

**Norms are default-only in cut 1, with the growth path reserved**: `material_norm.owner_id NULL =
default`, to be forked on write exactly like `template_default_override` (V113) when personal norms
land. No parallel "master coefficient" mechanism beside it.

**Entry point:** §17's summary-sheet row is superseded as the primary door by a **state-driven card
on the home screen** («🛒 Купити для …»), shown only while unbought rows exist and the list is not
archived. It costs no navigation, and it does not depend on the master exploring — the field report
in `open-questions.md` («masters do not discover the FAB») is why. The summary-sheet row stays as a
secondary way back, not as the discovery path.

---

## 19. Cut 2, step 1: the DRYWALL unit audit (2026-09-08)

Prompt B opens with «аудит одиниць. Без нього коду не писати» and requires the table to be reported
**before V127 is written**. This is that table. Source of truth: all 126 migrations replayed into a
scratch database, then `catalog_templates WHERE trade = 'DRYWALL'` read off it — not read out of the
migration files, because V116 merges and V122 deletes.

**56 live DRYWALL positions**: 34 `M2`, **15 `LINEAR_METER`**, 6 `PIECE`, 1 `PERCENT`.
`material` = 0 rows, `material_norm` = 0 rows — V127 is pure content on top of V126's schema.

### 19.1 The table

Verdicts: **✅ derivable** from the position alone · **⚙ parameter** (needs a figure the name does not
carry) · **∅ no material** · **❔ coverage** (geometry the name cannot carry at all).

#### Підготовка та захист

| Position | Unit | Derives | |
|---|---|---|---|
| Грунтування | m² | Ґрунтовка 0,10–0,15 л | ✅ also PAINTER |
| Демонтаж гіпсокартонної стелі | m² | — | ∅ also DEMOLITION |
| Демонтаж перегородки з гіпсокартону | m² | — | ∅ also DEMOLITION |
| Захист підлоги картоном | m² | Картон захисний 1,05 m²; стрічка малярна | ✅ also PAINTER |

#### Каркас і обшивка

| Position | Unit | Derives | |
|---|---|---|---|
| Вирізка отворів в гіпсокартоні | шт | — | ∅ |
| Монтаж арки з гіпсокартону | шт | — | ❔ arch size not in the name |
| Монтаж гіпсокартону на клей | m² | ГКЛ `1÷площа_листа`; клей гіпсовий ~5 кг | ✅ no frame |
| Монтаж гіпсокартону на стелю зі скосами | m² | ГКЛ; CD; підвіс ~0,7 шт; TN25 ~30 шт; дюбель-цвях | ✅ **⚙ UD = perimeter** |
| Монтаж гіпсокартону на стелю рівну | m² | same | ✅ **⚙ UD = perimeter** |
| Монтаж гіпсокартону на стіни | m² | ГКЛ; CD; підвіс прямий; TN25; дюбель-цвях | ✅ **⚙ UD = perimeter** |
| Монтаж декоративних елементів з гіпсокартону | шт | — | ❔ |
| Монтаж екрану ванни з підступком | шт | — | ❔ a fixed set is possible — the master decides |
| Монтаж каркасу посиленим профілем | **м.п.** | Профіль UA ~1,05 м.п.; анкер | ✅ per м.п. |
| Монтаж конструкцій (перегородки 2 сторони) в 1 шар | m² | ГКЛ 1 m²/m²; CW/UW; TN25; стрічка ущільнювальна | ✅ see §19.2 C |
| Монтаж конструкцій (перегородки 2 сторони) в 2 шари | m² | ГКЛ 2 m²/m²; TN25 + TN35 | ✅ see §19.2 C |
| Монтаж короба (прямого) по периметру стелі | **м.п.** | ГКЛ; CD/UD ~3 м.п. | ⚙ box section (w+h) |
| Монтаж короба (радіусного) по периметру стелі | **м.п.** | ГКЛ арковий 6,5; профіль | ⚙ box section |
| Монтаж напівкруглої конструкції ГКЛ | m² | ГКЛ арковий 6,5 (вищий запас); профіль | ✅ one side? — the master decides |
| Монтаж ніші під прихований карниз короб під комунікації | **м.п.** | — | ⚙ section |
| Монтаж профілю тіньового шва по периметру стелі | **м.п.** | Профіль тіньового шва 1,05 м.п. | ✅ per м.п. |
| Монтаж радіусних конструкцій (перегородки) в 1 шар | m² | ГКЛ 1 m²/m², вищий запас | ✅ see §19.2 C |
| Монтаж радіусних конструкцій (перегородки) в 2 шари | m² | ГКЛ 2 m²/m², вищий запас | ✅ see §19.2 C |
| Монтаж сухої збірної підлоги з гіпсоволокна | m² | Елемент підлоги ГВЛ 1,05 m² | ⚙ засипка = thickness |
| Монтаж треків прихованого карниза | **м.п.** | Трек 1,05 м.п. | ✅ per м.п. |
| Монтаж укосів із гіпсокартону | **м.п.** | ГКЛ | ⚙ slope width — the prompt own example |
| Облаштування ніші ГКЛ з підсвічуванням | **м.п.** | — | ⚙ section |
| Обшивка інсталяції в т.ч. отвори | шт | — | ❔ a fixed set is possible |
| Ремонт ділянки конструкції з гіпсокартону | m² | ГКЛ (запас вищий — латка); шпаклівка | ✅ low value |
| Установка люка-ревізії простого | шт | Люк-ревізія 1 шт | ✅ also PLUMBING |
| Фрезерування гіпсокартону | **м.п.** | — | ∅ |

#### Звукоізоляція та утеплення

| Position | Unit | Derives | |
|---|---|---|---|
| Безкаркасна звукоізоляція стелі | m² | — | ❔ needs a product decision |
| Безкаркасна звукоізоляція стін | m² | — | ❔ also PAINTER |
| Герметизація швів стиків герметиком | **м.п.** | Герметик акустичний ~0,02–0,03 л (уп. 0,6 л) | ✅ also PAINTER |
| Звукоізоляція стін мінеральною ватою | m² | Мінвата 1,05 m² | ✅ also PAINTER |
| Каркасна звукоізоляція (ГКЛ в два слоя) стелі | m² | ГКЛ ×2; CD/UD; підвіс; вата; TN25+TN35 | ✅ overlap — §19.2 G |
| Каркасна звукоізоляція (ГКЛ в два слоя) стін | m² | same | ✅ overlap — §19.2 G |
| Монтаж акустичної мембрани | m² | Мембрана 1,05 m² | ✅ |
| Монтаж ущільнювальної стрічки на профіль | **м.п.** | Стрічка ущільнювальна 1,05 м.п. | ✅ per м.п. |
| Утеплення ГКЛ стіродуром | m² | XPS 1,03 m²; дюбель-парасолька ~5 шт | ✅ |
| Утеплення мінватою в один шар | m² | Мінвата 1,05 m² | ✅ |

#### Оздоблення під фарбування

| Position | Unit | Derives | |
|---|---|---|---|
| Базове шпаклювання під скловолокно | m² | Шпаклівка 1,0–1,2 кг | ✅ also PAINTER |
| Вологе обезпилювання поверхні | m² | — | ∅ |
| Заповнення стиків ГКЛ паперовою стрічкою високої щільності | **м.п.** | Стрічка паперова 1,05 м.п.; шпаклівка для стиків 0,3–0,5 кг | ✅ |
| Заповнення та армування стиків ГКЛ | **м.п.** | Серпянка 1,05 м.п.; шпаклівка для стиків 0,3–0,5 кг | ✅ |
| Криючий ґрунт-наповнювач | m² | Ґрунт-наповнювач 0,15–0,20 л | ✅ |
| Локальне дефектування | m² | — | ∅ negligible |
| Мікрошліфування дефектів | m² | — | ∅ negligible |
| Обезпилення поверхні | m² | — | ∅ also PAINTER |
| Поклейка склополотна | m² | **Склополотно 1,1 m²**; клей 0,2–0,3 кг | ✅ also PAINTER |
| Проклеювання склополотном примикань і кутів | **м.п.** | **Склополотно ~0,15 m²** (смуга ~15 см) | ✅ §19.2 B |
| Шліфування під скловолокно/склохолст | m² | Сітка абразивна (optional) | ∅? also PAINTER |
| Шліфування стиків ГКЛ | **м.п.** | — | ∅ negligible |
| Шліфування стін/стель (фінішне) | m² | Сітка абразивна (optional) | ∅? also PAINTER |
| Шпаклювання та шліфування гіпсокартону (без склополотна) | m² | Шпаклівка 1,0–1,2 кг | ✅ |
| Шпаклювання фінішне (2–4 рази) | m² | Шпаклівка фінішна 1,0–1,2 кг | ✅ also PAINTER |

#### Надбавки

| Position | Unit | Derives | |
|---|---|---|---|
| Монтаж на висоті (більше 3м) | **%** | — | excluded from numerator AND denominator |

### 19.2 What the audit changes

**A. The м.п. blast radius is 15 of 56, not a handful.** Every one of them needs a norm written per
running metre. The v1 bug (a per-m² norm on a м.п. position) would have over-bought on 27 % of the
trade.

**B. The pair the prompt named is real and both halves ship.** «Поклейка склополотна» is m²,
«Проклеювання склополотном примикань і кутів» is м.п., and the first one's `description` says so in
the catalog itself. Same material, ~7x apart per unit (1,1 m²/m² vs 0,15 m²/м.п.).

**C. The «перегородки 2 сторони» basis — asked, and ANSWERED (2026-09-08).** Prompt B step 4 writes
its table «На 1 м² **обшивки**», while the catalog name and the price (800 ₴/m² against 430 ₴/m² for
the one-sided «Монтаж гіпсокартону на стіни») both read as «the m² is the partition face». 2x on
every sheet in four positions, so it went to the master. His ruling: **the figure he typed is the
sheathing area, both sides already in it — multiply nothing.**

> «нічого множити на 2 не треба, якщо майстер вказав 20 м2, то це має бути метраж всієї перегородки
> з обидвох боків і це його вже проблема якщо він помилився, ми тут не вгадуємо, а використовуємо
> дані які він же сам ввів»

The rule is bigger than this position and belongs in the engine's preamble: **the calculator never
reinterprets a quantity the master typed.** What it changes in V127:

- ГКЛ = **1 m²/m² per layer** against the entered figure. No ×2, no ×4.
- The frame norms (CW/UW, TN25, ущільнювальна стрічка) are written per m² of **sheathing** as well —
  about half of any handbook figure quoted per m² of partition face. This is not a second guess, it
  is the same ruling applied consistently: one basis for every norm on the line.
- **Layers default to 1**, and become 2 only where the position name says so («в 2 шари», «ГКЛ в два
  слоя»). Same ruling, second half: **whatever the calculator assumed is shown on the result screen
  and is editable there** — announced, never silent. That is §15 applied to an assumption rather
  than to a parameter, and it is what keeps «we do not guess» true when we did have to pick a number.

**D. 5 of the 6 `PIECE` positions cannot yield a norm at all.** Arch, decorative elements, bath
screen and installation cladding are size-driven, and openings/milling consume nothing. They belong
in the coverage report **by construction**, not by omission — worth wording differently there so the
master does not read it as a gap we forgot to fill.

**E. The parameter list is longer than «периметр».** Prompt B names the room perimeter (for UD).
The audit adds a **box/niche section** (4 positions: короб x2, ніша x2), a **slope width** (укоси)
and a **screed-fill thickness** (суха збірна підлога). Recommendation: cut 2 ships the m²-sheathing
family and the joints family; the box/slope/niche family goes to the coverage report from day one
with a «ввести» button, which is exactly the §15 rule and costs nothing extra.

**F. 27 % of DRYWALL is filed under another trade, measured.** 15 of the 56 positions also exist
under another trade — PAINTER (11), DEMOLITION (2), PLUMBING (1), plus «Установка люка-ревізії
простого». V118 stores each once under whichever trade claimed it first, so for these a lookup on
the position's trade misses whenever the row was filed under the other one. The prompt's warning is
not hypothetical.

> **Superseded by §25.** The fix this measurement suggested — fall back to name+unit alone — is the
> one that shipped in cut 2 and is now removed: it answered for positions of EVERY trade, not just
> these 15. **V132 re-files the rows instead**, which is where the 27 % actually belongs.

**G. Two positions can double-count the same ceiling.** «Каркасна звукоізоляція (ГКЛ в два слоя)
стелі» carries the whole sheathing stack, and a master who also lists «Монтаж гіпсокартону на стелю
рівну» for the same ceiling gets the sheets twice. Nothing in the model can tell these apart, and it
should not try — but the coverage widget should not hide it either. Open for the master.

**H. The denominator needs a third exclusion.** Prompt B excludes `PERCENT` and existing materials.
The audit finds **12 positions that legitimately consume nothing** (demolition x2, dust removal x2,
sanding x3, milling, openings, local defecting, micro-sanding). Listed under «не враховано» they are
exactly the noise the PERCENT rule was written to prevent — the widget would read «норми знаємо для
33 з 55» when coverage is in fact complete. A deliberate «no material» verdict has to be a recorded
state, not an absence. Cheapest shape: a `material_norm` row with `material_id IS NULL` meaning
«перевірено, матеріалу не потребує», which the engine counts as covered and the report never lists.

### 19.3 Blocker for the rest of prompt B

Prompt B's stated precondition — `EstimateNextStep`, the next-step block in the estimate editor it
launches the calculation from — **does not exist**. Nothing under `majstr-pwa/src` matches the name
or the idea; the editor ends at the items board. The prompt says to stop and report, so the entry
point is the master's decision before step 2 is written.

## 20. Cut 2, steps 2-6: the calculator itself (2026-09-08)

**Status:** built, green, **uncommitted**. Migration **V127**. Backend `./gradlew build` — 1305
tests, 0 failures. PWA gate (lint → `tsc -b` → `typecheck:tests` → vitest → `vite build`) — green,
889 tests. Version bumped `1.41.0` → `1.42.0`.

### 20.1 What shipped

**V127 — the DRYWALL norms.** Five sections in one migration: the schema the norms needed
(`material.code` + `ux_material_code`, `material.package_name` paired with `package_size` by a
CHECK, `material_norm.material_id`/`qty_per_unit` made NULLable behind a paired CHECK, and
`material_norm.basis` ∈ {`QUANTITY`,`PERIMETER`}); a 33-row material dictionary keyed by `code`;
the norms themselves, joined to the dictionary through `code` rather than a name; **11 «checked,
consumes nothing» verdict rows** (§19 H — a deliberate no-material answer is a RECORDED state, so
the coverage report never lists a demolition or a sanding position as a gap); and a self-check that
raises if any norm names a DRYWALL position the catalog does not ship.

**`MaterialCalculatorService`** — the engine. Four rules, all of them load-bearing:

- **the norm is found by (name, unit) and the POSITION's trade decides which answer may be used**
  (`normsFor`; a norm filed under no trade answers for anyone) — `estimate_items.trade` is nullable
  by design (V125), and V118 filing a shared position under one trade only is handled by **V132**,
  not by dropping the filter. (Cut 2 shipped a name+unit fallback here; **§25** removed it.);
- **a norm's unit is read off the POSITION and nothing is converted** — a м.п. norm multiplies a м.п.
  quantity. This is the v1 bug, and it has its own test in both suites;
- **a `PERCENT` line and a material the master already listed enter NEITHER the numerator NOR the
  denominator** of the coverage ratio;
- **a `PERIMETER` norm is ASKED FOR, never derived from the area** (§15), and is applied ONCE per
  estimate — the larger of the competing demands wins, so a ceiling and a wall lining share the one
  perimeter instead of buying it twice.

**Rounding runs UP**, to a whole package where the material has one (`package_size` × ⌈demand ÷
size⌉) and to a whole unit where it does not. Sheets are the exception the habit decides: a master's
`WASTE_PERCENT`/sheet-size preference overrides the dictionary's package for `GKL_SHEET*`.

**The result screen** (`MaterialCalculatorPage`, route `/estimates/:id/materials`). Every number is
editable, every row unfolds its own arithmetic, the waste toggle is 5/10/15 %, and the heading says
«орієнтовно» with the §18 preamble under it — «Орієнтири для звірки з майстрами, не істина». Two
exits: → the object's shopping list, → the estimate as MATERIAL lines (behind a confirm, because
they land at 0 ₴ by the V81 rule).

### 20.2 Three things that are easy to get wrong later

- **The waste toggle re-asks the SERVER**; it does not scale the numbers on screen. Rounding runs up
  to a whole package, so 5 % and 10 % of one base are not a factor apart. Changing waste or the
  perimeter also **drops the master's manual overrides** — a figure typed against the old numbers
  would otherwise leave the screen claiming to be what he decided.
- **The coverage report NAMES its gaps.** «Порахували 8 з 11» with the three hidden is precisely the
  failure this screen exists to avoid; the gap list is one tap away and the block turns amber.
- **`MaterialNormLookupIntegrationTest` must clean up after itself.** The Testcontainers schema is
  shared, and a leftover DRYWALL norm for a position no catalog ships reads as an orphan to
  `everyDrywallNormFindsItsPositionInTheShippedCatalog` — which is the guard that a later catalog
  rebuild renaming a DRYWALL position cannot break the norms silently (the migration's own
  self-check only runs at apply time).

### 20.3 The entry points

The §19.3 blocker is gone: `EstimateNextStep` shipped with the `estimate-editor-redesign` iteration,
and its secondary row's 🧮 action now opens the calculator instead of the shopping list. A second,
quieter entry point sits in the expanded mobile summary sheet — «🧮 Порахувати матеріали з робіт»,
shown only when the estimate has works and **no** material lines at all, which is the moment a
0 ₴ materials figure is worth acting on.

### 20.4 Not verified

The mobile layout was **not** checked in a live browser this round (it needs a running dev server,
a logged-in session and a DRYWALL estimate). The screen is built to the same mobile-first rules as
the shopping list — `max-w-xl`, a single column, 44 px minimum tap targets, a fixed action bar with
`env(safe-area-inset-bottom)` and `pb-40` clearing it — but that is design intent, not a measurement.

## 21. «Моя норма, назавжди» — the coefficient is the master's (2026-09-08)

**Status:** built, green, **uncommitted**. **No migration** — see below. Backend `./gradlew build`
green. PWA version `1.42.1` → `1.43.0`.

The screen already said every number was editable, and the master read that literally: «треба
дозволяти майстру міняти формулу». The quantity was editable; the **rate behind it** was not, so a
master who disagreed with 0,12 л/м² had to re-type the answer on every estimate, for ever.

### 21.1 No migration was needed, and that is the interesting part

V126 already wrote `owner_id` **inside** the natural key:

```
CONSTRAINT ux_material_norm UNIQUE NULLS NOT DISTINCT (owner_id, trade, name_key, unit, material_id)
```

so a master's row legitimately sits **beside** the shipped one it hides, rather than colliding with
it. Two integration tests now pin exactly that — `aMastersOwnNormCoexistsWithTheDefaultItWasForkedFrom`
and `twoOwnNormsForTheSamePositionAndMaterialAreRejected` — because the day someone "tidies" that
constraint, personal norms break with a 500 on an ordinary save.

### 21.2 Fork on write, exactly like V113

`MaterialNormService.saveOwn` is the one door. A shipped norm is **copied** into an owned row
(trade, `name_key`, unit, material, basis, waste, sort order — everything but the coefficient) and
the copy is written; the shared row is never touched, so one master's correction cannot move
another's arithmetic. Two rules follow, both the same as `template_default_override`:

- **the write answers with the norm it LANDED on**, whose `id` is the fork's, not the URL's;
- **addressing the default afterwards keeps resolving to that same copy** — `saveOwn` looks for an
  existing fork by natural key first. This is not a nicety: a master who opened the calculator
  before he edited is still holding the default's id, which is the ordinary case, and forking
  blindly would hit `ux_material_norm` and 500 on him.

`DELETE /api/material-norms/{id}` restores the shipped figure by deleting his fork — through either
id, and a no-op when he has none.

**A norm with `material_id IS NULL` is refused (400 `MATERIAL_NORM_INVALID`).** Those are the 11
V127 «checked, consumes nothing» verdicts; there is no coefficient to correct.

### 21.3 Resolution is a READ-path collapse, not a second lookup

The lookup itself (the key, and after §25 the trade filter) is untouched and still default-only. The
calculator instead asks `findAllByNameKeysForOwner(keys, ownerId)` — shipped rows **plus** this
master's — and `preferOwn` collapses them by natural key, the owned row winning. It preserves the
encounter order, so a corrected coefficient never reshuffles the screen.

`MaterialSourceLine` carries `normId` + `ownNorm` so the arithmetic line is itself the edit surface.

### 21.4 The PWA half

Inside «Показати розрахунок» each source line gets «Змінити норму» → a coefficient field,
«Зберегти як мою норму», and (only where he already has one) «Стандартна». A saved norm marks the
line «моя норма».

⚠️ **Saving re-asks the server**, exactly like the waste toggle and for the same reason: rounding up
to a whole package does not commute with scaling. It also drops his manual quantity overrides,
which were typed against the old figures.

### 21.5 Not verified

The mobile layout was **not** checked in a live browser this round. The editor is a single column
inside the existing math block, 44 px tap targets, buttons wrapping on `flex-wrap` — design intent,
not a measurement.

## 22. Checking V127 against the manufacturers — V130 (2026-09-11)

The master asked to look at Knauf's own calculator and then: «прав норми і чекни чи є позиція під
багаторівневу».

### 22.1 What Knauf actually publishes

**There is no official Ukrainian Knauf calculator.** `knauf.ua` redirects to a `knauf.com/uk-UA`
page that has none, and that page never reaches `document_idle`, so it cannot be read by automation
either. The one official calculator is on the Russian site, which is network-unreachable from here
(`ECONNREFUSED`). Norm tables on Ukrainian dealer sites are the usable evidence; one common source
publishes them as **images**, so only the text-table dealers are citable at all.

Therefore: V130's figures are **dealer compilations of the handbook**, one rung weaker than a
manufacturer datasheet, and the header of the migration says so. Where our figure already sat on
Knauf's own datasheet (Perlfix adhesive), the datasheet won and V130 changed nothing.

### 22.2 What held, and why the screws only LOOK wrong

Confirmed unchanged: CD **2.9** m/m² on a ceiling and **2.0** on a wall, CW **2.0** per m² of
partition face, finishing putty **1.2** kg/m², deep primer **~0.1** l/m².

The screw counts appear to be 2× off and are not: ours are per m² of **board**, the tables' per m²
of partition **face**. Same arithmetic as §19.2 C — the denominator, not the number.

### 22.3 The four defects

| # | Position(s) | Defect | V130 |
|---|---|---|---|
| 1 | стеля рівна / зі скосами / каркасна звукоізоляція стелі | **no connectors at all** — a CD frame has crossings and long runs; neither material existed in the dictionary | `CONNECTOR_CRAB` **1.7**, `PROFILE_CD_EXTENDER` **0.2** |
| 2 | монтаж ГКЛ на стіни / каркасна звукоізоляція стін | **no hanger on a wall frame** — CD was normed with nothing fixing it to the wall | `HANGER_DIRECT` **1.3** |
| 3 | all four partition positions | **no дюбель-цвях** — nothing fixed UW to floor and ceiling | `DOWEL_NAIL` **0.75** |
| 4 | all four partition positions | **UW ~50 % high** (0.5/m² sheathing = 1.0/m² face vs the tables' 0.7) | 0.5 → **0.35** |

Defect 2 is an **omission, not a judgement**: line 622 of the §19 audit already listed «підвіс
прямий» for «Монтаж гіпсокартону на стіни»; it simply never reached V127's INSERT. Defect 1 never
appeared in the audit at all.

Defect 4's arithmetic: a partition of length L and height 3 m takes 2L of track for 3L of face →
0.67 ≈ 0.7 per m² of face → **0.35** per m² of sheathing. The over-buy came from halving a figure
that had already been halved.

### 22.4 Two figures deliberately left alone

- **`GYPSUM_GLUE` 5.0 kg/m²** — the dealer table says 3.5, Knauf's Perlfix datasheet ~5.0. Ours is
  already the manufacturer's number.
- **`PUTTY_JOINT` 0.4 kg/м.п.** — on the high side (handbook ~0.3), but it is bought in 25 kg bags
  and the calculator rounds up to a bag, so on any realistic joint length the master carries the
  same bags out of the shop. Left for his word.

### 22.5 Three rules the migration obeys

- **Every coefficient write is filtered `owner_id IS NULL`.** A master who forked a norm under §21
  keeps his number; V130 corrects only the shipped default. `sort_order` **is** shifted on forks too
  — it is presentation, not his coefficient, and a fork left behind would interleave oddly.
- **`sort_order` is made room for, not appended to.** A crab belongs beside the profiles it joins, so
  ranks ≥ 4 shift by +2 on the three ceiling positions and +1 on the two wall ones; the partition
  dowel appends at each position's `max + 1`. A self-check refuses the migration on a shared rank.
- **No new catalog position, so no ranking block.** V130 inserts no `catalog_templates` row, which is
  the only thing that would oblige it to re-run V118's ranking verbatim (V120 PART 3).

### 22.6 The multi-level ceiling question — the position does not exist, deliberately

There is **no** «багаторівнева стеля» position, and its absence is a recorded decision, not a gap.
V120 (lines 70-71) rejected it in as many words: such a job **is** a flat ceiling plus a короб by the
linear metre, both of which we already sell, and one m² position on top of them **double-bills**.
V116's «Стеля з гіпсокартону» bundle independently sequences exactly that pair — flat ceiling (step
5) then прямий/радіусний короб (steps 7-8) and the niche (step 9).

What a multi-level ceiling really exposes is a **norm** gap, not a position gap: the two короб and
two ніша positions are `LINEAR_METER` and intentionally unnormed (§19 D/E — a section the position
name cannot carry), so such an estimate surfaces a coverage gap for them. Normng them needs one
parameter from the master (section w+h), which is the still-open «box / slope / niche parameters»
question. **Adding the m² position would need his explicit override of V120** — not assumed here.

### 22.7 Blast radius

Data-only; no Java and no PWA change. `MaterialCalculatorIntegrationTest`'s coverage guard counts
`DISTINCT name_key` **live** from the DB, and V130 adds norms only for name_keys V127 already
covered, so the expected figure moves with the data. Only `GKL_SHEET` is referenced by code; the two
new codes are not.

### 22.8 The gate

`./gradlew build` — **green: 1358 tests, 0 failures**. No new test was written and none was changed:
V130 is data-only, and the guard that matters (`everyDrywallNormFindsItsPositionInTheShippedCatalog`)
already reads its expected figure live from the DB. The migration's own three self-checks — every
name_key resolves to a live position, all four UW defaults carry 0.35, and all twelve new
(position, material) pairs exist — run at apply time, so a green Testcontainers boot **is** the
assertion that they passed.

Two traps this round, both worth remembering because each returned exit code 0 while doing nothing:

- **`JAVA_HOME` on this machine points at `C:\Program Files\Java\jdk-25.0.3`, which no longer
  exists** (only `jdk-25.0.4.1` and the `.jdks\ms-*` toolchains are installed). The wrapper prints
  «JAVA_HOME is set to an invalid directory» and stops. Build with
  `$env:JAVA_HOME='C:\Users\AndriyKushka\.jdks\ms-21.0.12.1'` (the toolchain is pinned to 21 anyway).
- **`./gradlew build | tail` hides that**, because a pipeline's status is the LAST command's. The
  first run of this round reported exit 0 having never compiled a line. Redirect to a log and read
  the exit code separately.

The **PWA gate was not run** and is not owed: nothing under `majstr-pwa/src` changed — only the
version string in `package.json` (`1.43.1` → `1.43.2`), which no lint, type-check or test reads.

## 23. The короб asks for its section — V131 (PWA v1.44.0) (2026-09-11)

The master: «давай норму на короб, переріз питаємо в майстра».

### 23.1 Why the box stood unnormed, and why that was already fixable

§19 D/E and §22.6 left the two короб and two ніша positions deliberately without norms: they are
`LINEAR_METER`, and the sheathing they consume depends on a figure **the position name cannot
carry** — the розгортка, width + height. V127 had already met this shape once and answered it with
a rule rather than a guess: a `PERIMETER` norm is **asked for, never derived from the area**. So
this round needed a third basis, not a new concept.

### 23.2 A box's consumption splits in two, and one half needs nothing new

Along a короб of length L run the longitudinal UD/UW track, the dowel-nails that fix it, and the
corner bead on its edges. Those scale with **L**, and V127's rule that *a norm's unit is read off
the POSITION* already handles them exactly right — they stay `QUANTITY` on a `LINEAR_METER`
position and no parameter is needed at all.

Only the sheathing, the ribs and the screws scale with the розгортка. Those become `SECTION`:

```
amount = length (м.п.) × section (w+h, m) × qty_per_unit
```

which makes `SECTION` conceptually free — it is a **per-m² norm whose m² is computed rather than
typed**. Its coefficients therefore read on the same scale as the existing ceiling/wall per-m²
figures (`GKL_SHEET` 1.0, `PROFILE_CD` 2.2), instead of being a new kind of number nobody can
sanity-check.

### 23.3 Per POSITION, not per estimate — the one real design decision

`PERIMETER` is one number for the whole estimate, larger demand winning. `SECTION` must **not**
behave that way: a прямий короб, a радіусний короб and a ніша in one estimate are three different
boxes with three different розгортки. One figure for all of them would be silently wrong for two —
precisely the failure the «asked for, never derived» rule exists to prevent. Three consequences:

- `MissingParameter` gains `estimateItemId` + `positionName` (both null for `PERIMETER`, which has
  no position to name);
- `SECTION` demands are **never merged** — no larger-wins, no single bucket;
- the PWA asks **once per POSITION, not per material**: the board and the ribs of one короб share
  one розгортка, so three missing parameters over two positions render two inputs.

### 23.4 The figure rides the GET as one scalar

`sections=<uuid>:0.4,<uuid>:1.2`. Two reasons decide the shape: `src/api/client.ts` has no
`paramsSerializer`, so a repeated param would go out as `sections[]=` and not bind; and V127's rule
that the calculation is a **GET because it stores nothing** has to survive — a POST would quietly
retire it.

`parseSections` **ignores** a malformed or unknown entry instead of answering 400. A stale
estimate-line id then simply re-asks the question, whereas a 400 would be an empty screen with no
way forward, for a figure that is optional by design.

### 23.5 What V131 writes

The CHECK widens to `('QUANTITY', 'PERIMETER', 'SECTION')`; one dictionary material is added
(`ANGLE_PERFORATED`); 17 norms land on three positions (**S** = SECTION, **Q** = QUANTITY):

| position | norms |
|---|---|
| прямий короб | `GKL_SHEET` 1.0 **S**, `PROFILE_UD` 2.1 Q, `PROFILE_CD` 2.2 **S**, `ANGLE_PERFORATED` 1.05 Q, `SCREW_TN25` 30 **S**, `DOWEL_NAIL` 4.2 Q |
| радіусний короб | `GKL_SHEET_ARCH` 1.0 **S**, `PROFILE_UD` 2.1 Q, `PROFILE_CD` 3.0 **S**, `SCREW_TN25` 40 **S**, `DOWEL_NAIL` 4.2 Q |
| ніша | the same six as прямий |

Still deliberately unnormed: «Монтаж укосів із гіпсокартону» (a slope's width is not a section) and
«Облаштування ніші ГКЛ з підсвічуванням» (it carries an LED profile whose length is a second,
different unknown).

Four self-checks run at apply time: every SECTION norm's `name_key` resolves to a live DRYWALL/WORK
catalog position under V127's normalisation; the (position, material) pairs number exactly 17; no
two norms on one position share a rank; and a box board must be `SECTION` while `PROFILE_UD` must be
`QUANTITY` — the one mix-up that would silently double- or half-buy everything.

### 23.6 A section is not a master preference

A розгортка is a property of the **object**, not of how this master works, so by `MaterialPrefKey`'s
own documented rule it must not become a preference. It is asked per estimate, every time — which is
also why the answer is not stored anywhere.

### 23.7 Blast radius

Java: `NormBasis`, `MissingParameter`, `MaterialSourceLine`, `MaterialCalculatorController`,
`MaterialCalculatorService`. PWA: `api/types.ts`, `api/materials.ts`,
`MaterialCalculatorPage.tsx`, both locale files. Two records changed signature, so the fan-out check
ran: `new MissingParameter(` / `new MaterialSourceLine(` appear at exactly **5** sites, all inside
`MaterialCalculatorService`; the only external callers of `calculate(...)` are the controller and
two test helpers (both updated); there is **no** `MaterialCalculatorControllerTest`.

One PWA bug fell out of the split: `needsPerimeter` was `(data?.parameters ?? []).length > 0`, so
the **perimeter** card would have appeared for a `SECTION` parameter. It now filters on
`p.parameter === 'PERIMETER'`, pinned by its own test.

### 23.8 The gate

`./gradlew build` — **green: 1363 tests, 0 failures** (1358 + the five new SECTION tests). The PWA
gate ran in full CI order: `npm run lint`, `npx tsc -b`, `npm run typecheck:tests`,
`npx vitest run` (**928 tests, 123 files**), `npx vite build` — every step exit 0. Version
`1.43.2` → **`1.44.0`**: a new headline capability is a minor bump.

Mobile was **not** verified live. The переріз card reuses the shipped perimeter card's structure —
stacked full-width inputs, one full-width action — which should hold at 375 px, but that is design
intent, not a measurement.

A machine trap worth recording beside §22.8's two: **the backend build and the PWA gate do not fit
in memory together** on this machine (15.6 GB, with IntelliJ, an IDE-launched Spring Boot app and a
Vite dev server resident). Run concurrently, both were killed mid-run; alone, at 2 GB free, both
pass. `./gradlew --stop` on an idle daemon is the one safe ~600 MB to reclaim — a daemon is a warm-JVM
cache, so nothing is lost.

## 24. The coverage block names trades, and a price list stops buying — PWA v1.44.1 (2026-09-11)

The master, with four screenshots of his own test estimate: «з гіпсокартону в мене одна позиція,
звідки у матеріалах стільки матеріалів + оте порахували 8 з 39 і де в перелікує демонтаж і монтае,
де воно і нащо береться, то думаю треба забрати і просто писати для якої категорії пораховано чи
якось так, бо це якось взагалі не добре». Then, fixing the wording himself: «**"Порахували
матеріали для: Гіпсокартон, Малярні роботи" - лишаємо отак**».

### 24.1 One screenshot, three independent causes

Read as one complaint it looks like a rendering choice. It was not — three separate things met on
that screen, and only the first is a bug:

1. **`workLines` had no quantity filter.** Every WORK line entered the calculation, including the
   price-list rows masters keep inside an estimate at 0 («Штукатурні роботи (від) — 0 м²»). On his
   own estimate **31 of 39 lines were exactly that.** Each one reached a norm, entered the
   denominator, and produced a material row of zero — «Картон захисний — 0 м²», «Шпаклівка фінішна
   — 0 кг». That is «звідки у матеріалах стільки матеріалів».
2. **The denominator was every WORK line**, while V127 ships norms for DRYWALL `name_key`s only. A
   mixed estimate therefore *could not* read better than «8 з 39» — the ratio was measuring the
   catalog's norm coverage, not anything the master can act on.
3. **Drywall materials under a painter's position** — read at the time as correct by design: the
   lookup's second rung was name+unit only (V127, §19 F's measured 27 %), so a шпаклівка position
   typed PAINTER resolved to a DRYWALL norm on purpose. **That reading was wrong, and §25 fixes
   it** — the master looked at the same screen once more and ruled «оце все з малярки не має взагалі
   попадати». What §24 shipped correctly is that this cannot be explained by a ratio.

### 24.2 A ratio invites arithmetic nobody can check

«8 з 39» is a claim the master is entitled to verify, and verifying it means asking after each of
the 31 — which is what the amber gap list then answered, one name at a time, including «демонтаж» и
«монтаж» rows whose norms deliberately consume nothing. So the number produced the list, and the
list produced the question. His ruling removes both: **what WAS counted is named by trade; what was
not stays silent.** One calm line replaces a figure plus 31 names.

This is also why the fix is not «filter the gap list better». A shorter list is still a list of
things the screen failed at, on a screen whose whole job is «скільки купити».

### 24.3 The trade shown is the POSITION's, never the norm's

The one real design decision. V127 ships DRYWALL norms only, so reading the trade off the **norm**
would have answered «Гіпсокартон» for a painter's estimate — the very confusion of cause 3 above,
printed as a label. The trade is therefore read off `estimate_items.trade`, the position the master
himself typed. §25 kept this rule and removed the confusion under it: a position of another trade no
longer resolves at all, so the two can no longer disagree.

`estimate_items.trade` is **nullable by design (V125)**, and NULL ≠ `Trade.OTHER`. Folding them
together would rewrite a deliberate distinction for a caption's convenience, and silently omitting
untyped positions would understate what was counted. So `MaterialCoverage` carries both shapes:

```java
public record MaterialCoverage(List<String> trades, boolean otherWorks) {}
```

`trades` is a `LinkedHashSet` drained in estimate order, so the line reads in the order he wrote
the work in and names each trade once. `otherWorks` renders as «інші роботи» after them. Nothing
counted, and the line becomes «Норм для цього кошторису ще немає — матеріали впишіть самі.» — a
sentence with an instruction in it, not a zero.

### 24.4 Only a buying decision is counted

The filter that produced the bug is now the rule the whole service states once: a `PERCENT`
surcharge, a material the master already listed, and **a line whose quantity is still 0** are none
of them a decision to buy. The third was the live one:

```java
.filter(i -> i.getType() == ItemType.WORK)
.filter(i -> i.getUnit() != Unit.PERCENT)
.filter(i -> i.getQuantity() != null && i.getQuantity().signum() > 0)
```

Downstream this makes `item.getQuantity()` non-null and positive by construction, which is the one
place the old code could multiply a norm by nothing and still print a row.

**Unit-test trap it created:** `given()` registered the norm-repository stub whenever a non-PERCENT
WORK line existed. Under STRICT_STUBS a 0-quantity-only test then fails the *build* on an unused
stub, because the new filter means no lookup happens at all (`normsByKey` returns early on an empty
key list). The helper mirrors the service filter, quantity included.

### 24.5 Availability collapses to one boolean

`MaterialAvailabilityResponse` had three fields; nothing read the other two. It is now
`(boolean available)`. The reason is not tidiness — a second counting surface that can disagree
with the screen is exactly the class of bug just removed, and the probe's only job is to decide
whether «Матеріали» appears at all (V129). It answers with the calculation's own unit rule: a norm
in another unit is not an answer for this line, and nothing is converted.

### 24.6 Deleting the gap list nearly deleted a real guard

`CoverageGapKind.AMBIGUOUS` was a DTO value the integration test asserted **absence of** — its
`doesNotContain(AMBIGUOUS)` was the only thing pinning that the shipped catalog holds no
name+unit two trades both norm. Deleting the enum would have deleted that check silently, leaving a
green build. It is re-expressed as two direct SQL invariants over `material_norm` + `catalog_templates`:
no shared norm whose position the catalog no longer ships (**orphaned**), and no (name_key, unit)
with `count(DISTINCT trade) > 1` (**ambiguous**). Strictly stronger than what it replaced: V127's
own apply-time self-check cannot catch a *later* catalog rebuild renaming a position out from under
a norm, and the whole-catalog run then asserts the catalog must buy something.

### 24.7 Blast radius

Java: `MaterialCoverage` (rewritten), `MaterialAvailabilityResponse` (collapsed),
`MaterialCalculationResponse` (javadoc), `MaterialCalculatorService`; **deleted** `CoverageGap` and
`CoverageGapKind`. PWA: `api/types.ts`, `MaterialCalculatorPage.tsx`, both locale files, the page
test. The `coverage`/`coverageHint`/`showGaps`/`hideGaps` keys are **gone**, replaced by
`coverage`/`coverageOther`/`coverageNone`, so a re-added `t('materials.coverageHint')` reddens
`i18nKeys.test.ts`. Trade labels needed nothing: `t('trades.' + code)` already ships all of them,
and it is a dynamic key that `i18nKeys.test.ts` deliberately skips. A repo-wide grep for
`CoverageGap|coverage\.|coverageHint|showGaps|hideGaps` found no other consumer
(`ProjectImportSheet`'s `coverage.total` is an unrelated measurement shape).

### 24.8 The gates

`./gradlew build` — **green: 1367 tests, 0 failures** (1363 + three new unit tests + one new
integration test). The PWA gate ran in full CI order: `npm run lint`, `npx tsc -b`,
`npm run typecheck:tests`, `npx vitest run` (**930 tests, 123 files**), `npx vite build` — every
step exit 0. The PWA delta reads as a check on the change: 928 → 930 over an unchanged 123 files
is exactly one deleted gap test against three new ones.

Version `1.44.0` → **`1.44.1`**: a fix and a wording change on shipped work is a patch.

Mobile was **not** verified live. The block now renders one short `<p>` in the same card that
previously held a ratio, a toggle and a 31-row amber list, so 375 px can only get easier — but that
is arithmetic about the markup, not a measurement.

A note for the next round: the coverage line no longer says anything about a position it could not
answer for, which is the intended behaviour and also removes the only on-screen hint that a norm is
missing. If masters start asking «а чому тут нічого», the answer is a per-position hint at the line
itself, not the return of a global list.

## 25. A norm answers for its own trade, and V132 re-files the shared positions — V132 (2026-09-11)

§24 fixed the screen and left the arithmetic under it alone. The master looked once more, at the
same estimate, and named what was actually wrong:

> «ну дивись суто в нашому каталозі із гіпсокартонних робіт - тільки вирізка отворів, **оце все з
> малярки не маю взагалі попадати** тай і по гіпсокартону в нас немає ніяких матеріалів, то щось ти
> забагато переш у калькулятор»

He is right, and §24.1's cause 3 — «drywall materials under a painter's position are correct, not a
leak» — is the claim that was wrong. Three changes, approved together («давай всі три»).

### 25.1 The POSITION's trade decides which norms may answer

Cut 2 shipped a two-rung lookup: the position's trade first, then **name and unit alone** whenever
that missed. The fallback was written for the 15 shared positions §19 F measured, but it does not
know that is what it is for — it answers for a position of **any** trade. On his estimate, one
drywall line («Вирізка отворів») among 38 painter and tiling ones, the fallback resolved the
painting section's шпаклювання and грунтування positions against V127's DRYWALL norms and sold him
картон, ґрунтовку and шпаклівку he had never asked to buy.

The rule now: **a norm answers for a position when its trade IS the position's, or when the norm
carries no trade at all** (general — nothing in V126's schema or V127's data forbids it, and the
lookup test already inserted such rows). A position whose own trade is NULL — nullable by design
(V125) — still takes every candidate, because there is nothing to disagree with; the pre-existing
`spansSeveralTrades` guard still refuses to guess when the candidates for such a line disagree
among themselves.

Both filters (unit, trade) live in **one** private helper, `MaterialCalculatorService#normsFor`, and
`calculate` and `availability` both call it. That is deliberate: §24.5's invariant «the probe and
the calculation share one lookup» has to stay literally true, not duplicated in two places that can
drift. `rung1` is gone, and so is the ladder — the repository's two queries stay (they pin the key's
shape and the fork tests read through them), but **which norms may be USED is now a service
decision, not a query result**.

### 25.2 «Consumes nothing» is a complete coverage answer and NO answer for the button

The second half of what he reported: «по гіпсокартону в нас немає ніяких матеріалів». His one
drywall line is a demolition one, and every norm for it carries `material_id IS NULL` — V127's
recorded «checked, consumes nothing» verdict (§19 H). `availability` asked only «did any norm
answer», so it answered `true`, the PWA showed «Матеріали», and the screen opened with nothing on
it.

So the probe carries **one condition the calculation does not**: the norm must actually buy
something (`getMaterial() != null`). The trade is still counted in the coverage line — the work WAS
checked, it just consumes nothing — which is exactly the asymmetry V127 introduced those rows for.
Pinned by `aConsumesNothingVerdictIsNotAnAnswerForOfferingTheScreen`: `available()` false, coverage
`containsExactly("DRYWALL")`.

### 25.3 V132 — the 10 shared positions are STORED under DRYWALL for a master who has the trade

With 25.1 in place, a DRYWALL master whose «Шпаклювання фінішне» sits in `catalog_items` under
PAINTER gets no materials for it. That is not a corner case: **V118 stores one row per (owner, name,
type, unit)**, and V116 PART 7 copies 10 positions into DRYWALL that PAINTER also ships — so for a
two-trade master the row lands under whichever the array named first, and V118's array puts PAINTER
before DRYWALL. Deterministically the wrong one, for every DRYWALL+PAINTER master.

This is not «re-filing only moves the problem» (V118's own finding): **display is unaffected**,
because `sharedTrades` reads the library, not the stored row, so the position keeps appearing under
both trades with each trade's own category. What changes is the one thing that reads the STORED
trade — the calculator, after V125 made `estimate_items.trade` derive from it.

Shape decisions worth keeping:

- **The 10 names are DERIVED from the library, not hardcoded.** A self-join of `catalog_templates`
  on (`lower(trim(name))`, type, unit) where one side is DRYWALL and the other is not. Ten Ukrainian
  string literals would silently stop matching the day a position is renamed — the V123 trap.
- **It cannot collide.** `ux_catalog_items_owner_name_type_unit` does not contain `trade`, so
  flipping the column can never hit the unique index.
- **Only owners who HAVE the DRYWALL trade** are touched (`EXISTS` over `user_trades`), and only
  rows with `custom_trade_id IS NULL` (V91's invariant).
- **`source` is deliberately not filtered on.** V79 backfilled it by a heuristic, so a LIBRARY row
  of an early master may read MANUAL; the (name, type, unit) + trade-pair match is the narrower
  filter anyway.
- **V118 PART 4's renumbering is re-run verbatim**, because the category moves with the trade and
  `sort_order` ranks by category.
- **No row is created or deleted** — §5b asserts `count(*)` is unchanged against a `v132_before`
  snapshot, beside four outcome invariants (nothing shared still filed under the other trade for a
  DRYWALL owner, gapless 0-based `sort_order` per owner, V91's CHECK, and no unsigned line left on a
  non-DRYWALL trade over the re-filed names).

### 25.4 The fourth piece: an estimate line's trade is a SNAPSHOT, and a SIGNED one keeps it

`estimate_items.trade` was resolved once, when the line was added (V125). Re-filing the catalog does
not reach back into it, so §4 of the migration re-derives it the same way `resolveTrade` does — and
**only `WHERE e.status <> 'SIGNED'`**.

A signed estimate therefore keeps its stale PAINTER snapshot, and the calculator will still answer
nothing for such a line. That is immutability doing its job, not an oversight: the client signed
that document, and we do not rewrite a field inside it to improve a shopping list. A master who
needs materials for a signed estimate has the duplicate path, whose lines resolve fresh.

### 25.5 Blast radius

Java: `MaterialCalculatorService` (`normsFor` replaces `rung1`; `availability` gains the
buys-something condition), and javadoc only in `MaterialNormRepository`, `MaterialNorm`,
`MaterialCoverage`, `MaterialNormService`. SQL: `V132__shared_positions_filed_under_drywall.sql`.
Tests: `MaterialCalculatorServiceTest` (`aTradeDisagreementDoesNotLoseThePosition` **inverted** into
`aNormFromAnotherTradeIsNotAnAnswerForThisPosition`; a new
`aNormWithNoTradeAnswersForAPositionOfAnyTrade`; the coverage test's two painter positions re-filed
under PAINTER so «trades named once each in estimate order» still asserts what it meant),
`MaterialCalculatorIntegrationTest` (the probe/coverage asymmetry above),
`MaterialNormLookupIntegrationTest` (comments and two test names — its broad results are the QUERY
working, and reading them as the engine's answer is how this bug got written).

**No PWA change.** The screen already renders whatever the server counted; it now counts less.

**Docs corrected rather than appended to**, because the old wording was a load-bearing claim and is
now false: CLAUDE.md's two bullets (and «latest is V129» → V132),
`docs/architecture-index-detail.md`'s shopping-list bullet, `docs/iteration-shopping-list.md`, and in
this file §19.2 F (with a superseded note — the measurement stands, the fix it suggested does not),
§20.1's first invariant, §21.3's heading, §24.1's cause 3 and §24.3. V126's own SQL comments are
left alone: an applied migration is never edited.

### 25.6 The gate

`./gradlew build` — **green, exit 0: 1369 tests, 0 failures, 0 errors** (1367 + two new: one unit
test for the general no-trade norm, one integration test for the probe/coverage asymmetry). The
inverted test and the two renamed lookup tests change no count, which is itself the check that
nothing was dropped rather than rewritten.

**V132's own assertions ran as part of it.** Its five self-checks fire at apply time, so a green
Testcontainers startup IS the proof they passed — the same reasoning V130 and V131 recorded.

**The PWA gate was not run, and does not owe a run:** nothing under `majstr-pwa/src` changed. The
server counts less; the screen renders whatever it is given. Version `1.44.1` → **`1.44.2`** — a
correction to shipped behaviour is a patch.

Mobile was **not** verified live, and here that is nearly vacuous: no markup changed, and the
visible difference is fewer rows and a shorter coverage line.

## 26. The datasheet round, and two review items riding with it — V133 (2026-09-15)

Cut 3 of the calculator, from the master's own deeper norm research (`NORMS-SUMMARY.md`), scoped by
him to **гіпсокартон only**: «беремо до уваги тільки гіпсокартони». Three review items from the
2026-09 backlog (B-17, B-27, B-30) travel in the same migration because they are one round, not
because they are related — the header of `V133__drywall_norms_from_datasheets.sql` says so outright.

### 26.1 The screws were wrong everywhere, and wrong in ONE direction

V127 seeded fasteners from a general handbook. Against the actual system sheets they were the one
family wrong across the board: ~30 per m² is roughly a factory figure for screwing at **100 mm on
every rib**. Knauf, Rigips and Siniat all screw the **field** of a board at **250 mm** and densify
only the **edges** — which halves the count.

That a whole family moved in one direction is the reassuring part, not the alarming one: a mistake
with a single cause (one wrong spacing assumption) is a mistake you can correct in one pass. Ten
figures moved; §19.2 C's denominator rule and §22.2's «screws only LOOK wrong» both still hold, and
neither was what was wrong here.

The partition figures look implausibly small beside the wall ones (12 against 14, 5 against 7) and
are not typos: **rule 1** (V127 decision 1, master's ruling 2026-09-08) still governs — the m² typed
on a «перегородки 2 сторони» position is SHEATHING area with both faces already in it, so every
manufacturer per-partition figure is halved for us. The inner layer of a two-layer build also takes
FEWER short screws than a single layer: it is only tacked, and the outer layer's longer TN35 does the
holding.

### 26.2 Two conflicts the sources do not settle, and what we chose

Both are recorded in the migration header, because the next person to read a datasheet will hit them
again and the reasoning is not recoverable from the numbers.

**(a) The ceiling frame — keep V130's shape.** Knauf D113 hangs a two-level frame on **0,7** direct
hangers per m² *because* it also carries cross connectors; Siniat's single-level frame has no
connectors and needs **3,0**. Both are internally correct, and **mixing them buys the ceiling
twice**. V130 already shipped the Knauf shape in full (0,7 `HANGER_DIRECT` + 1,7 `CONNECTOR_CRAB` +
0,2 `PROFILE_CD_EXTENDER` + 1,6 `DOWEL_NAIL`), so it stays — with an explicit instruction in the
header: *do not raise the hangers to 3,0 without deleting the connectors in the same migration.*

**(b) Partition `DOWEL_NAIL` — keep 0,75.** The band is 0,45–0,75 for us; a deeper reading argues
0,5. Dowels are bought in 100-piece packs the calculator rounds up to anyway, so churning every
master's list for ~0,25/m² buys a difference no shop counter would notice. Same reasoning V130 used
to leave `PUTTY_JOINT` alone — and §26.3 is where that reasoning ran out.

Left alone deliberately: радіусні конструкції `SCREW_TN25` **35**. No manufacturer publishes a figure
for a bent partition; 35 is denser than the straight 12 on purpose (a curve is screwed at ~100 mm),
and it stays flagged as **our own estimate**, matching `NORMS-SUMMARY`'s «залишити з приміткою».

### 26.3 Where «the calculator rounds up anyway» stops being true

V130 kept `PUTTY_JOINT` at 0,4 kg/м.п. on exactly that argument. It is true for one room and **wrong
for a flat**, where the difference is a whole 25 kg bag. Published: Rigips 0,18, Siniat UA 0,25,
ready-mixed Semin/Kreisel 0,36 → **0,3**, the middle of the band.

The rounding argument is sound only while the error is smaller than the package. It is a reason to
tolerate imprecision, never a reason to keep a figure you now know is wrong.

### 26.4 The gap: every frame we sell, and nothing screwed to it

`SCREW_LN` 3,5×11 — the metal-to-metal screw fixing a hanger or connector to a profile — was in no
position's norms at all. Every frame we sell needs it; the master finds that out at the top of a
ladder. New dictionary row plus six norms.

The figures follow **our** frame, not a datasheet's, because the datasheet's frame is a different one
(§26.2a): walls 1,3 hangers × 2 = 2,6 → **3**; ceilings 0,7 hangers × 2 + 1,7 connectors × 4 = 8,2 →
**8**. Change the hangers or the connectors and these must be recomputed **in the same migration** —
stated in the SQL, and pinned by a test.

Also added: `PRIMER_DEEP` **0,15** on «Монтаж гіпсокартону на клей». Every adhesive datasheet (Perlfix
above all) requires a primed substrate; the glue shipped without it, so the list told the master to
buy adhesive for an unprepared wall. It is a **LITRE material on an M2 norm** — correct, and exactly
rule 2 (V127 decision 2): a norm's unit is the POSITION's, and nothing is converted.

New rows are appended at `max(sort_order) + 1` per position rather than renumbering ranks, which was
the fiddliest part of V130.

**Skipped on purpose:** Стрічка розділова (Trenn-Fix) and ґрунт бетоноконтакт — no norm would
reference them, i.e. dead dictionary data; and `PROFILE_CD_EXTENDER` stays at V130's 0,2 rather than
`NORMS-SUMMARY`'s 0,6.

### 26.5 B-17 — packaging measured in a unit the calculator never reads

`MaterialCalculatorService#line` divides by `package_size` and then labels the result with the
material's **`unit`**, never reading `package_unit`. Every row V126/V127/V130/V131 seeded happens to
have the two agree, so the honest fix is to make the coincidence a **rule**: a CHECK that
`package_unit IS NULL OR package_unit = unit`. The day someone adds a KG material sold in 10-LITRE
buckets, the migration fails instead of the shopping list lying. The column stays (a future packaging
model may need it to diverge — at which point the calculator must learn to convert **first**).

### 26.6 B-27 — «Фасад» and «фасад» were two folders

The reserved aliases were always compared case-insensitively; custom names were compared with `=`.
So a master who typed «фасад» after making «Фасад» got a second folder and his photos split across
two entries he cannot tell apart in the list. **The asymmetry was the bug.**

Both halves are needed and only the second is enforceable: the service now looks folders up ignoring
case (`findByProjectIdAndNameIgnoreCase`, `existsByProjectIdAndFolderIgnoreCase`), and the migration
replaces `UNIQUE (project_id, name)` with a functional unique index on
`(project_id, lower(btrim(name)))`. A service guard alone still loses a race between two uploads,
which is how a twin got created in the first place.

**Order is load-bearing in the backfill**: photos reference folders **by name** (V111), so fold the
photos onto the surviving spelling → delete the twins → trim the survivors → swap the constraint. A
photo left pointing at a deleted folder name lands in a folder the master can no longer see. The
oldest row wins — it is the one he made first and the one his photos already carry.

One new private method owns the whole idea: `canonicalFolder` answers with the spelling the folder
**already has**, so a photo files into «Фасад» instead of minting a twin. The master named the
folder, not us. `normalizeFolder`/`matchesAlias` are untouched.

### 26.7 B-30 — what a self-check may and may not stop

(a) V132's pattern is **already in prod** («Так, уже в проді»), so per the review item there is
nothing to do; it is simply not repeated here.

(b) The rule this round implements: **a check guarding DATA raises `EXCEPTION`; a check guarding only
our own WORDING raises `WARNING`.** An admin renaming a catalog category between two deploys must not
be able to stop Flyway at 3am. §7 has six EXCEPTION checks (orphan `name_key`; all ten screw
corrections landed; box coefficients; both new materials found a position; no shared (position, rank);
no photo orphaned by §2) and **one WARNING** — if a ceiling position no longer buys `CONNECTOR_CRAB`,
the 0,7 hanger figure is now too low, but that is a judgement call about a live catalog, not a corrupt
write.

(c) A false claim in `MaterialCalculatorService#preferOwn`'s javadoc said a shipped norm «is deleted
and recreated by every catalog rebuild». Rebuilds recreate the **templates** (that part, in
`MaterialNormRepository`, is true and was left alone); norm rows are edited in place by correction
migrations and never re-seeded. The conclusion — address the default by NAME, not id — is unchanged;
the reason given for it was wrong.

### 26.8 Blast radius

SQL: `V133__drywall_norms_from_datasheets.sql` (schema §1-2, data §3-6, self-checks §7). Java: two
repository methods renamed to their `IgnoreCase` forms, `ProjectPhotoService` (`canonicalFolder` +
three call sites), javadoc only in `MaterialCalculatorService` and `ProjectPhotoFolder`.

**V130's and V131's own self-checks stay satisfied** and were re-read to confirm it: V130 asserts
every default `PROFILE_UW` = 0,35 and lists 12 (position, material) pairs — V133 touches neither;
V131 hard-codes `IF pairs <> 17`, and V133 changes box **values**, not row counts.

**No PWA change** beyond the version: no endpoint, field or wording moved. Mobile is therefore not
verified live and owes no verification — the only visible difference is different numbers in rows
that already render.

### 26.9 The gate

`./gradlew build` — **green: 1415 tests in 162 classes, 0 failures, 0 errors, 0 skipped**, of which
**14 are new** (8 + 4 in two new integration classes, 2 added to `ProjectPhotoServiceTest`). The
per-class results were read back rather than trusting the summary line, because a test class that
silently never runs reports coverage that does not exist — the failure mode `IntegrationTestBase`'s
own javadoc warns about.

**V133's §7 assertions ran as part of it**: they fire at apply time, so a green Testcontainers
startup IS the proof they passed (same reasoning V130, V131 and V132 recorded).

The two new integration classes exist for what the migration's self-checks **cannot** do — stop a
LATER migration from quietly undoing this one. `DrywallNormCorrectionsIntegrationTest` reads the
corrected figures back out of a live schema (and pins B-17's CHECK from both sides);
`ProjectPhotoFolderCaseIntegrationTest` pins the functional index and the case-insensitive lookup.
Both clean up after themselves — the Testcontainers schema is shared.

Version `1.45.0` → **`1.45.1`**: a correction to shipped behaviour is a patch.

**The machine trap from §22.8 fired again, in a new way.** `JAVA_HOME` still points at
`C:\Program Files\Java\jdk-25.0.3`; the JDK has since auto-updated to **`jdk-25.0.4.1`**, so the path
is stale again and Gradle does not start at all. The build was run with
`$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4.1'` for that invocation only — the machine setting
was **not** changed. The toolchain is pinned to 21 and no JDK 21 is installed, so the foojay resolver
in `settings.gradle.kts` provisions it (which is why this build took 5m15s). Worth fixing at the
machine level rather than per-session.
