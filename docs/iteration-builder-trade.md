# New trade "Будівельник" (BUILDER) + construction units + starter set

- **Status:** ✅ Backend + PWA complete. Migrations V25/V26 validated against the
  restored prod schema (apply cleanly); PWA `tsc -b` clean, vitest green.
- **Migration:** V26 (trade/unit CHECKs + BUILDER template seed). (V25 was the
  estimate-UX iteration; both verified together on the V24 backup.)
- **Goal:** Add a builder trade (foundation/masonry/reinforcement) with a starter
  catalog skeleton, plus the units construction needs (м³, т).

## Units (м³ / т)

`Unit` enum gained **`M3`** (м³) and **`T`** (тонна) — concrete is priced per m³,
rebar/sand/gravel per tonne. V26 extends the unit CHECK on `catalog_items`,
`estimate_items`, `catalog_templates` (drop+recreate, additive — existing rows
untouched). PWA: `Unit` type, `UNIT_OPTIONS`, both item zod enums, and
`units.*`/`unitOptions.*` labels (uk+en) gained M3/T, so they show in every unit
picker (catalog + estimate item).

## Trade (BUILDER)

`Trade` enum gained **`BUILDER`** (after TILING). V26 extends the trade CHECK on
`user_trades` and `catalog_templates`. PWA: `Trade` type, `TRADE_VALUES` (the
shared register/profile picker list), `TRADE_EMOJI` (🏗️), and `trades.BUILDER`
label (uk "Будівельник" / en "Builder"). It therefore shows in registration, the
profile trade editor, and the trade badge — all read from the same sources.

`reset-from-template` / `seedForUser` need **no change** — they copy templates via
`templateRepository.findByTradeIn(user.getTrades())`, so a BUILDER user's set is
picked up like any other. The Fix-J `findWithTradesById` `@EntityGraph` is
untouched, so no lazy-init regression.

## Starter set (skeleton — placeholder prices)

28 `catalog_templates` rows for BUILDER, seeded as **data** (trivial to edit):
- **WORK (17):** Земляні та фундамент (розробка ґрунту м³, опалубка м², в'язання
  арматури т, заливка бетону м³, гідроізоляція м²), Мурування (цегла/газоблок м³,
  перегородки м², перемички м.п.), Перекриття та покрівля (монолітне/збірне/
  крокви/покрівля м²), Оздоблення та демонтаж (стяжка/штукатурка/демонтаж м²).
- **MATERIAL (11):** Бетон товарний м³, Цемент (мішок) шт, Пісок/Щебінь т, Цегла
  шт, Газоблок м³, Арматура т, Сітка армувальна м², Дошка/Брус м³, Гідроізоляція м².

⚠️ **Prices are placeholders** — a practising builder refines them. The choice of
unit per item (masonry m³ vs m², rebar t vs lin.m) is the skeleton's best guess
and to be confirmed with a master (engages them as a co-author).

## Not changed / follow-up (open-questions)

- **Material metrics per object** (sum of concrete/brick/rebar used) — a builder's
  idea, logged as OPEN. Needs the master to clarify: per-estimate or per-object
  (all estimates)? plan (estimated) or actual? Likely a simple first cut (group
  MATERIAL items of a project's estimates by name+unit) — data already exists —
  but confirm the exact want first.

## Verify

1. Registration / profile → "Будівельник" in the trade list.
2. Pick BUILDER → "Стартовий набір" → catalog fills with construction works +
   materials (м³/т where relevant).
3. м³ (M3) and т (T) available when adding a catalog/estimate item.
4. Other trades and their starter sets unchanged.
5. `reset-from-template` works, no `lower(bytea)`/lazy-init in Sentry.
