# Iteration: DRYWALL — one wording per job, a catalog ordered by phases, and the finishing chain

**Status:** code complete, backend build green (`./gradlew build`, **1171 tests**), NOT pushed
(awaiting the user's approval).
**Source:** `C:\Work\prompts\drywall-catalog-prompt.md` + a master's own «Технологічна матриця —
Підготовка під фарбування поверхонь ГКЛ» PDF (серпень 2026).
**Migrations:** **V116** `V116__drywall_catalog_by_phases.sql`, **V117** `V117__drywall_joint_sanding.sql`,
**V120** `V120__catalog_prices_and_drywall_gaps.sql` (§14 — his prices, four gaps).
**PWA:** 1.30.0 → **1.31.0** (minor — migration-backed).

---

## 0. What the prompt claimed, and what was actually there

The prompt's mandated order was recon → report → code. The recon disagreed with the prompt on four
points, all confirmed against the running database before a line was written:

| The prompt says | What the database says |
| --- | --- |
| DRYWALL has ~17 positions | **51** |
| ≥10 bundle positions are missing from `catalog_templates`, so they apply at 0 ₴ | **Zero.** Every default bundle line resolves. The invariant test the prompt asked me to write **already exists** (`SeedCatalogInvariantsIntegrationTest.everyDefaultBundlePositionCanBePriced`, added when V70–V73 fixed exactly that class of bug for other trades) and it passes |
| The levels to ship are Q2 / Q3 / Q4 | The master's own matrix sells **Q3 / Q3+ / Q4**. Q2 is a level for tiling and heavy wallpaper, and he does not sell it |
| Positions carry a `description` | There was no such column. V116 adds one |

So **defect #1 as described does not exist.** What does exist is a worse one the prompt did not
name, and it is what this iteration is actually about.

## 1. The real defect: one job, two names, two prices

Two import waves never met. V27/V31 seeded one set of wordings; the tetris import (V50) seeded a
second set **for the same eight jobs**, at different prices. V50's dedupe compared
punctuation-insensitively, and these pairs differ by *words*, so it saw nothing:

```
Монтаж радіусної перегородки ГКЛ в 1 шар                              240 ₴
Монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар    900 ₴
```

Eight such pairs. Two masters quoting the same radius partition got 240 or 900 depending on which
bundle they happened to tap. And it is not only cosmetic: `price_insight_candidate` (V94) computes
its crowd median **per name** and needs ≥3 masters agreeing on one — splitting a job's usage across
two names halves the signal on both, so the mechanism that is supposed to settle these prices was
being starved by the duplication.

Two more, found on the same sweep:

- **`Монтаж на висоті більше 3м надбавка` was `PERCENT` with `suggested_price = 345.00`.** V31 wrote
  a money price into a percent row, so a master who picked it added **345 %** to the estimate. Its
  tetris twin is the correct 20 %, so this dies with the merge rather than needing its own UPDATE.
- **Seven masonry positions filed under DRYWALL** («Кладка перегородки з цегли…»). User: «це не
  частина гіпсокартону, це треба прибрати».

## 2. Why a MERGE, and why the pattern is new

Third distinct shape in this codebase:

- **V82/V83 — replace.** Tiling's catalog was thin; throw it out and write a new one.
- **V96/V97 — extend.** Painter's was rich and internally consistent; add to it.
- **V116 — merge.** Drywall's was rich and **self-contradictory**. Neither of the above applies:
  replacing would throw away good rows, extending would keep the contradiction.

**Canon is the tetris (v5) wording** (user: «v5 канон»). It is also the better-formed set — «Монтаж
короба (прямого) із гіпсокартону по периметру стелі» says which короб and out of what; «Монтаж
короба прямого по периметру стелі» says neither.

## 3. Categories are now PHASES (this overrides V72)

V72 filed DRYWALL by **object** — Стелі / Перегородки / Короби / Шви і суміші / Інше. That reads
fine as a warehouse index and badly as a work list. A master describes a job as a **sequence**
(«знімаю стару обшивку, ставлю каркас, шию, заробляю стики, готую під фарбу»), and the **client**
reads the estimate in that order too — he has no idea which of five buckets «Заробка стиків» lives
in. The object (стеля / перегородка / короб) is in the position **name**, where search finds it.

```
Підготовка (4)  →  Каркас і обшивка (23)  →  Оздоблення під фарбування (17)
```

plus two categories that are not phases and stay as they are: **Звукоізоляція та утеплення** (9 — a
parallel job with its own positions, deliberately never glued into a ГКЛ position; untouched per the
prompt) and **Надбавки** (1 — the >3 m PERCENT modifier, which belongs to no phase).

`category` is display-only and carries no FK, so re-filing is safe — the same reasoning V72 used.

## 4. The glued position, split

```
Заробка стиків у гіпсокартоні, поклейка склополотна (серпянки) на стики і по периметру — 100 ₴/м.п.
```

Three operations in two different units under one linear-metre price: armouring the joints is
measured **along the joint**, fibreglass over a plane is measured in **м²**, the perimeter is a third
thing again. Replaced rather than renamed, because one of the three children already exists under
PAINTER:

| child | unit | price | source |
| --- | --- | --- | --- |
| Заповнення та армування стиків ГКЛ | м.п. | 100 | new, canon price |
| Проклеювання склополотном примикань і кутів | м.п. | 60 | new |
| Поклейка склополотна | м² | 160 | **copied verbatim from PAINTER** |

## 5. The finishing chain, and why levels are ONE position each  ⚠️ OVERTURNED BY V121 (§15)

From the master's matrix: Q3 · Економ (22 етапи, 1 контроль ковзним світлом), Q3+ · Преміум (28
етапів, 6 контролів, вологе обезпилювання), Q4 · Еліт (32 етапи, 10 контролів, стики паперовою
стрічкою високої щільності).

Nobody buys «2.4 Контрольне ковзне світло» separately — a control point is what the level
**guarantees**, not a line on an invoice. By the atomicity rule that is **3 positions, not 82**.
«Контрольне ковзне світло» and «Світло під фарбування» are therefore deliberately NOT positions.

What distinguishes the three is a **sentence**, not a name — which is why V116 also adds a
`description` column (§7).

The individual stages the catalog did **not** already carry became positions too, because a master
who prices the chain himself needs them: `Криючий ґрунт-наповнювач`, `Локальне дефектування`,
`Мікрошліфування дефектів`, `Вологе обезпилювання поверхні`, the Q4 joint with paper tape, and (V117, §12)
`Шліфування стиків ГКЛ`.

**The stages we DID already carry are copied from PAINTER verbatim — name, type, unit and price,
not one word re-written.** `CatalogTemplateService.missingItems` dedups a master's catalog across
trades on name+type+unit, so a master running both trades still owns exactly **one** row;
re-wording any of them would have handed him two — the very defect §1 spends its length undoing.
Ten rows copied this way (2 DEMOLITION, 8 PAINTER).

**The levels stop before the paint** (user: «все має бути окремо»). The paint is the client's choice
and the painting is a separate job at a separate price. Stage 6 of the matrix is airless painting,
which PAINTER carried under no wording — so V116 adds **one row outside DRYWALL**:
`PAINTER / Фарбування / Фарбування безповітряним методом (airless)` м² 180. It is an addition, never
an edit. (`PainterCatalogRebuildOnLiveDataIntegrationTest.theCatalogEndsUpNetPlus81` was `…Plus80`.)

`Шпаклювання та шліфування гіпсокартону` stays as its own product (user: «це різне») but is renamed
to **`… (без склополотна)`** so it cannot read as a fourth, unnamed quality level sitting next to
Q3/Q3+/Q4, and moved out of «Арка та декор ГКЛ» into the finishing phase where it belongs.

## 6. Prices are an ORIENTIR, not a quote

Nothing is invented from the air, and nothing is confirmed by a master either. Every new number is
derived by proportion from a position this catalog already ships — the full table is in the
migration's own preamble. In short:

| position | ₴ | derived from |
| --- | --- | --- |
| Заповнення стиків паперовою стрічкою | 150 /м.п. | our own joint line 100 × 1.5 (paper tape is bedded and sanded, mesh is not) |
| Криючий ґрунт-наповнювач | 120 /м² | PAINTER «Грунт-фарба (праймер під фарбу)» 80, raised for a filling primer laid thicker |
| Локальне дефектування | 60 /м² | PAINTER «Заробка тріщин раковин шліфування» 60 |
| Мікрошліфування дефектів | 60 /м² | below «Шліфування стін/стель (фінішне)» 100 — it is local, not the whole plane |
| Вологе обезпилювання | 40 /м² | PAINTER «Обезпилення поверхні» 25 × 1.6 |
| Проклеювання склополотном примикань | 60 /м.п. | the perimeter half of the split row |

The three levels are priced from the **sum of their own chain**, then rounded down — a turnkey level
must not cost more than buying its stages one by one: Q3 = 1159 → **1100**, Q3+ = Q3 × 1.27 →
**1400**, Q4 = Q3 × 1.45 → **1650**. (The three figures were approved by the user.)

**These must be checked with masters.** The mechanism that is supposed to set them for real is
`price_insight_candidate` (V94) — a weekly crowd median off masters' own estimate lines, min 3
masters after an IQR trim. It is starving on today's user count, which is exactly why a derived
orientir ships now instead of a blank. §1 also removes one reason it was starving.

## 7. `description` — a new column, read path only

```sql
ALTER TABLE catalog_templates ADD COLUMN description VARCHAR(500);
ALTER TABLE catalog_items     ADD COLUMN description VARCHAR(500);
```

«Підготовка ГКЛ під фарбування · Q3» and «· Q4» are not distinguishable by name — the difference is
which paints may go on top, how many sliding-light control points, and whether the surface is
wet-dedusted. It travels with the copy into a master's own catalog like every other field, because
**the master is the one who has to explain it to the client**.

Wired on: both entities, `CatalogItemResponse`, `AdminCatalogTemplateResponse`,
`CatalogTemplateService.missingItems` (so it rides the library copy).

**Deliberately NOT added to `CatalogItemRequest`.** The PWA has no field for it, and a PATCH that
omits a column it cannot see would **null the text on the master's first edit**. Read path only
until the PWA catches up — logged in `docs/open-questions.md`.

## 8. Fourteen bundles → four

Eleven of the fourteen held 2–5 positions: «Короб під ванну» was two lines, «Стеля ГКЛ рівна» two.
That is the shape V112 already rejected for PAINTER — «просто набір позицій, без будь-якої
послідовності» — and a master does not reach for a bundle to save himself three taps.

| bundle | lines |
| --- | --- |
| Стеля з гіпсокартону | 18 |
| Стіни та перегородки з гіпсокартону | 19 |
| Підготовка ГКЛ під фарбування | 18 |
| Звукоізоляція та утеплення (was `ЗВУКОІЗОЛЯЦІЯ`) | 6, untouched |

`sort_order` **is** the content: підготовка → робота → фініш, in the order the work is actually done.

A bundle offers **alternatives** as well as steps (в 1 шар / в 2 шари has always done this) — the
master deletes the variant he is not building. The three Q levels are alternatives to **each other**
and to the piecemeal chain above them, which is why they sit at the end of their own bundle and
never inside the assembly ones: a Q level already contains the joints and the fibreglass, so listing
it beside them would bill the same work twice.

The survivor `ЗВУКОІЗОЛЯЦІЯ` keeps its positions, their order and its override rows — only the
**label** changed, because it was the last CAPS bundle name in the trade and would have read as a
shout beside three sentence-case siblings.

## 9. Existing masters (V83/V97 pattern, with one correction that matters)

V83's and V97's delete guard asked only *"does `catalog_templates` still carry this name/type/unit
**anywhere**"* — with **no trade filter**. That is fine when the retired rows are unique to the trade
being rebuilt. It is **wrong here**: all seven masonry positions are still shipped under BUILDER at
the identical price, so the old guard would have **blocked every single masonry deletion** while the
migration still reported success.

V116 asks the question that was always meant — *does any trade **this master has** still ship this
position?*

```sql
AND EXISTS (SELECT 1 FROM user_trades ut WHERE ut.user_id = ci.owner_id AND ut.trade = 'DRYWALL')
AND NOT EXISTS (
    SELECT 1 FROM catalog_templates ct
    JOIN user_trades ut ON ut.user_id = ci.owner_id AND ut.trade = ct.trade
    WHERE lower(trim(ct.name)) = lower(trim(ci.name))
      AND ct.type = ci.type AND ct.unit = ci.unit)
```

A master with DRYWALL + BUILDER keeps his masonry (he is a builder, he lays block); a DRYWALL-only
master loses it. **This is load-bearing on live data:** of the two DRYWALL masters today, one has
BUILDER and one does not.

Never touched: `estimate_items` (snapshots, no FK — a signed estimate reads tomorrow as it read
today); `catalog_items` with `source <> 'LIBRARY'`; a LIBRARY row whose **price the master changed**
(compared against the `drywall_v13_retired_baseline` scratch table, dropped at the end).

### The ordering trap in PART 9/10

`template_default_override.template_id` is **ON DELETE CASCADE**. Deleting the default bundles
therefore deletes the override rows *with* them — and PART 10 needs those rows to find a master's
**forked** copy of one of our bundles (V113) and repoint its lines off the retired wordings. Written
in the obvious order, the fork silently keeps a line naming a position that no longer exists, and it
applies at **0 ₴**. So the forks are captured into `_forks` **before** the DELETE. (Found by writing
the test, not by reading the code.)

`forked_template_id` is ON DELETE SET NULL on the other side, so the fork itself survives the
cascade as an ordinary master-owned template — he keeps every edit; it simply stops being "my copy
of a default" and becomes "my template". Templates he wrote himself are never touched at all.

## 10. Tests

**`DrywallCatalogRebuildOnLiveDataIntegrationTest`** — the "second database migrated to the version
before the change" pattern (`PaymentReceiptMigrationOnLiveDataIntegrationTest`): migrate to V115,
plant two masters, finish migrating, look. 13 tests. The two masters exist specifically for §9 —
`SOLO` (DRYWALL) must lose his masonry, `BOTH` (DRYWALL + BUILDER) must keep it.

**`SeedCatalogInvariantsIntegrationTest`** gained
`noPositionIsSoldTwiceUnderWordingsThatDifferByWordsRatherThanPunctuation` — the detector that would
have caught §1 in 2026. The existing duplicate check compares punctuation-insensitively, which is
exactly what V50 claimed to do and why it saw nothing. The new one drops the words that carry no
meaning here (ГКЛ ≡ гіпсокартон ≡ nothing, «із», «на», «конструкцій», the trailing «надбавка»), cuts
every remaining word to **six** letters so a declension cannot hide a repeat
(«радіусної»/«радіусних»), and compares the bag of stems.

Two calibrations, both measured against the real catalog rather than guessed:

- **six letters, not five** — at five, «гідрокомпенсатора» and «гідрострілки» collide, which is a
  false accusation about two different devices;
- **a token containing a digit is never cut** — «1200х2400» and «1200х3200» are different tiles, and
  truncating made them one.

It ships with a **named** allowlist of exactly one pre-existing group, `KNOWN_LEGACY_DUPLICATES`:
BUILDER's «Гідроізоляція плівкова» / «Гідроізоляція плівкою». That is a real defect, but it belongs
to BUILDER, and retiring a row there means refreshing every builder's own catalog — a migration of
its own. Listed by name rather than weakened away, so it has to be **deleted from the list**, not
merely noticed, when BUILDER's turn comes. (Open-questions item added.)

The invariant the prompt asked for — every default bundle position has a catalog match in its own
trade — **already existed** and was not duplicated.

## 11. The count, and the prompt's own cap (lifted)

The prompt said: «Мета — приблизно 30–40 робочих позицій у DRYWALL замість 17… Якщо виходить більше
50 — правило застосовано неправильно, зупинись і доповідай.» It was reported at 54 and **the user
lifted the cap**: «якщо є більше 50 то всерівно добавляй, то було хибне про 50.» That released the
one stage that had been folded away rather than shipped — §12.

**DRYWALL lands at 55.** The arithmetic:

```
51  before (not 17 — the prompt's baseline was wrong)
-17  retired (8 duplicate losers + 7 masonry + the glued row + the 345 % row)
+11  new (2 split children, 6 stages off the master's PDF, 3 Q levels)
+10  verbatim copies of stages PAINTER/DEMOLITION already ship
= 55
```

It is not over-splitting. The lines past 50 are both things the user asked for explicitly: «з того
документу пдф що я тобі скинув треба взяти позиції ті яких в нас не має» (+6) and the ten
cross-trade copies, which are not new *jobs* at all — they are rows a two-trade master already owns,
made visible under DRYWALL so the finishing bundle can name them. **Working positions invented in
this iteration: 11.**

## 12. V117 — joint sanding, the one stage the cap had swallowed

Re-reading the matrix against the shipped catalog with the cap lifted found exactly one gap: stage
**1.2 «Шліфування стиків ГКЛ»**. V116 folded it into the *description* of «Заповнення та армування
стиків ГКЛ», on the reasonable-sounding grounds that a joint is filled and sanded in one pass over
the same linear metre. The master's own document disagrees, and it is his document that decides: he
lists it separately, ships it in **Q3+ and Q4 and not in Q3** — which makes it a step that is sold
or not sold, not a detail of another step. A master composing the chain by hand instead of buying a
Q level has to be able to price it.

- `Шліфування стиків ГКЛ` · Оздоблення під фарбування · WORK · м.п. · **40 ₴** — the same kind of
  orientir as everything in §6: the full joint pass (fill + tape + sand) is the canon 100 ₴/м.п. and
  sanding is roughly its closing 40 %; cross-check, «Мікрошліфування дефектів» is 60 ₴/м² and a
  joint strip is well under a square metre per linear metre.
- Its neighbour's description **stops claiming the sanding** (in `catalog_templates` and in masters'
  own LIBRARY rows) — leaving it would have two positions billing one operation on one estimate.
- Into «Підготовка ГКЛ під фарбування» at position 3, right after the two joint variants and before
  the planes are primed. Inserting into the middle of a sequence means renumbering, so the
  migration self-checks that no two lines share a `sort_order` — `sort_order` **is** the content.

**Why a separate migration and not an edit to V116:** V116 had already been applied to the live dev
database (Flyway ran it on the next app start), and Flyway checksums an applied migration — editing
it means the app refuses to boot until somebody repairs the history by hand. Prod gets both in one
deploy either way.

**It deliberately reuses V116's catalog version 14** rather than opening 15. Both migrations reach
production in the same deploy, so the master sees ONE catalog update; and the propagation to
existing masters is by name, not by version, precisely because V116 has already stamped them at 14
and a version-driven refresh would skip them. For the same reason V117 **tops up V116's undismissed
COUNT notice by one instead of queueing its own** — two «каталог оновлено» rows out of one deploy
would claim an update that never happened. If a master has no undismissed notice (registered between
the two migrations), it queues a fresh one.

Three tests in `DrywallCatalogRebuildOnLiveDataIntegrationTest`, plus the notice assertion inside
`bothMastersAreToldWhatChangedAndAreMarkedSynced`.

## 13. Not changed / confirmed

- Звукоізоляція and утеплення positions: names, prices, units, and their bundle — untouched.
- The >3 m surcharge stays `PERCENT` 20 % (now the only PERCENT row in the trade).
- No Knauf system codes (C112 / W623 / D112), no manufacturer or product names. `Монтаж каркасу
  посиленим профілем Walraven TECE` → `Монтаж каркасу посиленим профілем` — EN 14195 names the
  profile family neutrally, which is what a reinforced frame actually means here. A self-check in
  the migration raises if a brand survives.
- Sources used: ДСТУ EN 520 (plate types A · H2 · DF · DFH2), EN 14195 (profiles CD 60×27,
  UD 27×28, CW 50/75/100, UW), the Q1–Q4 level scheme, and the master's own matrix. No third-party
  private price list was used as a source of structure or price.
- `estimate_items`, `catalog_items` with `source <> 'LIBRARY'`, every other trade (one documented
  PAINTER addition aside) — untouched.
- The migration carries **five self-checks** that `RAISE EXCEPTION` rather than land half-done: a
  money price on a PERCENT row, a surviving «Кладка» category, a position filed outside the five
  phases (NULL-aware — `NOT IN` is never true for NULL), a brand name, a duplicate wording group.

## 14. V120 — the three prices he settled, and four gaps in the trade

> «та ти ціни давай зміни, беремо 850, 1350 і 100 і доречі ти десь там бігав по сайтах і понаходив
> якісь позиції по гіпсокартоні, якщо там є що для нас взяти то давай беремо заодно»

### The prices

All three were logged as *waiting on his word, not on code*
([open-questions.md](open-questions.md), items 1 and 4 of the DRYWALL contradictions;
[iteration-catalog-order-and-explanations.md](iteration-catalog-order-and-explanations.md) §7).
Each is the same defect V116 existed to undo, seen **across trades** rather than inside one: one
job, two wordings, two prices.

| Position | was | now | the other wording, already right |
| --- | --- | --- | --- |
| PAINTER «Каркасна звукоізоляція ГКЛ два слоя стелі» | 80 | **850** | DRYWALL «Каркасна звукоізоляція (ГКЛ в два слоя) стелі» 850 |
| PAINTER «…стін» | 60 | **650** | DRYWALL «…стін» 650 |
| BUILDER «Облаштування дверного пройому звуження розширення» | 800 | **1350** | BUILDER «Облаштування дверної пройми» 1350 |
| PAINTER «Армування стиків ГКЛ» | 75 | **100** | DRYWALL «Заповнення та армування стиків ГКЛ» 100 |

Two things worth keeping in view. The soundproofing pair looks like it **inherited the price of the
безкаркасна variant** sitting next to it (which really is 80/60) — a 10x gap is not a pricing
opinion. And on the joints, the master had **already re-priced his own row to 100** by hand; that is
the answer to which of the two numbers is real, and it arrived before he said it.

`CatalogNameKey` already treats «Каркасна звукоізоляція ГКЛ два слоя стелі» and «Каркасна
звукоізоляція (ГКЛ в два слоя) стелі» as the **same key** («в» is a connector, word order is sorted
away), so this pair was also halving its own `price_insight_candidate` signal on both wordings.

**How the change reaches a master:** it does not. The shared `catalog_templates` row is updated and
every master still carrying our old number on a `LIBRARY` row gets a **PRICE_DRIFT notice**; his own
price moves only when he taps «Прийняти», and `acceptUpdateNotice` refuses even then if the number
has changed in the meantime. On the live copy that is **7 notices** — and deliberately **zero** for
the joints, because the one master who has that position had already fixed it himself.

### The four positions

Read the Kyiv per-position price lists (rabotniki.ua's averages over 40–120 offers each, budver,
kabanchik) against our 55. Almost everything they sell we already ship, and in more detail: укоси,
профіль тіньового шва, треки прихованого карниза, ніша з підсвічуванням, радіусні перегородки, арка,
фрезерування. Four things were genuinely absent.

| Position | | Why it is not covered by something we have |
| --- | --- | --- |
| Монтаж сухої збірної підлоги з гіпсоволокна | 430 ₴/м² | The **whole library** had no dry-floor row — no ГВЛ, no суха стяжка, in any trade. |
| Ремонт ділянки конструкції з гіпсокартону | 600 ₴/м² | TILING and PAINTER both ship a repair position; drywall did not. |
| Установка люка-ревізії простого | 400 ₴/шт | Copied **verbatim** from PLUMBING, description and all (NULL there). |
| Монтаж ущільнювальної стрічки на профіль | 50 ₴/м.п. | We had the wool, the membrane and the sealant, but not the tape that decouples the frame. |

Prices are an **orientir** derived the way §6 derives every number, to be settled by
`price_insight_candidate`: the floor from budver pricing a dry floor level with a wall, scaled by
the ratio our wall row already has to theirs; the repair from демонтаж (60) + обшивка (430) + the
joints around the patch; the tape from rabotniki's 30–70 range, average 43 over 68 offers.

**Only the tape joins a bundle** — «Звукоізоляція та утеплення», opening its framed half, because
the profile is taped before the frame goes up and a default bundle is a **sequence**. The other
three are stand-alone jobs: a dry floor is its own contract, a repair is ad-hoc, a hatch is priced
per piece when the object needs one.

**Deliberately not added**, each because it would compete with a row we already have — which is the
exact defect this whole iteration undoes:

- «Обшивка труб / стояків гіпсокартоном» — «Монтаж ніші під прихований карниз короб під
  комунікації» **is** that job, and it is the row a master already prices.
- «Монтаж дворівневої стелі» — that is a flat ceiling plus a короб by the linear metre, both of
  which we sell; an m² position on top of them double-bills.
- «Монтаж додаткового шару ГКЛ» — genuinely absent, but his own 800 → 900 for a partition in one vs
  two layers implies **~50 ₴/м² per extra face**, which is too low to ship as a number he might bill
  a client with. *«A norm we invented, that the master then billed a client for, is worse than no
  norm at all.»* Left for him to say; logged in [open-questions.md](open-questions.md).

The revision hatch shows the verbatim-copy rule paying off immediately: **both** DRYWALL masters on
the live copy already own it under PLUMBING, so V120 hands them nothing (one of them keeps his own
300 ₴), and V118's `sharedTrades` read path now shows it on the ГІПСОКАРТОН chip anyway. Re-wording
it would have given each of them a second row.

### What the migration has to do that is not obvious

- **Re-run V118's ranking.** `catalog_templates.sort_order` is the library's statement of the order
  the work is done in, and V118's self-check refuses rank 0. Four new rows on the column DEFAULT
  would break it and would land wherever PostgreSQL felt like putting them in the master's own
  catalog. PART 3 re-runs V118 PART 3/4 verbatim; **any future migration that INSERTs a template
  has to end this way too.**
- **Catalog version 15**, and it **tops up the undismissed COUNT notice** instead of queueing a
  second — V116/V117/V120 reach production in one deploy, so the master sees one «каталог оновлено»
  (§12 has the same rule and the same reason).
- **Guard before the UPDATE.** Each repriced row must still exist at exactly the old price, or the
  migration raises: his word was given about a number, and a notice quoting a price that has since
  moved would offer a change that can never apply.

`DrywallPricesAndGapsOnLiveDataIntegrationTest` — 9 tests on the V119→V120 live-data harness: the
four new numbers, the trade that was already right, a stale master being **offered** the change with
his own price untouched, a master who fixed it himself hearing **nothing**, the four positions
inside the phase sequence with a rank and a price, the hatch not duplicated for a plumber, one
deploy staying one notice, the tape resolving to a catalog position, and V118's ranking invariants
surviving the insert.

## 15. V121 — a level is a BUNDLE, and it explains itself to the client

This section **overturns §5**. Keep §5 as written: the reasoning it records («nobody buys a control
point separately») is still right, and it is exactly the reasoning that produced the wrong shape.

### What broke

V116 shipped the three levels as three catalog POSITIONS at 1100 / 1400 / 1650 ₴/м², each carrying a
five-sentence `description` (§7) that listed the whole chain of works inside it. Two things went
wrong at once, and the master named both.

1. **A level is a sequence, so it is a bundle.** The description said so out loud — «заповнення
   стиків, шпаклювання під склополотно, армування склополотном, фінішне шпаклювання, криючий ґрунт»
   is five positions this catalog already ships, squeezed into one line's explanation. His verdict:
   *«це не може бути однією позицією… це має бути шаблоном, тобто на кожен рівень робимо шаблон з
   специфічним набором позицій»*. §5 stopped one step short: it correctly refused to sell 82 stages
   as 82 positions, and then filed the answer under the wrong noun. The atomic unit of a CATALOG is
   a job; the unit that names a chain of jobs already existed and is `estimate_templates`.
2. **The paragraph had nowhere to live.** Rendered inline under the line (V119) it ran the full
   width of the estimate board — «все пливе» — and in the client portal it wrapped into a tall block
   while the position NAME above it was ellipsized to «Базове шпаклювання під с…». A 500-character
   sentence is not a line label under any layout.

### Q1 and Q2 exist, and he went and checked

Q1…Q4 is not a Knauf table — it is how the whole industry grades a drywall surface («тобто це не
тільки кнауф таке робить, а і інші виробники»). His own matrix starts at Q3 because he sells
painting; a job that ends under **tile** (Q1) or under **wallpaper** (Q2) is a different, cheaper
contract he was quoting by hand. So **five** bundles ship:

| bundle | lines | what it ends under |
| --- | --- | --- |
| Q1 — під плитку та панелі | 3 | керамічна плитка, стінові панелі, груба фактурна штукатурка |
| Q2 — під шпалери | 5 | стандартні та рельєфні шпалери, фактурні фарби, рідкі шпалери |
| Q3 — під матову фарбу (економ) | 12 | матові фарби економ і середнього сегменту |
| Q3+ — під якісне освітлення (преміум) | 14 | матові та глибокоматові преміум |
| Q4 — під глянець і бокове світло (еліт) | 14 | будь-які, включно з глянцем |

**Q3+ is the one judgement call.** It is not in the industry table — it is the master's own middle
tier, fully specified in his matrix (28 stages, 6 light checks) and priced at 1400 ₴/м². Dropping it
to match the textbook would delete a product he sells; keeping it costs one row in a list he can
hide per-master anyway (V113). **Q4 differs from Q3+ in exactly one line** — the joint on
high-density paper tape — which is what the matrix says and what the test pins.

**Q1 and Q2 are short on purpose, and that does not contradict V112's «a 3-4-line bundle is not
worth reaching for».** That rule is about a bundle whose only value is saving taps. Here the
shortness IS the product: Q1 is genuinely «стики і саморізи, далі плитка», and what the master
reaches for is the named level and the paragraph the client reads, not the three lines.

A stage the matrix repeats — priming, знепилення — is listed **once**. How many coats is the
quantity, and the quantity is the master's (the V112 rule, unchanged).

### The two new columns

```sql
ALTER TABLE estimate_templates ADD COLUMN description VARCHAR(1000);
ALTER TABLE estimates          ADD COLUMN quality_note VARCHAR(1000);
```

- `estimate_templates.description` — the level in the client's words: what is done, which paints may
  go on top, how many sliding-light checks, and **what the tolerances are**. Fully editable by the
  master (`PATCH /api/estimate-templates/{id}`), which forks a default on write like every other
  template edit (V113).
- `estimates.quality_note` — **a snapshot taken at apply time, never a join.** Same rule as
  `estimate_items.description` (V119), `source_unit_price` and every other frozen field: the client
  signed THAT wording, so re-wording or deleting the bundle must not change a signed estimate. It is
  read-only — there is no request field for it — and it survives `duplicate` (the same work at a
  different price still promises the same level).

`EstimateTemplateService.qualityNote(templates)` joins the distinct non-blank descriptions of the
applied bundles with a blank line, capped at 1000 chars: applying Q4 twice, or Q4 alongside an
undescribed bundle, must not make the client read the same paragraph twice.

`updateMeta`'s description argument is **three-valued**: `null` = leave as is, blank = clear, text =
write. A plain rename — and an offline replay of one — must not silently drop a client-facing
paragraph it never knew about.

### Where the client actually reads it

- **Portal** (`static/portal/index.html`): a «Стандарт робіт» card under the estimate table,
  `white-space: pre-line`. The same file also fixes screen 2 — the name column no longer ellipsizes;
  the numeric columns take fixed widths, the name takes the remainder and **wraps**, and the
  position's own `description` moves behind a per-row `(i)`.
- **PDF** (`EstimatePdfService.addNotesAndSignatures`): «Стандарт робіт:» printed **above** «Умови:»
  — the level explains the table over it, not the terms under it. The per-position long descriptions
  in the PDF are untouched this round («пдф поки не чіпаємо»); this block is the thing he asked for.

### And where the MASTER meets it (PWA)

The same round moved the per-position explanation off the estimate board, which is what screen 1 was
about, and gave the bundle's paragraph a place to be written and read.

- **`EstimateItemsBoard`** — the line's own `description` (V119) no longer renders inline under the
  name. It ran the width of the board and pushed the whole estimate sideways («все пливе»), on a row
  that already carries quantity, unit price and act progress under the name. It is an `InfoPopover`
  **beside** the row now — a sibling AFTER the row `<button>`, never inside it, because a button in
  a button is invalid markup. Same gesture as the catalog board, minus the clamped preview line.
- **`TemplatesPage`** — the list row gets the same `(i)`, and the editor gets the field the
  paragraph is actually written in: a 1000-character `<textarea>` under the name, inside the
  explicit-save draft. Two rules it inherits from V113: the metadata write is **one** call carrying
  name and description together, and `description` is **sent only when it changed** — absent means
  «leave it as it is», so a plain rename can never drop what the client reads. `follow()` still
  re-keys the cache onto the id the server answers with (a default forks on write).
- **`TemplatePickerSheet`** — the `(i)` in the row and the paragraph spelled out in the preview:
  choosing between «Q3+» and «Q4» off the names alone is exactly the choice the master said his
  clients cannot make either.
- **`EstimateEditorPage`** — a read-only «Стандарт робіт» card under the lines, saying out loud that
  this is what the client reads under the table and that changing it means changing the bundle. It
  is a snapshot; there is no request field, so there is nothing to edit here.
- **`useApplyTemplate`'s offline composition** mirrors `EstimateTemplateService.qualityNote`
  character for character (distinct non-blank descriptions, `\n\n`, capped at 1000) — the
  mirrored-formulas rule. Same pass made the line's `description` ride the offline copy too, matched
  on the same `nameKey` the PRICE resolves through.

### What the migration has to do that is not obvious

- **Fix the FORKS before deleting the default.** `template_default_override.template_id` is
  `ON DELETE CASCADE`, so the override rows pointing at «Підготовка ГКЛ під фарбування» vanish WITH
  it — and they are the only way to find a master's forked copy. Delete first and a fork keeps three
  lines naming positions that no longer exist, applying each at **0 ₴**, silently. Same trap as V116
  PART 9, in the opposite direction.
- **No new catalog positions ⇒ no version bump.** Every stage the five bundles name already exists
  (V116 read them off the same matrix, V117 added the joint sanding). V121 only DELETES three rows,
  so `MAX(added_in_version)` stays **15** and V118's ranking re-run is **not** needed — deletions
  leave every remaining `sort_order` distinct and non-zero. A version bump exists to push NEW rows
  into masters' catalogs, and there are none.
- **A position keeps a hint, the bundle keeps the paragraph.** The five surviving DRYWALL
  descriptions are trimmed to one sentence and pushed onto masters' LIBRARY copies (safe: §7's rule
  that `description` is not on `CatalogItemRequest` means there is nothing of the master's to
  overwrite). A self-check refuses any DRYWALL description over 200 chars.
- **The retired positions leave a master's catalog on the V83/V97/V116 guard** — only while the row
  still carries OUR price and only when no trade he actually has still ships that name — and the
  COUNT notice is **topped up, not queued a second time** (one deploy, one notice; §12's rule).
- A template the master wrote **himself** is never touched. If he typed a level position into his own
  bundle, that line is his, and it stops resolving to a price the way any hand-typed line does.

### Tests

`DrywallQualityLevelsOnLiveDataIntegrationTest` — 10 tests on a V120→head live-data harness: no level
sold as a position any more; five described bundles; the old bundle gone; Q4 differing from Q3+ in
exactly one line and Q1 genuinely three lines; **no bundle line that would apply at 0 ₴**; a position
capped at a hint while the bundle carries the paragraph; the catalog version not moving; a master on
our price losing the rows and hearing about it; a master who re-priced Q4 himself keeping his row and
hearing **nothing**; and a **forked** bundle losing the retired lines yet surviving the default's
deletion. Unit side: `EstimateTemplateServiceTest` (the snapshot at apply time, the join across
several bundles taken once, the empty case, `updateMeta`'s three-valued description),
`EstimateServiceTest.duplicate_carriesTheFinishLevelOntoTheCopy`, and two in
`EstimatePdfServiceTest` (the block, its position above «Умови:», and its absence on a hand-built
estimate).

PWA side, all in `majstr-pwa`: `useApplyTemplate.test.tsx` (the snapshot, the dedup across two
bundles, the join in pick order, the empty case, and the line's own explanation),
`useEstimateTemplates.test.tsx` (the three-valued `description` on the queue — a plain rename must
queue **no key at all**), `TemplatesPage.test.tsx` (the `(i)` in the list, the paragraph written in
one call with the name, and a rename sending `description: undefined`),
`TemplatePickerSheet.test.tsx` (row `(i)` + preview, and no `(i)` for an unexplained bundle), and
`EstimateItemsBoard.test.tsx` (the explanation is behind the `(i)`, one per explained row, and the
trigger has no `<button>` above it).

---

## 16. V122 — the ladder is Q1, Q2, Q3, Q4

> «дальше по шаблонах гіпсокартону — в нас має бути **Q1, Q2, Q3 and Q4, такого я Q3+ — не треба**»

§15 shipped five bundles and named Q3+ as **the one judgement call in the set** — not in the
industry's own grading table, but a middle tier his matrix specifies and prices. He has answered that
call. Five tiers is a choice the client cannot make either, and the two premium ones differed by a
single line.

V122 deletes **one default bundle row and nothing else**:

- **No catalog position is retired.** Every stage Q3+ named is also named by Q3 or Q4 (its line list
  is Q4's, with the joint on mesh instead of high-density paper tape), so nothing leaves any master's
  catalog and **no COUNT notice is queued**.
- **No new position ⇒ no version bump and no V118 ranking re-run.** Catalog version stays 15 —
  §15's reasoning, unchanged.
- **A master's FORK survives as an ordinary template he owns.** That is the V113 rule, not an
  oversight: forking means he edited it, and the override row is the only thing that made it «my copy
  of a default». Unlike V116/V121 this migration retires no position, so the fork's lines still name
  live catalog rows and still apply at real prices — **the ON DELETE CASCADE trap those two had to
  work around does not bite here**, because there is nothing to repair in a fork before the default
  goes.
- **Two position hints are re-worded** (PART 2). A hint naming a tier the app no longer offers is
  exactly the stale copy that makes him stop trusting the rest of them. Both stages survive — they
  are Q4's lines too — so only the wording moves.

A self-check refuses to finish unless the surviving DRYWALL ladder reads exactly `Q1, Q2, Q3, Q4`.

**The client-facing half of §15 is gone in the same round** — the «Стандарт робіт» card is no longer
rendered in the portal or the PDF («приберемо для клієнта взагалі покищо»). The `quality_note`
snapshot is still taken, still carried by `duplicate`, still shown to the master; only the two client
renderers stopped reading it. See
[iteration-catalog-order-and-explanations.md](iteration-catalog-order-and-explanations.md) §9.

**Tests:** `DrywallQualityLevelsOnLiveDataIntegrationTest` gained the V122 assertions (the ladder's
four rungs, the bundle gone, the catalog version and the notice queue untouched, a fork surviving),
and `DrywallCatalogRebuildOnLiveDataIntegrationTest`'s joint-sanding pair was re-read on the
surviving ladder — the sequence assertion used to name Q3+ (it now reads Q2, whose joint is the same
mesh one), plus the tier that deliberately **drops** the sanding (Q3) and the four rungs again.

### 16a. V123 — the sanding hint names every level that sells it

V122's PART 2 got one of its two re-wordings half right: it moved «Шліфування стиків ГКЛ» off the
retired tier to «Окремий етап у Q2 і Q4», and Q1 («під плитку та панелі») ships the same stage right
after the joint it sands. A master who applies Q1 then reads a hint placing the stage in two other
levels — the stale copy PART 2 existed to prevent, one migration later.

V123 is an UPDATE and nothing else: no position added, so no V118 ranking re-run, no notice, catalog
version still 15. Its self-check **reads the levels off the bundles** rather than typing them again
and refuses to run if they are not `Q1, Q2, Q4`, so the next bundle edit that moves the stage fails
the migration instead of silently outdating the sentence. The LIBRARY propagation is V121/V122's
rule verbatim — `description` is not on `CatalogItemRequest`, so a master cannot have authored the
text being overwritten.
