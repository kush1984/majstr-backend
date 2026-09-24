-- =================================================================================================
-- V138 - four PAINTER positions V137 left unanswered, and the three coatings they buy.
--
-- V137 normed 95 of the 233 painting positions and listed what it deliberately left alone. Most of
-- that list is still right: a decorative plaster is a different product with a different rate for
-- every name on it, and an epoxy grout's published figures span two orders of magnitude. But three
-- coatings in that list turned out to be ordinary once they were actually looked up - the sources
-- agree inside a narrow band, exactly the standard the drywall and tiling rounds used.
--
-- WHAT IS ADDED, AND WHERE EACH FIGURE COMES FROM
--
--   1. FACADE PAINT - 0,35 l/m2 over the two coats every manufacturer specifies.
--      Ceresit CT 42 Acrylic Elastic and CT 48 Silicone Self Clean: 5-8 m2/l on an ABSORBING
--      surface (= 125-200 ml/m2 per coat), 9-11 on a non-absorbing one, applied in two layers.
--      Caparol Muresko and AmphiSilan-plus: ~150-200 ml/m2 per coat on a SMOOTH surface, more on a
--      textured one. Two unrelated manufacturers overlapping at 150-200 ml/m2; a real facade is
--      rendered, so the absorbing/textured end is the honest one: 175 ml/m2 x 2 coats = 0,35.
--      Plus the deep primer every one of them requires under the first coat, at the 0,15 l/m2 this
--      catalog already uses for it - the same shape as V137's interior painting positions.
--
--   2. ENAMEL on wood - 0,22 l/m2, the same arithmetic as interior paint and, as it happens, the
--      same answer. Sniezka Supermal acrylic enamel «до 12 м²/л» in 1-2 coats; Eskaro Condor Aqua
--      Email 30 «6-10 м²/л» in 2-3; Maxima acrylic enamel for wood and metal 80-125 ml/m2 per coat
--      (8-12,5 m2/l). Band 6-12,5, middle ~9 m2/l, two coats -> 0,22 l/m2.
--
--   3. CLEAR VARNISH on concrete and microcement - 0,20 l/m2 over two coats. Aura Aqua Lack 70:
--      8-10 m2/l per coat in 2-3 layers (0,10-0,125 l/m2 per coat); Ukrainian PU/acrylic concrete
--      and microcement varnishes: 70-120 g/m2 per coat, density ~1,0. Both land on ~0,1 per coat.
--
-- WHAT IS STILL LEFT OUT, AND WHY IT STAYED OUT AFTER BEING LOOKED UP
--
--   - EPOXY GROUT. Ceresit CE 79's own table spans 0,08-12,40 kg/m2 across formats; Litokol
--     Starlike is quoted at ~1,6 kg/m2 for a 15x15 mosaic at a 2 mm joint, where our CEMENT figure
--     for the same geometry is 0,6. Epoxy is not cement x a constant - most of the difference is
--     the washing loss, which no sheet states. An invented number for the most expensive grout on
--     the market is worse than an honest gap, and it stays one.
--   - DECORATIVE PLASTERS beyond короїд/баранець. Microcement: base 0,4-1,4 kg/m2 per coat, finish
--     1,2-3,5 over two «depending on the desired effect». Venetian: 0,5-1,0 kg/m2. A threefold
--     spread inside one product name is not a norm, it is a range the master has to pick from.
--   - PAINTING A MOULDING, A BAGUETTE OR A DOOR. The coating rate above answers these too - what
--     is missing is the AREA: how many m2 a metre of «багет до 6 см» presents, or a door leaf. No
--     sheet states it and the position name gives only a width band, so a figure here would be our
--     geometry wearing a manufacturer's authority. The rate is now in the dictionary, which is what
--     the answer will be built on when the master gives us the areas he bills against.
--
-- Filed under PAINTER, never trade-less. BUILDER also ships «Фарбування фасаду» and FLOORING ships
-- three lacquering positions, but those trades have NO norms at all: a trade-less norm would flip
-- `GET /materials/availability` ON for them and offer a buying list of one line out of forty, which
-- the availability rule exists to prevent.
--
-- No catalog_templates row is inserted, so V118's ranking is not re-run.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. Dictionary. Packaging is the smallest size commonly on the shelf - V137's rule, because
--    rounding up to a package he does not need is the error the master pays for.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'PAINT_FACADE',  'Фарба фасадна',              NULL, 'LITRE', 10,   'LITRE', 'відро'),
    (gen_random_uuid(), 'ENAMEL_WOOD',   'Емаль для дерева і металу',  NULL, 'LITRE', 0.75, 'LITRE', 'банка'),
    (gen_random_uuid(), 'VARNISH_CLEAR', 'Лак захисний прозорий',      NULL, 'LITRE', 1,    'LITRE', 'банка');

-- -------------------------------------------------------------------------------------------------
-- 2. The norms.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'PAINTER', v.name_key, v.unit, m.id, v.qty, 'QUANTITY', v.ord
FROM (VALUES
    ('фарбування фасаду', 'M2', 'PAINT_FACADE', 0.35::numeric, 1),
    ('фарбування фасаду', 'M2', 'PRIMER_DEEP',  0.15,          2),
    ('фарбування дерев''яної вагонки', 'M2', 'ENAMEL_WOOD',   0.22, 1),
    ('лакування бетонних стін',        'M2', 'VARNISH_CLEAR', 0.20, 1),
    ('захисне лакування мікроцементу', 'M2', 'VARNISH_CLEAR', 0.20, 1)
) AS v(name_key, unit, code, qty, ord)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 3. Self-checks. Same two V137 runs: a norm must name a live position, and the count must be what
--    this file wrote. `MaterialCalculatorIntegrationTest` covers the other direction.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    orphans text;
    added int;
BEGIN
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO orphans
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade = 'PAINTER'
       AND n.name_key IN ('фарбування фасаду', 'фарбування дерев''яної вагонки',
                          'лакування бетонних стін', 'захисне лакування мікроцементу')
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.trade = 'PAINTER'
               AND t.type = 'WORK'
               AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V138: material_norm rows name no live catalog position: %', orphans;
    END IF;

    SELECT count(*) INTO added
      FROM material_norm n
      JOIN material m ON m.id = n.material_id
     WHERE n.owner_id IS NULL
       AND m.code IN ('PAINT_FACADE', 'ENAMEL_WOOD', 'VARNISH_CLEAR');
    IF added <> 4 THEN
        RAISE EXCEPTION 'V138: expected 4 norms on the three new coatings, found %', added;
    END IF;
END $$;
