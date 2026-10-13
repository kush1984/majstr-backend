# Trade-by-trade review — the price anomalies, 2026-10-13

**Migration:** V157. **Source:** the «ціни-аномалії» of `C:\Work\prompts\trade-review\` 02-09, re-read
against the V156 library. **PWA:** no source change (version bump only).

## Shape

V120's: the shared library row (`catalog_templates.suggested_price`) moves, and every master still
carrying our OLD number on a LIBRARY row gets a `PRICE_DRIFT` notice; his price changes only on
«Прийняти» and only while it still equals the old number. A master who re-priced the position
himself gets nothing, and one already holding a pending notice for it gets no second.

## The fourteen prices (owner ruling: as recommended)

Anchored in our own catalog:

| Trade | Position | Old → new | Anchor |
|---|---|---|---|
| PAINTER | Захист вхідних дверей картоном | 0 → 200 | DEMOLITION's same wording |
| PLUMBING | Прокладання труб на хомутах (каналізація) | 25 → 220 | «Прокладка труб каналізації» |
| PLUMBING | Заробка штроб (опалення) | 53 → 100 | «Заробка штроб (сантехніка)» |
| PLUMBING | Прокладання труб на хомутах (опалення) | 53 → 180 | the water twin |
| PLUMBING | Шумоізоляція стояка каналізації, м.п. | 3000 → 300 | a riser's price on a metre |
| DRYWALL | Вирізка отворів в гіпсокартоні | 900 → 200 | ELECTRICAL's box hole in GKL |
| FLOORING | Чистка підлоги порохотягом підготовка | 421 → 60 | «Підготовка поверхні (очищення…)» |
| PAINTER | Грунтовка поверхні кварцгрунтом | 25 → 90 | «Грунтування кварцгрунтом» |
| DEMOLITION | Вирізка прорізу в цегляній стіні | 263 → 1000 | «Розширення отвору під вхідні двері» |

Looked up (Kyiv / Ukraine, work only):

| Trade | Position | Old → new | Market |
|---|---|---|---|
| DEMOLITION | Вантаження сміття в авто, м³ | 950 → 200 | loaders +100-250 ₴/м³ over haulage (Мурашка, MoveX, Podsobnik); 950 was haulage with loading, double-counting the container line |
| PAINTER | Монтаж 3 Д панелей з підготовкою під фарбування | 4500 → 800 | mounting 500-1200 ₴/м², seams +200 ₴/м.п. (BeautyWalls 2026, 3dpanel.kiev.ua) |
| PAINTER | Монтаж 3Д панелей | 80 → 500 | the same range |
| BUILDER | Бетонування армопоясу з міксера | 300 → 1100 | rabotniki 1068, budver 1155 (258 masters) |
| BUILDER | Бетонування армопоясу вручну | 1800 → 1200 | the same plus on-site mixing |

«Виготовлення кнопки з плитки» 13 316 stays (one Kyiv list: «від 21 600»).

## Left alone

The metal stair frame (900), «Підйом будматеріалів на поверх» (263) and the boiler hook-up per
POINT — what the price is for is a rename's question; the 500 ₴ meter duplicate leaves with the
de-duplication; BUILDER's 1350 × 19 and 630 × 5 and the 11 800/м² terrace have no source; TILING's
18 and PAINTER's 11 zeros are deliberate («договірна»).

## Tests

- `CatalogPriceAnomaliesOnLiveDataIntegrationTest` — a second database migrated to V156 with three
  planted masters: the fourteen library prices, the tile button untouched, a stale master offered
  every change, a self-priced master left alone (price and notice), a pending notice not doubled.
- `SeedCatalogInvariantsIntegrationTest` — PAINTER's deliberate zeros 12 → 11.
