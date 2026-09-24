-- =================================================================================================
-- V139 - the AREA behind a painted moulding, baguette and door. V138's one remaining question.
--
-- V138 normed three coatings and listed three families it still refused. Two of the three are still
-- refused for the reason it gave (an epoxy grout's published figures span two orders of magnitude;
-- a decorative plaster's spread threefold inside one product name). The third was never a rate at
-- all: the litres per m2 for painting a moulding or a door were already in the dictionary, and what
-- was missing was the AREA - how many m2 one metre of «багет до 6 см» presents, or one door. The
-- master bills them by the metre and by the piece and does not carry that figure in his head
-- either, so this file derives it, writes the derivation down, and leaves every number he can
-- disagree with editable: a coefficient is the master's and forks on write (open-questions 21), and
-- the figure a SECTION norm asks for arrives PRE-FILLED AND VISIBLE, never applied silently
-- (open-questions 15). This supersedes V138's «stays refused» paragraph for this family only.
--
-- 1. THE METHOD IS THE TRADE'S OWN, NOT AN INVENTION HERE
--
--    Converting a cornice's running metres to m2 is done by multiplying its length by the profile's
--    розгортка - the developed width of the painted outline. It is how the Ukrainian norm books do
--    it (ДБН Д.2.4-12-2000 tabulates cornice painting against the developed width; ДБН Д.2.5-10-2001
--    applies a coefficient of 1,6 to a ribbed surface measured over its projection), and it is
--    exactly the question this calculator already asks for a reveal: V137's «фарбування укосів» is
--    a SECTION norm whose section is the reveal's width. A moulding is the same shape of question,
--    so it needs no Java at all - NormBasis.SECTION already means «length x param x qty_per_unit».
--
-- 2. WHERE THE SUGGESTED SECTIONS COME FROM
--
--    Two unrelated manufacturers publish their whole profile range, and both were read off rather
--    than guessed at:
--      NMC NOMASTYL (height x width, mm): A 110x110, A1 80x80, A2 50x50, A3 30x30, AT 100x95,
--        B1 66x78, B2 35x35, B5 50x50, BW1 65x60, C 83x66, D 49x42, E 25x17.
--      Orac Decor Purotouch (cm): 2,9x2,9; 6,5x5,7; 8x8; 7,6x11,6; 11,6x4,8; 11,6x11,2;
--        10,3x15,6; 17,6x13.
--    A cornice is glued along its two flanks and painted across the profiled face between them, so
--    the painted width follows the OUTLINE and not the straight line across it: ~1,15 x
--    sqrt(h^2 + w^2), the 1,15 being the profiling. For the near-square sections both tables are
--    full of, that is ~1,6 x the nominal size a position name gives; it reproduces both tables
--    inside ~10 % and always lands slightly high, which is the direction to err in - a high figure
--    costs paint left in the tin, a low one costs a second drive to the merchant.
--
--      «до 6 см» -> 0,10 m      «до 8 см» -> 0,13 m      «6-10 см» -> 0,16 m
--      «10+ см»  -> 0,25 m (the name has no upper bound; 15-17 cm is where the catalogues stop)
--      no size in the name -> 0,12 m (7-8 cm is the commonest ceiling cornice, so this is the
--        middle of the range rather than a figure pretending to describe a particular profile)
--
--    None of these is a multiplier applied behind his back. Each is a default_param: it lands in
--    the field the position asks him to fill, already filled in and labelled, and he types over it
--    for the 20 cm profile in the hall.
--
-- 3. A DOOR IS A PIECE, AND ITS AREA IS NOT WORTH ASKING FOR
--
--    A leaf is 0,8 x 2,0 = 1,6 m2 plus ~0,2 for the edges and the rebate, so ~2,0 m2 per side -
--    which is already THIS catalog's own door figure: V137 buys 2,0 m2 of cardboard to mask one
--    entrance door. A leaf does not vary the way a profile does, so a SECTION question here would
--    buy nothing but an empty field on a phone in a merchant's yard; the area is folded into the
--    coefficient instead, where the ordinary fork still lets him correct it - permanently, once,
--    rather than on every estimate.
--      «...прих. монтажу (одна сторона)»                    2,0 m2 -> paint 0,44 l, primer 0,30 l
--      «...(підготовка і фарбування з двох сторін)»         4,0 m2 -> paint 0,88 l, primer 0,60 l
--      «Фарбування дверей» (an ordinary door): leaf both sides 3,2 + edges 0,2 + frame and casings
--        on both sides ~0,9 = ~4,2 m2 -> enamel 0,92 l.
--    The materials differ on purpose. A door of прихованого монтажу is finished flush with the wall
--    and painted with the wall's own paint - that is the entire point of it - so it takes
--    PAINT_INTERIOR over the deep primer, like any other puttied surface here. An ordinary door is
--    wood or MDF and takes the enamel V138 shipped at 0,22 l/m2; it carries no primer row, because
--    a wood primer is not in this dictionary and the deep primer above it is for mineral surfaces.
--
--    Every PAINT_ coefficient is rescaled by the master's own paint habit (PAINT_COVERAGE x
--    PAINT_COATS) as a RATIO, so a figure written in litres per PIECE rescales with the rest. It is
--    no part of the «the shipped figure and the Java constants are one statement made twice» guard,
--    which is about interior paint written per m2.
--
-- 4. WHAT IS STILL LEFT OUT OF THIS FAMILY
--
--    «Фарбування плінтуса прихованого монтажу (перед монтажем)». It is painted off the wall before
--    it goes up, so whether the back and the return get a coat is the fitter's habit and not
--    something a norm can state, and the profile is usually anodised aluminium, which wants an
--    adhesion primer this dictionary does not carry. A deep primer on aluminium is not a small
--    error, so the row stays unnormed rather than half right.
--
-- No catalog_templates row is inserted, so V118's ranking is not re-run.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. Mouldings and baguettes: SECTION, suggested per the bands in header point 2.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order, default_param)
SELECT gen_random_uuid(), 'PAINTER', v.name_key, v.unit, m.id, v.qty, 'SECTION', v.ord, v.def
FROM (VALUES
    ('фарбування молдинга/багета до 6 см', 'LINEAR_METER', 'PAINT_INTERIOR', 0.22::numeric, 1, 0.10::numeric),
    ('фарбування молдинга/багета до 6 см', 'LINEAR_METER', 'PRIMER_DEEP',    0.15,          2, 0.10),
    ('фарбування молдинга/багета 6–10 см', 'LINEAR_METER', 'PAINT_INTERIOR', 0.22,          1, 0.16),
    ('фарбування молдинга/багета 6–10 см', 'LINEAR_METER', 'PRIMER_DEEP',    0.15,          2, 0.16),
    ('фарбування молдинга/багета 10+ см',  'LINEAR_METER', 'PAINT_INTERIOR', 0.22,          1, 0.25),
    ('фарбування молдинга/багета 10+ см',  'LINEAR_METER', 'PRIMER_DEEP',    0.15,          2, 0.25),
    ('фарбування стельових багетів (простих - з пінопласту) до 8 см', 'LINEAR_METER', 'PAINT_INTERIOR', 0.22, 1, 0.13),
    ('фарбування стельових багетів (простих - з пінопласту) до 8 см', 'LINEAR_METER', 'PRIMER_DEEP',    0.15, 2, 0.13),
    ('фарбування стельових багетів, молдінгів (поліуретанових) до 6 см', 'LINEAR_METER', 'PAINT_INTERIOR', 0.22, 1, 0.10),
    ('фарбування стельових багетів, молдінгів (поліуретанових) до 6 см', 'LINEAR_METER', 'PRIMER_DEEP',    0.15, 2, 0.10),
    ('фарбування стельових багетів пінопласт',      'LINEAR_METER', 'PAINT_INTERIOR', 0.22, 1, 0.12),
    ('фарбування стельових багетів пінопласт',      'LINEAR_METER', 'PRIMER_DEEP',    0.15, 2, 0.12),
    ('фарбування стельових багетів поліуретанових', 'LINEAR_METER', 'PAINT_INTERIOR', 0.22, 1, 0.12),
    ('фарбування стельових багетів поліуретанових', 'LINEAR_METER', 'PRIMER_DEEP',    0.15, 2, 0.12)
) AS v(name_key, unit, code, qty, ord, def)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 2. Doors: QUANTITY, with the area of header point 3 folded into the litres per piece.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'PAINTER', v.name_key, v.unit, m.id, v.qty, 'QUANTITY', v.ord
FROM (VALUES
    ('фарбування дверей прих. монтажу (одна сторона)', 'PIECE', 'PAINT_INTERIOR', 0.44::numeric, 1),
    ('фарбування дверей прих. монтажу (одна сторона)', 'PIECE', 'PRIMER_DEEP',    0.30,          2),
    ('фарбування дверей прихованого монтажу (підготовка і фарбування з двох сторін)', 'PIECE', 'PAINT_INTERIOR', 0.88, 1),
    ('фарбування дверей прихованого монтажу (підготовка і фарбування з двох сторін)', 'PIECE', 'PRIMER_DEEP',    0.60, 2),
    ('фарбування дверей', 'PIECE', 'ENAMEL_WOOD', 0.92, 1)
) AS v(name_key, unit, code, qty, ord)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 3. Self-checks. The two V137/V138 run - a norm must name a live catalog position, and the count
--    must be what this file wrote - plus one this file needs of its own: a metre row that is not
--    SECTION, or is SECTION with nothing to suggest, re-opens the very gap the file closes, and it
--    would do it silently. `MaterialCalculatorIntegrationTest` covers the other direction.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    keys text[] := ARRAY[
        'фарбування молдинга/багета до 6 см',
        'фарбування молдинга/багета 6–10 см',
        'фарбування молдинга/багета 10+ см',
        'фарбування стельових багетів (простих - з пінопласту) до 8 см',
        'фарбування стельових багетів, молдінгів (поліуретанових) до 6 см',
        'фарбування стельових багетів пінопласт',
        'фарбування стельових багетів поліуретанових',
        'фарбування дверей прих. монтажу (одна сторона)',
        'фарбування дверей прихованого монтажу (підготовка і фарбування з двох сторін)',
        'фарбування дверей'
    ];
    orphans text;
    added int;
    silent text;
BEGIN
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO orphans
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade = 'PAINTER'
       AND n.name_key = ANY (keys)
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.trade = 'PAINTER'
               AND t.type = 'WORK'
               AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V139: material_norm rows name no live catalog position: %', orphans;
    END IF;

    SELECT count(*) INTO added
      FROM material_norm n
     WHERE n.owner_id IS NULL AND n.trade = 'PAINTER' AND n.name_key = ANY (keys);
    IF added <> 19 THEN
        RAISE EXCEPTION 'V139: expected 19 norms on the moulding and door positions, found %', added;
    END IF;

    SELECT string_agg(DISTINCT n.name_key, ', ')
      INTO silent
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade = 'PAINTER'
       AND n.name_key = ANY (keys)
       AND ((n.unit = 'LINEAR_METER' AND (n.basis <> 'SECTION' OR n.default_param IS NULL))
         OR (n.unit = 'PIECE' AND n.basis <> 'QUANTITY'));
    IF silent IS NOT NULL THEN
        RAISE EXCEPTION 'V139: a metre that asks for no section, or a piece that asks at all: %', silent;
    END IF;
END $$;
