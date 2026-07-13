# Iteration — "Металоконструкції" (METAL) trade

A master asked for a dedicated metalwork trade with as many default positions as
possible and a few ready estimate templates. Follows the BUILDER (V26) / tetris
(V50) pattern exactly — additive, immutable-old-migrations, catalog + templates as
plain data a fabricator refines.

## Scope

- New `Trade.METAL` enum constant + every trade CHECK extended (`user_trades`,
  `catalog_templates`, `catalog_items`, `estimate_templates`).
- **66 default catalog positions** (46 WORK + 20 MATERIAL) at
  `added_in_version = 6` (V50 used 5) across 9 categories: Ворота та хвіртки,
  Паркани та огорожі, Навіси та козирки, Сходи та огородження, Каркаси та ферми,
  Ковані та декоративні вироби, Зварювальні та монтажні роботи, Обробка та
  покриття, Матеріали (метал).
- **7 estimate templates** (39 items): Ворота відкатні, Паркан металевий, Навіс
  металевий, Сходи та перила, Каркас та ферми, Козирок над входом, Ковані ґрати на
  вікна.

## Pricing / units

- Prices are **orientative** UAH hints (2026) a fabricator edits — metalwork is
  priced by the kg of structure (`Виготовлення/Монтаж металоконструкцій` → KG),
  linear metre (railings, fences, profile pipe), m² (gates, mesh, sheet), piece,
  tonne (`Виготовлення каркасу ангара` → T), or welder hour (`Зварювальні роботи`).
  No new units needed — all fit the existing set.

## Migration mechanics (V54)

- Trade CHECKs dropped/recreated with METAL added (old migrations immutable).
- Catalog rows via `gen_random_uuid()` (same as V50).
- **Estimate templates + items without literal UUIDs:** templates inserted with
  `gen_random_uuid()`; items inserted with a `JOIN (VALUES …) ON t.name = v.tpl AND
  t.trade='METAL' AND t.is_default` so each item links to its template by name (no
  hand-written UUIDs, no randomness in app code). Every template-item name matches
  a catalog name exactly, so each master's price resolves from their own catalog at
  apply-time.

## Validation

- Dry-run on the live dev DB inside `BEGIN … ROLLBACK`: 66 catalog / 7 templates /
  39 items inserted, per-template item counts correct, and **0 template items with
  no matching catalog name** (price resolution guaranteed). Constraints accepted
  METAL everywhere.
- `WebSearch` was unavailable (session limit) at authoring time; positions/prices
  come from domain knowledge of the Ukrainian metalwork market and are explicitly
  orientative (the master refines them, same as every default catalog).

## PWA (v0.9.2)

- `Trade` union +`METAL`; `TRADE_EMOJI.METAL = '⚙️'`; `TRADE_VALUES` (register
  picker) +`METAL`; i18n `trades.METAL` = "Металоконструкції" / "Metalwork".

## Follow-ups

- Prices are placeholders — worth a real market pass (or letting the admin catalog
  editor tune them) once a metalworker uses it.
