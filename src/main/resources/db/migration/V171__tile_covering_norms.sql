-- =================================================================================================
-- V171 — the tile itself: «Матеріали» counted the adhesive, the grout and the primer for a tiling
-- position, and never the tile. The public tile calculator's first question is «скільки плитки
-- купити», and the app answers it from the SAME norm (seo-calculators, tile addendum).
--
-- ONE MATERIAL PER FORMAT, packaged by the PIECE. A bathroom routinely mixes 300×300 on the floor
-- with 300×600 on the walls; one «Плитка» material would add the two into one figure nobody can buy.
-- A format of its own keeps them apart, and its `package_size` is one tile's area — so the engine's
-- existing «round UP to a whole package» IS the piece count, with no new arithmetic. Where the
-- position names no single size (mosaic, «кабанчик», a plank «до 900 мм», anything past 3200 mm)
-- the package is NULL and the answer stays in whole m², as V147 did for every covering.
--
-- THE COEFFICIENT IS 1,00 — the tile covers the area it is laid on. Cutting loss is the master's
-- own allowance (his `WASTE_PERCENT`, or the visitor's 5/10/15 %), applied on top exactly as for
-- laminate; `waste_percent` stays 0 for the reason V147 gives. A DIAGONAL course is not a norm here:
-- the catalog prices it as a «%» line («Укладання плитки по діагоналі (плюс % до м.кв.)») riding on
-- the format position, so the engine applies V147's laminate figure — 1,05 — to the tile of the
-- position that line points at (`MaterialCalculatorService.DIAGONAL_LAYOUT`).
--
-- sort_order 0: the tile is the first line of the list, before the adhesive (1) and the grout (3).
--
-- NO `catalog_templates` ROW IS INSERTED (so V118's ranking need not be re-run) and no existing norm
-- is touched, so a master's forks — keyed by material inside `ux_material_norm` — hide nothing new.
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'CERAMIC_TILE_100X100',   'Плитка', '100×100 мм',   'M2', 0.010, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_200X200',   'Плитка', '200×200 мм',   'M2', 0.040, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_300X300',   'Плитка', '300×300 мм',   'M2', 0.090, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_300X600',   'Плитка', '300×600 мм',   'M2', 0.180, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_300X900',   'Плитка', '300×900 мм',   'M2', 0.270, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_600X600',   'Плитка', '600×600 мм',   'M2', 0.360, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_600X1200',  'Плитка', '600×1200 мм',  'M2', 0.720, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_800X800',   'Плитка', '800×800 мм',   'M2', 0.640, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_800X1600',  'Плитка', '800×1600 мм',  'M2', 1.280, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_1000X1000', 'Плитка', '1000×1000 мм', 'M2', 1.000, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_1000X2000', 'Плитка', '1000×2000 мм', 'M2', 2.000, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_1000X3000', 'Плитка', '1000×3000 мм', 'M2', 3.000, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_1200X1200', 'Плитка', '1200×1200 мм', 'M2', 1.440, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_1200X2400', 'Плитка', '1200×2400 мм', 'M2', 2.880, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_1200X3200', 'Плитка', '1200×3200 мм', 'M2', 3.840, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_1500X3000', 'Плитка', '1500×3000 мм', 'M2', 4.500, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_1600X1600', 'Плитка', '1600×1600 мм', 'M2', 2.560, 'M2', 'плитка'),
    (gen_random_uuid(), 'CERAMIC_TILE_1600X3200', 'Плитка', '1600×3200 мм', 'M2', 5.120, 'M2', 'плитка'),
    -- ---- No single size in the name: sold and counted by the m² --------------------------------
    (gen_random_uuid(), 'CERAMIC_TILE_OVERSIZE',  'Плитка', 'понад 3200 мм', 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'CERAMIC_TILE_PLANK',     'Плитка-дошка', NULL,       'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'CERAMIC_TILE_METRO',     'Плитка «кабанчик»', NULL,  'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'CERAMIC_TILE_MOSAIC',    'Мозаїка', NULL,            'M2', NULL, NULL, NULL);

INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'TILING', v.name_key, 'M2', m.id, 1.0, 'QUANTITY', 0
FROM (VALUES
    ('укладання плитки 100х100',          'CERAMIC_TILE_100X100'),
    ('укладання плитки 200х200',          'CERAMIC_TILE_200X200'),
    ('укладання плитки 300х300',          'CERAMIC_TILE_300X300'),
    ('укладання плитки 300х600',          'CERAMIC_TILE_300X600'),
    ('укладання плитки 300х900',          'CERAMIC_TILE_300X900'),
    ('укладання плитки 600х600',          'CERAMIC_TILE_600X600'),
    ('укладання плитки 600х1200',         'CERAMIC_TILE_600X1200'),
    ('укладання плитки 800х800',          'CERAMIC_TILE_800X800'),
    ('укладання плитки 800х1600',         'CERAMIC_TILE_800X1600'),
    ('укладання плитки 1000х1000',        'CERAMIC_TILE_1000X1000'),
    ('укладання плитки 1000х1000 мм',     'CERAMIC_TILE_1000X1000'),
    ('укладання плитки 1000х2000 мм',     'CERAMIC_TILE_1000X2000'),
    ('укладання плитки 1000х3000 мм',     'CERAMIC_TILE_1000X3000'),
    ('укладання плитки 1200х1200 мм',     'CERAMIC_TILE_1200X1200'),
    ('укладання плитки 1200х2400 мм',     'CERAMIC_TILE_1200X2400'),
    ('укладання плитки 1200х3200 мм',     'CERAMIC_TILE_1200X3200'),
    ('укладання плитки 1500х3000 мм',     'CERAMIC_TILE_1500X3000'),
    ('укладання плитки 1600х1600 мм',     'CERAMIC_TILE_1600X1600'),
    ('укладання плитки 1600х3200 мм',     'CERAMIC_TILE_1600X3200'),
    ('укладання плитки більше 3200 мм',   'CERAMIC_TILE_OVERSIZE'),
    ('укладання плитки дошка до 900 мм',  'CERAMIC_TILE_PLANK'),
    ('укладання плитки дошка до 1200 мм', 'CERAMIC_TILE_PLANK'),
    ('укладання плитки дошка до 1800 мм', 'CERAMIC_TILE_PLANK'),
    ('укладання плитки кабанчик, «цегла»', 'CERAMIC_TILE_METRO'),
    ('укладання мозаїки',                 'CERAMIC_TILE_MOSAIC')
) AS v(name_key, code)
JOIN material m ON m.code = v.code;

DO $$
DECLARE
    inserted int;
    unknown  text;
BEGIN
    SELECT count(*) INTO inserted
    FROM material_norm n JOIN material m ON m.id = n.material_id
    WHERE n.owner_id IS NULL AND m.code LIKE 'CERAMIC\_TILE\_%';
    IF inserted <> 25 THEN
        RAISE EXCEPTION 'V171: expected 25 tile norms, found %', inserted;
    END IF;

    -- Every name must be one the TILING adhesive is already normed under — the same positions,
    -- spelled the same way. About data an admin can rename between deploys, hence a WARNING.
    SELECT string_agg(DISTINCT n.name_key, ', ') INTO unknown
    FROM material_norm n JOIN material m ON m.id = n.material_id
    WHERE n.owner_id IS NULL AND m.code LIKE 'CERAMIC\_TILE\_%'
      AND NOT EXISTS (SELECT 1 FROM material_norm a JOIN material am ON am.id = a.material_id
                      WHERE a.owner_id IS NULL AND a.name_key = n.name_key AND a.unit = n.unit
                        AND am.code LIKE 'TILE\_ADHESIVE%');
    IF unknown IS NOT NULL THEN
        RAISE WARNING 'V171: tile norms on positions with no adhesive norm: %', unknown;
    END IF;
END $$;
