# ELECTRICAL: 19 positions from a Lviv estimate — V216

**Status:** shipped 2026-11-19, `./gradlew build` green.
**Migration:** `V216__electrical_positions_from_lviv_estimate.sql` — catalog version 27.

## Source

A labour-only subcontract estimate for a residential complex near Lviv (Брюховичі): 90 lines plus a
31-line rate sheet, every price +10 % over the electrician's («матеріал Замовника» — the cable,
conduit and tray lines are laying prices, not materials). Compared line by line with our ELECTRICAL
library (≈170 work positions) on 2026-11-19.

**Its prices were NOT taken.** 126 flats, 9 km of conduit: wholesale rates 1,3–2× under our
private-renovation figures (socket 120 vs 140, cable ≤2,5 mm² 36 vs 50, 4–6 mm² 42 vs 90, panel
12–24 mod. 250–300 vs 800, earthing loop 3 500 vs 8 000). Useful only as a floor and as a sanity
check — «кабель у гофрі» comes out 27 + 36 ≈ 63 there, 65 here.

## What shipped — 19 jobs the library had no position for

Priced at apartment level from public price lists (Lviv: amperage.com.ua, electroimpulse.lviv.ua,
podkluch.com.ua; elsewhere: electricpro.od.ua, elektrika.lutsk.ua, klaster.ua, zoloti-ruky.com,
relicon.kiev.ua, rabotniki.ua), the estimate as a floor. Owner approved list and prices 2026-11-19.

| Category | Position | Unit | ₴ |
|---|---|---|---|
| Кабель | Прокладка силового кабелю перерізом 16–35 мм² | м.п. | 130 |
| Кабель | Прокладка силового кабелю перерізом від 50 мм² | м.п. | 180 |
| Кабель | Герметизація кабельної проходки вогнестійкою піною | шт | 300 |
| Кабель | Монтаж кабельного лотка шириною від 400 мм | м.п. | 320 |
| Кабель | Монтаж кришки кабельного лотка | м.п. | 100 |
| Кабель | Монтаж повороту або трійника кабельного лотка | шт | 250 |
| Кабель | Монтаж перегородки в кабельному лотку | м.п. | 100 |
| Щит | Підключення жили кабелю до затискача, до 10 мм² | шт | 100 |
| Щит | Підключення жили кабелю до затискача, 16–35 мм² | шт | 160 |
| Щит | Підключення жили кабелю до затискача, 50–95 мм² | шт | 300 |
| Щит | Установка розетки на дин-рейку | шт | 250 |
| Заземлення | Прокладання сталевої штаби заземлення | м.п. | 200 |
| Заземлення | Приєднання обладнання до контуру заземлення | шт | 300 |
| Заземлення | Монтаж коробки зрівнювання потенціалів (КЗП) | шт | 400 |
| Освітлення | Установка та підключення лінійного світильника (підвісного або накладного) | шт | 450 |
| Освітлення | Установка та підключення LED-панелі (накладної або підвісної) | шт | 400 |
| Освітлення | Установка аварійного (евакуаційного) світильника | шт | 350 |
| Вимірювання | Виконавча документація: схеми і протоколи вимірювань | компл. | 4 000 |
| Організаційні послуги | Виїзд (перебазування) бригади на обʼєкт | шт | 800 |

Descriptions only where a name could be mistaken for an existing position: the three «Підключення
жили» (per core; crimping is «Опресовка наконечників» apart), the wide tray (≤300 mm is the plain
«Монтаж кабельного лотка»), the documentation set (don't add it beside itemised measurements), the
fire foam (foam is a separate material).

## Shape — V176's, for additions

- ONE new catalog version, **nothing pushed**: a master takes them with «Додати нові з довідника»;
  `last_synced_catalog_version` is not advanced; no notice (the «available in the library» notice
  is still an open question).
- Existing categories only; no brand in a name (the estimate's FOCUS / Trinix / FLAME-X stayed out).
- A position already in ELECTRICAL is skipped with a WARNING; the self-check raises only about this
  migration's own rows. V118's ranking re-run (V120's rule).

## Not changed

- No existing position, price, bundle or norm touched.
- **No material norms** for the 19 — they join the open question «V176's new positions have no
  material norms».

## Tests

- `ElectricalLvivPositionsIntegrationTest` — 19 under one version, prices, existing categories only,
  a description, a synced electrician offered exactly 19 by `countNewFromCatalog`.
- `MarketPriceReviewOnLiveDataIntegrationTest.aSyncedMasterSeesTheNewPositionsAsNewFromTheLibrary`
  read the head's version minus one; it now reads V176's own version (a later addition bumps the head).
