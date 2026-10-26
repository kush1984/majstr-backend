# Market price review 2026-10 — V176

**Source:** [price-review-2026-10/price-review-2026-10-decisions.json](price-review-2026-10/price-review-2026-10-decisions.json)
(the owner's decisions, applied as given) beside
[majstr-zvirka-tsin-2026-10.xlsx](price-review-2026-10/majstr-zvirka-tsin-2026-10.xlsx) (every figure's
sources: URL, what it says, city, date). Work-only prices, Kyiv / Lviv / Ukraine. Recon 2026-10-26,
owner rulings on the recon the same day. One migration: `V176__market_price_review_2026_10.sql`.

## Summary

| | count |
|---|---|
| Library prices changed | 253 — **183 up, 70 down**, 14 of them from a zero |
| Structural edits | 14 — 6 renames, 5 unit changes, 2 deletions, 1 description |
| New positions | 67, one new catalog version (26), pushed into nobody's catalog |
| Deferred (not touched) | 34 |

Prices per trade:

| Trade | up | down | from 0 |
|---|---|---|---|
| BUILDER | 37 | 11 | 0 |
| DEMOLITION | 16 | 2 | 0 |
| DRYWALL | 10 | 2 | 0 |
| ELECTRICAL | 16 | 25 | 0 |
| FLOORING | 7 | 4 | 0 |
| METAL | 5 | 2 | 0 |
| PAINTER | 18 | 8 | 0 |
| PLUMBING | 43 | 12 | 0 |
| TILING | 31 | 4 | 14 |

## 1. Prices — V157's shape, plus a trade filter

Every (trade, name, unit) in the decision file matched exactly one library row, at the expected old
price (the migration refuses otherwise). The library row moves. A master still carrying our OLD number
on a LIBRARY copy gets a PRICE_DRIFT notice. A price he set himself is never touched or nagged about.
A master with a pending notice for the position gets no second one.

**New: the notice is filtered by `catalog_items.trade`.** 24 repriced positions are a single row that
two or more trades ship, and a master holds ONE catalog row for it, filed under one trade (V118).
Matching on name alone (as V157 did) would have caused two problems. «Демонтаж перегородки з
гіпсокартону» would have got two notices on the same row (DEMOLITION 60 → 140 and DRYWALL 60 → 150).
And a BUILDER-filed «Грунтування» would have been told about PAINTER's 33 → 40. Owner ruling: notify
only the copy filed under the trade whose price moved, so one master's row gets at most one notice.
Shared rows keep a price per trade (V169's precedent), so after V176 several of them diverge:
«Облицювання фасаду каменем» (BUILDER 900 / PAINTER 1500), «Люк-ревізія» (PLUMBING 800 / DRYWALL 400),
and the sanitary demolitions (PLUMBING / DEMOLITION).

On the dev database: 2 386 notices across 74 masters; 360 copies left alone at their own price.

**Discrepancy with the decision file's note:** the note says 13 of TILING's 18 zeros get prices, but
`price_changes` prices 14. The migration applies `price_changes`, which leaves 4 TILING zeros:
«Укладання плитки поверх теплої підлоги», «Гарантійний повторний виїзд», «Замір приміщення та схема
розкладки плитки», «Виготовлення та монтаж кріплення для раковини». The pin in
`SeedCatalogInvariantsIntegrationTest` moved from 18 to 4. PAINTER's 11 organizational zeros are
deferred and stay at 0.

## 2. Structural edits

| Edit | What it touched (dev data) |
|---|---|
| unit «Формування кута з епоксидної суміші» M2 → м.п. (+ 287 → 320) | 30 copies, bundle «Душова врівень з підлогою (трап)»; 2 estimate lines are snapshots, untouched |
| unit «Відведення лінії фарби довкола вікон/дверей» M2 → м.п. | 1 copy, bundle «Фарбування»; norm re-ruled to masking tape **1,0 m per м.п.** (owner); the 0,5/м² row stays on the old unit |
| unit «Установка фільтра питної води під мийку» POINT → PIECE | 4 copies |
| unit «Монтаж фільтра самопромивного» POINT → PIECE (+ 400 → 900) | 4 copies |
| unit «Монтаж та підключення ДБЖ» POINT → PIECE (+ 8000 → 10000) | 24 copies |
| delete FLOORING «Приклеювання стиків в місцях порожків, герметизація силіконом» (PIECE 500) | library only; the survivor «Приклеювання стиків порожків герметизація» is м.п. 212; the deleted row's norm stays on its key |
| delete PLUMBING «Монтаж котельної котел бойлер насоси крани фільтра» (SET 825) | library only; the survivor «Монтаж котельної (…) - від» is PIECE 20 000 |
| rename «Установка АВР» → «Установка модуля АВР (перемикача) на DIN-рейку» | 24 copies |
| rename «Свердління отвору … в капітальній стіні - від» → «… понад 30 см - від» | 24 copies (11 at their own price, renamed anyway as in V158), bundle «Електрика приватного будинку — чорнова», «consumes nothing» norm |
| rename «Обв'язка радіатора один радіатор 2 точки» → «Обв'язка радіатора (за точку; радіатор = 2 точки)» | 4 copies, bundle «Опалення радіатори» |
| rename «Установка штанги» → «Установка душової штанги з лійкою» (+ 300 → 800) | 4 copies |
| rename «Опломбування лічильника води» → «Організація опломбування лічильника води» | 4 copies, bundle «Квартира — вузол вводу…», «consumes nothing» norm |
| rename «Підйом будматеріалів на поверх» → «… (за мішок, за кожен поверх)» (+ 263 → 15) | 1 copy, «consumes nothing» norm |
| description «Прокладка труб в траншеї»: «Без копання траншеї (земляні роботи — окремо)» | library row, plus copies whose description was empty or still ours |

- **Renames** follow V158 verbatim.
- **Unit changes** were deliberately NOT done by V159-V164 («a unit change cannot reach a master's
  copy»). The owner ruled they move exactly what a rename moves: the library row, the default bundle
  lines, every master's LIBRARY copy (his price kept; a master who already owns the name in the new
  unit is left alone), and the lines of his forks.
- **Deletions** cross units, so V167's merge (which renames the copy into the survivor) cannot apply.
  Owner ruling: delete the library row only; masters' copies and estimate lines stay untouched. No
  bundle uses either row.

Every rename, unit change and deletion is recorded in `catalog_renamed_position` (13 rows,
`renamed_in = 'V176'`). Rows that also change price are repriced first, under their old name and
unit; the notice then follows the rename.

## 3. New positions

ONE new catalog version (26), and **nothing pushed** (owner: 67 positions unasked is too many). A
master takes them with «Додати нові з довідника» → «Знайдено N нових позицій», which offers the
templates above his `last_synced_catalog_version`. V176 does not advance that column (V158-V166 did,
because they pushed).

**No notice.** The owner asked for one informational notice («У довіднику з'явились нові позиції (N)
— переглянути») if the existing mechanism allows it. It does not. The only notice shape that counts
positions is `COUNT`, and it renders as «Ваш каталог оновлено — Додано N позицій», which would be
false when nothing was added. The «Додати нові» button only checks when tapped. An «available in the
library» notice needs a new notice kind and PWA rendering, so it is logged in open-questions.

Departures from the decision file:
- «Монтаж збірно-монолітного перекриття (Teriva)» names a brand, which a catalog position never does
  (CLAUDE.md). It ships as «Монтаж збірно-монолітного перекриття (балки та пустотілі блоки)».
- A new DEMOLITION category, «Зовнішні роботи» (owner ruling), holds both roof demolitions, the paving,
  the curb and the facade.

«Демонтаж змішувача» joins PLUMBING at 300 under the same wording DEMOLITION ships at 200: one shared
row with a price per trade. Near-twins in other trades are left as they are:
- FLOORING «Демонтаж ламінату» 130 vs DEMOLITION «Демонтаж ламінату або паркету» 25;
- PAINTER «Збивання старої штукатурки…» 250/200 vs DEMOLITION 60/90;
- FLOORING's warm floor 300 and thermostat 350 vs ELECTRICAL 450-600 and 600.

**The new positions carry no material norms** (the decision file has none). FLOORING was the one trade
pinned to «every position answers». `FlooringNormsOnLiveDataIntegrationTest` and
`FlooringCatalogReviewOnLiveDataIntegrationTest` now exclude catalog version 26 explicitly, and the
FLOORING count pin moved from 71 to 81. Logged in open-questions.

Position → category:

| Trade | Position | Unit | ₴ | Category |
|---|---|---|---|---|
| FLOORING | Монтаж електричної теплої підлоги (мат/кабель) | M2 | 300 | Підлога |
| FLOORING | Підключення теплої підлоги до терморегулятора | PIECE | 350 | Підлога |
| TILING | Облицювання кухонного фартуха плиткою | M2 | 850 | Укладання плитки |
| TILING | Облицювання відкосів плиткою (за м.п.) | LINEAR_METER | 800 | Укладання плитки |
| FLOORING | Демонтаж ламінату | M2 | 130 | Підлога |
| FLOORING | Демонтаж паркету / дерев'яної підлоги | M2 | 150 | Підлога |
| FLOORING | Демонтаж ПВХ-плитки / кварцвінілу | M2 | 250 | Підлога |
| TILING | Демонтаж керамічного плінтуса | LINEAR_METER | 260 | Підготовчі роботи |
| FLOORING | Встановлення маяків для стяжки | M2 | 120 | Стяжка |
| FLOORING | Стяжка з пінополістиролбетону | M2 | 450 | Стяжка |
| FLOORING | Укладання кварцвінілу ялинкою | M2 | 700 | Підлога |
| FLOORING | Радіусна (фігурна) підрізка ламінату | LINEAR_METER | 150 | Підлога |
| FLOORING | Укладання ламінату на стіну/стелю (на клей) | M2 | 530 | Підлога |
| FLOORING | Монтаж плінтуса поліуретанового (під фарбування) | LINEAR_METER | 250 | Плінтус |
| TILING | Укладання плитки на стелю (плюс % до м.кв.) | PERCENT | 50 | Укладання плитки |
| PAINTER | Поклейка шпалер на стелю | M2 | 240 | Шпалери |
| PAINTER | Ручна штукатурка стелі по маяках | M2 | 410 | Штукатурка |
| PAINTER | Машинне нанесення фінішної шпаклівки | M2 | 280 | Шпаклівка |
| PAINTER | Штукатурка ніш під радіатори | M2 | 470 | Штукатурка |
| PAINTER | Монтаж маяків / розмітка площин стелі | M2 | 140 | Штукатурка |
| PAINTER | Фактурна штукатурка «американка» | M2 | 460 | Декоративна штукатурка |
| PAINTER | Фарбування вікон (дерев'яних рам) | M2 | 450 | Фарбування |
| PAINTER | Фарбування плінтусів та лиштви | LINEAR_METER | 90 | Фарбування |
| PAINTER | Збивання старої штукатурки зі стін | M2 | 250 | Демонтаж |
| PAINTER | Збивання старої штукатурки зі стелі | M2 | 200 | Демонтаж |
| PAINTER | Очищення старої олійної фарби | M2 | 250 | Демонтаж |
| DRYWALL | Перепад рівня стелі з ГКЛ (прямий) | LINEAR_METER | 550 | Каркас і обшивка |
| DRYWALL | Перепад рівня стелі з ГКЛ (радіусний) | LINEAR_METER | 670 | Каркас і обшивка |
| DRYWALL | Обшивка стелі ГКЛ у 2 шари | M2 | 600 | Каркас і обшивка |
| ELECTRICAL | Установка та підключення світильника для стелі Армстронг | PIECE | 300 | Освітлення |
| ELECTRICAL | Складання та монтаж люстри складної (кришталевої, великої) | PIECE | 900 | Освітлення |
| ELECTRICAL | Перенесення електрощитка в квартиру | PIECE | 2700 | Щит |
| ELECTRICAL | Розробка схеми електропроводки | M2 | 150 | Підготовка |
| ELECTRICAL | Пошук прихованої проводки (детектором) | PIECE | 1000 | Підготовка |
| ELECTRICAL | Установка та підключення прожектора | PIECE | 400 | Освітлення |
| ELECTRICAL | Установка діммера (світлорегулятора) | PIECE | 300 | Розетки |
| ELECTRICAL | Підключення кухонної витяжки | PIECE | 500 | Підключення техніки |
| ELECTRICAL | Установка силової розетки для електроплити (32/40 А) | PIECE | 300 | Розетки |
| ELECTRICAL | Установка електромагнітного (електромеханічного) замка | PIECE | 1400 | Слаботочка |
| ELECTRICAL | Заміна пробок на автоматичні вимикачі | PIECE | 550 | Щит |
| ELECTRICAL | Година роботи електрика | HOUR | 450 | Інше |
| PLUMBING | Монтаж каналізаційної насосної станції (КНС) | PIECE | 3800 | Каналізація |
| PLUMBING | Установка пісуара (навісного) | PIECE | 1250 | Сантехприлади |
| PLUMBING | Установка проточного водонагрівача | PIECE | 1300 | Сантехприлади |
| PLUMBING | Заміна арматури в бачку унітазу | PIECE | 700 | Сантехприлади |
| PLUMBING | Демонтаж змішувача | PIECE | 300 | Сантехприлади |
| PLUMBING | Механічне прочищення каналізації | LINEAR_METER | 300 | Каналізація |
| PLUMBING | Пусконалагодження (запуск) котла | PIECE | 1200 | Котел |
| PLUMBING | Монтаж коаксіального (турбо) димоходу котла | PIECE | 900 | Котел |
| PLUMBING | Перенесення радіатора опалення | PIECE | 2100 | Опалення |
| PLUMBING | Монтаж газової колонки | PIECE | 2100 | Котел |
| PLUMBING | Установка кімнатного термостата / контролера котла | PIECE | 730 | Котел |
| DEMOLITION | Демонтаж покрівлі (металочерепиця, профнастил) | M2 | 225 | Зовнішні роботи |
| DEMOLITION | Демонтаж покрівлі з шиферу | M2 | 150 | Зовнішні роботи |
| DEMOLITION | Демонтаж тротуарної плитки | M2 | 170 | Зовнішні роботи |
| DEMOLITION | Демонтаж бордюру | LINEAR_METER | 120 | Зовнішні роботи |
| DEMOLITION | Демонтаж фасаду (утеплювач, облицювання) | M2 | 285 | Зовнішні роботи |
| DEMOLITION | Вирізання прорізу в бетонній стіні 100–150 мм | M2 | 1900 | Отвори |
| DEMOLITION | Вирізання прорізу в бетонній стіні 150–200 мм | M2 | 2500 | Отвори |
| BUILDER | Облицювання фасаду сайдингом | M2 | 420 | Фасад |
| BUILDER | Укладання цементно-піщаної черепиці | M2 | 480 | Покрівля |
| BUILDER | Монтаж мансардного вікна (у процесі будівництва) | PIECE | 4400 | Покрівля |
| BUILDER | Монтаж мансардного вікна в готову покрівлю | PIECE | 5400 | Покрівля |
| BUILDER | Монтаж снігозатримувачів | LINEAR_METER | 250 | Покрівля |
| BUILDER | В'язка арматури (за тонну) | T | 18300 | Фундамент |
| BUILDER | Монтаж збірно-монолітного перекриття (балки та пустотілі блоки) | M2 | 720 | Монтажні |
| BUILDER | Фігурне укладання тротуарної плитки (візерунки) | M2 | 720 | Благоустрій |

## Tests

`MarketPriceReviewOnLiveDataIntegrationTest` migrates a second database to V175, plants four masters,
migrates V176, and checks:
- prices per trade on a shared row (DRYWALL 150 / DEMOLITION 140; BUILDER «Грунтування» unchanged);
- a master on our old numbers gets exactly his own trades' notices (none for PAINTER's «Грунтування»
  on his BUILDER row); the DEMOLITION-filed copy of the same partition gets 60 → 140;
- a price the master set himself is left alone, and a pending notice is not doubled;
- a rename with a norm: his copy at HIS price, the default bundle line, the norm on both keys, 13
  `catalog_renamed_position` rows;
- a unit change: the copy and the bundle line in м.п., the tape norm at 1,0;
- a cross-unit duplicate leaves the library while his copy stays;
- 67 positions at one version, in nobody's catalog, with `last_synced_catalog_version` behind it, and
  five in «Зовнішні роботи»;
- against the head database: `CatalogTemplateService.countNewFromCatalog` offers a synced DEMOLITION
  master exactly 7.

Adjusted pins: `SeedCatalogInvariantsIntegrationTest` (TILING zeros 18 → 4) and the two FLOORING norm
tests (above).
