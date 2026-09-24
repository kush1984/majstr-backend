# Fix O — a line belongs to the trade the master was working in (2026-09-24)

**Status:** built, green, **uncommitted**. Migration **V140**. PWA **1.47.2**.

Two complaints, one root cause, found by reading the master's own database.

> «коли в нас є шаблон по малярних роботах, то в нас появляються якісь не зрозумілі категорії з
> плитки, гіпсокартону і т.д.»
> «калькулятор появився тільки коли я щось ввів з гіпсокартону»

## 1. The root cause

`catalog_items` holds **ONE row per (owner, name, type, unit)** — V118's rule — so a position two of
the master's trades both ship is stored once, under whichever trade **claimed the name first**. Every
door that built an estimate line then copied that row's `trade` and `category` verbatim, and the
bundle's own trade — which the code had in its hand — was thrown away.

His painting estimate `3ecd086d`, straight out of the database:

```
 5|DRYWALL|Оздоблення під фарбування|Обезпилення поверхні
 6|DRYWALL|Підготовка та захист     |Грунтування
15|DRYWALL|Оздоблення під фарбування|Базове шпаклювання під скловолокно
18|DRYWALL|Оздоблення під фарбування|Шпаклювання фінішне (2–4 рази)
19|DRYWALL|Оздоблення під фарбування|Шліфування стін/стель (фінішне)
27|TILING |Організаційні послуги    |Прибирання приміщення після робіт
28|TILING |Організаційні послуги    |Винесення та вивезення будівельного сміття
29|TILING |Організаційні послуги    |Гарантійний повторний виїзд
```

36 painting positions are shared this way, and the folders genuinely differ: «Шпаклювання фінішне» is
DRYWALL / «Оздоблення під фарбування» and PAINTER / «Шпаклювання та шліфування»; «Поклейка
склополотна» is DRYWALL / «Оздоблення під фарбування» and PAINTER / «Шпалери».

**It is not cosmetic.** The same stamp is what `MaterialCalculatorService#normsFor` filters
consumption norms by, and what `MaterialCoverage` names the answered trades from. Eight positions in
his live catalog were unanswerable because of where the row happens to sit:

```
BUILDER |Фарбування фасаду                        → norm shipped under PAINTER
BUILDER |Декоративна штукатурка фасаду короїд     → PAINTER
BUILDER |Грунтовка поверхні кварцгрунтом          → PAINTER
BUILDER |Монтаж / демонтаж будівельного риштування→ PAINTER
BUILDER |Армування фасаду сітка перетяжка         → PAINTER
TILING  |Грунтовка поверхонь бетоноконтактом      → PAINTER
PLUMBING|Установка люка-ревізії простого          → DRYWALL
```

— the facade norms V138 had shipped a day earlier, filed under PAINTER on purpose (a trade-less row
would have flipped `/materials/availability` ON for trades with no norms at all).

## 2. `CatalogFiling` — one rule, three doors

The caller says which trade the master was **working in**; if the shipped library files that exact
name+type+unit under that trade, the line takes THAT trade and THAT folder. Otherwise nothing is
assumed and the matched row's own filing stands — **an answer we cannot source from the library is a
guess, and a guess here moves a position into a folder he never chose.** Only the filing moves:
price, unit, type, wording and description stay the master's own.

Where «working in» comes from at each door:

- **A bundle** — `EstimateTemplate.trade`. A bundle of the master's own CUSTOM trade answers null:
  its `trade` column reads OTHER for storage reasons only (V91) and the library ships nothing under
  OTHER, so re-filing could only ever clear a folder he chose himself.
- **The picker** — the BRANCH of the tree he tapped in, sent as an optional `trade` on
  `EstimateItemFromCatalogRequest` and on each `AddCatalogItemsBatchRequest.Entry` (per entry: one
  multi-select can span branches). It is a hint from a screen, never an instruction — the server
  honours it only if the library agrees, so a forged value can only ever be ignored.
- **Anything else** (the autocomplete, an offline replay of an older payload) — null, and nothing
  changes.

A subtlety kept deliberately: a library row with **no** folder of its own files the position
«nowhere in particular», so the stored folder is kept rather than «Без категорії» invented. That is
the rule `toTradeTree` already follows when it shows a shared position under a second trade.

## 3. The calculator button, and the second half of the same bug

`workLines` dropped `quantity <= 0` lines — correct, and shipped for a real bug (31 of 39 lines on
the master's test estimate were price-list rows at 0 м², each producing «Картон захисний — 0 м²»).
But the availability PROBE shared that filter, «so the probe and the result screen can never
disagree». An estimate applied from a bundle carries **nothing but zeros**, so the probe answered
«nothing to buy» and «Матеріали» was hidden at exactly the moment the estimate was created.

Split in two: `buyableLines` (type WORK, not PERCENT) is what the probe asks about — **whether we
can answer is a property of the position NAMES** — and `priced` adds the quantity filter for the
calculation. `MaterialCalculationResponse.quantitiesMissing` carries the difference to the screen,
which now says «Впишіть кількості» instead of «ми не знаємо норм для цих робіт» — a different
sentence, and a false one here.

**Nothing invalidated `MATERIALS_AVAILABILITY_KEY`** either, so a cached «no» outlived every line he
then added (`staleTime` 60 s, `refetchOnWindowFocus: false`, a week-long `gcTime`): the button came
back only after leaving the page and returning a minute later — which is why it looked as though
drywall specifically had unlocked it. It now hangs off `useInvalidateEstimate`, the one door every
estimate writer already goes through.

## 4. V140 — the drafts already written

Forward-only fixes leave the estimate he is looking at wrong, so the repair runs over **DRAFTS
only** (his ruling). A SIGNED estimate is a snapshot and is never rewritten; a SENT one is already
open on the client's phone, and although only the section headings would move, a document changing
under a reader invited to read it is not something to do for tidiness.

Three refusals to guess, each costing rows the migration could have touched:

- **a line with no trade stays untouched** — NULL is deliberate (V125): hand-typed lines and
  ADDENDUM rows carry it, and inventing a trade puts the master's own wording in a folder he never
  chose. On dev this alone left 14 rows alone.
- **an estimate with no clear majority is skipped** — the bundle that produced a line is recorded
  nowhere, so the working trade has to be inferred, and the only honest evidence is that at least
  half the document agrees.
- **a name the majority trade does not ship gets no new home.**

Dry-run on the dev database: **9 rows**, all of them his painting estimate's eight foreign lines plus
one. The migration `RAISE NOTICE`s the count.

## 5. Tests

- `CatalogFilingTest` (7) — the rule itself: re-files when the library agrees, keeps the stored
  filing when it does not, a different UNIT is a different position, no working trade changes
  nothing, a line that matched no catalog row still lands in the right folder, a library row with no
  folder keeps the stored one, and the key survives real seed data's untidiness.
- `LineFilingOnLiveCatalogIntegrationTest` (4) — **the test that would have caught this**, because
  both halves of the bug are DATA: a PAINTER bundle over a catalog storing the shared positions
  under DRYWALL/TILING, the picker door, the no-branch door, and a guard that the two positions it
  is built on are still shared with different folders (otherwise the other three would pass by
  saying nothing).
- `EstimateTemplateServiceTest` (+2), `EstimateServiceTest` (+2) — the mocked halves, including the
  custom-trade bundle that must not re-file.
- `MaterialCalculatorIntegrationTest` (+3) — a bundle-fresh estimate still offers the screen and
  asks for quantities; an empty estimate asks for nothing; one priced line stops the ask.
- PWA: `catalogTree.test.ts` (+2) — a shared row answers with the branch, a custom branch answers
  null; `CatalogPicker.test.tsx` (+2) — each copy of a shared row answers with its own branch;
  `MaterialCalculatorPage.test.tsx` (+1) — asks for quantities instead of claiming ignorance.

## 6. Verification

`./gradlew build` — **green, exit 0: 1542 tests in 177 classes, 0 failures, 0 errors, 0 skipped**
(1524 → +18, two new classes). Per-class XML read back rather than the summary line.

PWA gate, CI's `verify` job mirrored in order: `npm run lint` ✓, `npx tsc -b` ✓,
`npm run typecheck:tests` ✓, `npx vitest run` — **1180 tests in 133 files** ✓, `npx vite build` ✓.
No service-worker or offline change, so `test:e2e:offline` is not owed.

`RequestContractSnapshotTest` regenerated for the new optional `trade`, and the snapshot copied to
`majstr-pwa/src/api/contract/request-dtos.json` — mirrored pair, changed in the same round.

## 7. Not done, deliberately

- **The catalog PAGE still browses by chips** while the picker is a tree — the existing open
  question, untouched. It edits rows rather than picking them, so the branch question does not
  arise there.
- **A shared position is still ONE row.** Re-filing at write time is the fix; splitting the storage
  per trade would duplicate the master's price and let the two copies drift.
