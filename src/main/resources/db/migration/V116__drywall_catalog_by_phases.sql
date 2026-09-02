-- DRYWALL: one wording per job, a catalog ordered by the PHASES of the work, and the finishing
-- chain the trade actually sells.
--
-- ============ WHY THIS TOUCHES SO MUCH ========================================================
-- The DRYWALL catalog was written by two import waves that never met. V27/V31 seeded one set of
-- wordings; the tetris import (V50) seeded a second set for the SAME eight jobs, at different
-- prices, and V50's "punctuation-insensitive" dedupe could not see it because the two wordings
-- differ by words, not by punctuation. The bundles were then split across both sets, so:
--
--     «Монтаж радіусної перегородки ГКЛ в 1 шар»                          240 ₴   (bundle A)
--     «Монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар» 900 ₴   (bundle B)
--
-- Two masters quoting the same radius partition got 240 and 900 depending on which bundle they
-- tapped. That is not a cosmetic duplicate: it also starves price_insight_candidate (V94), whose
-- crowd median is computed PER NAME and needs at least 3 masters agreeing on one — splitting a
-- job's usage across two names halves the signal on both.
--
-- So the pattern here is MERGE — not the V82 replace (tiling's catalog was thin) and not the V96
-- extend (painter's was rich and internally consistent). DRYWALL's was rich and self-contradictory.
--
-- ============ WHY CATEGORIES CHANGE AGAIN (this overrides V72) =================================
-- V72 filed DRYWALL by OBJECT — «Стелі», «Перегородки», «Короби», «Шви і суміші», «Інше». That
-- reads fine as a warehouse index and badly as a work list: a master describes a job as a
-- SEQUENCE («знімаю стару обшивку, ставлю каркас, шию, заробляю стики, готую під фарбу»), and the
-- estimate is read in that order by the CLIENT too, who has no idea which of five buckets
-- «Заробка стиків» lives in. The object (стеля / перегородка / короб) is in the position NAME,
-- where search finds it; the category is now the PHASE:
--
--     Підготовка  →  Каркас і обшивка  →  Оздоблення під фарбування
--
-- plus two categories that are not phases and stay as they are: «Звукоізоляція та утеплення»
-- (a parallel job with its own positions — deliberately never glued into a ГКЛ position) and
-- «Надбавки» (the >3 m surcharge, a PERCENT modifier, which belongs to no phase).
-- Category is display-only and carries no FK, so re-filing is safe; the same reasoning as V72.
--
-- ============ WHERE THE FINISHING CHAIN COMES FROM ============================================
-- A master supplied a «Технологічна матриця — Підготовка під фарбування поверхонь ГКЛ»
-- (серпень 2026) selling THREE levels: Q3 · Економ (22 етапи, 1 контроль ковзним світлом),
-- Q3+ · Преміум (28 етапів, 6 контролів, вологе обезпилювання) and Q4 · Еліт (32 етапи,
-- 10 контролів, стики паперовою стрічкою високої щільності). Note it is Q3/Q3+/Q4, NOT Q2/Q3/Q4:
-- Q2 is a level for tiling/heavy wallpaper, and this master does not sell it.
--
-- Two things came out of that document:
--   1. THE LEVELS THEMSELVES are one position each. Nobody buys «2.4 Контрольне ковзне світло»
--      separately — a control point is what the level GUARANTEES, not a line on an invoice. By the
--      atomicity rule that is 3 positions, not 82. What distinguishes them is a sentence, not a
--      name, which is why this migration also adds a `description` column (see PART 0).
--   2. THE INDIVIDUAL STAGES that we did not carry anywhere become positions too, because a master
--      who prices the chain himself needs them: криючий ґрунт-наповнювач, локальне дефектування,
--      мікрошліфування, вологе обезпилювання, and the Q4 joint with paper tape.
-- The stages we DID already carry are copied from PAINTER **verbatim** (name, type, unit, price) —
-- never re-worded. CatalogTemplateService.missingItems dedups a master's catalog across trades by
-- name+type+unit, so a master running both trades still owns exactly ONE row; re-wording would
-- have handed him two.
--
-- ============ PRICES ARE AN ORIENTIR, NOT A QUOTE =============================================
-- Nothing here is invented from the air, and nothing here is confirmed by a master either. Every
-- new number is derived by proportion from a position THIS catalog already ships:
--
--   Заповнення стиків паперовою стрічкою 150 ₴/м.п.  = 100 (our own joint line) × 1.5, paper tape
--                                                       is bedded and sanded, mesh is not
--   Криючий ґрунт-наповнювач            120 ₴/м²    = PAINTER «Грунт-фарба (праймер під фарбу)» 80,
--                                                       raised for a filling primer laid thicker
--   Локальне дефектування                60 ₴/м²    = PAINTER «Заробка тріщин раковин шліфування» 60
--   Мікрошліфування дефектів             60 ₴/м²    = below «Шліфування стін/стель (фінішне)» 100,
--                                                       it is local, not the whole plane
--   Вологе обезпилювання поверхні        40 ₴/м²    = PAINTER «Обезпилення поверхні» 25 × 1.6
--   Проклеювання склополотном примикань  60 ₴/м.п.  = the perimeter half of the split row below
--
-- The three levels are priced from the SUM of their own chain, then rounded down — a turnkey level
-- should not cost more than buying its stages one by one:
--   Q3  = 33+185+60+25+33+160+240+100+25 (stages 2-4) + 33+120+60+60+25 (stage 5) = 1159 → 1100
--   Q3+ = Q3 × 1.27 (28/22 етапів, 6 контролів, вологе обезпилювання)             = 1472 → 1400
--   Q4  = Q3 × 1.45 (32/22 етапів, 10 контролів, паперова стрічка)                = 1680 → 1650
--
-- **These must be checked with masters.** The mechanism that is supposed to set them for real is
-- price_insight_candidate (V94): a weekly crowd median off masters' own estimate lines, min 3
-- masters after an IQR trim. It is starving on today's user count, which is exactly why a derived
-- orientir ships now instead of a blank.
--
-- ============ WHAT IS NEVER TOUCHED ===========================================================
--   1. estimate_items — snapshots with no FK. A signed estimate reads tomorrow as it read today.
--   2. catalog_items with source <> 'LIBRARY' — the master typed or imported it; it is theirs.
--   3. a LIBRARY row whose price the master changed — compared against drywall_v13_retired_baseline.
--   4. the «ЗВУКОІЗОЛЯЦІЯ» bundle and every звукоізоляція/утеплення position: name, price, order.
--   5. every other trade, with ONE deliberate exception noted in PART 7 (a single PAINTER row).
-- ==============================================================================================

-- ==============================================================================================
-- PART 0. A position can now carry a sentence.
-- ==============================================================================================
-- «Підготовка ГКЛ під фарбування · Q3» and «· Q4» are not distinguishable by name — the difference
-- is which paints may go on top, how many sliding-light control points, and whether the surface is
-- wet-dedusted. That is a sentence. It travels with the copy into a master's own catalog, like
-- every other field, because the master is the one who has to explain it to the client.
--
-- Deliberately NOT added to the write DTOs yet: majstr-pwa has no field for it, and a PATCH that
-- omits a column it cannot see would null it on the first edit. Read path only until the PWA
-- catches up (logged in docs/open-questions.md).
ALTER TABLE catalog_templates ADD COLUMN description VARCHAR(500);
ALTER TABLE catalog_items     ADD COLUMN description VARCHAR(500);

COMMENT ON COLUMN catalog_templates.description IS
    'Optional one-paragraph explanation of what the position guarantees, for positions whose name cannot carry it (the Q3/Q3+/Q4 finishing levels). Copied by value into catalog_items.';

DO $$
DECLARE
    v_new_version int;
    v_removed     int;
    v_added       int;
    v_masters     int;
    v_orphans     int;
    v_dupes       int;
    v_total       int;
BEGIN
    v_new_version := (SELECT MAX(added_in_version) FROM catalog_templates) + 1;   -- 13 -> 14

    -- ==========================================================================================
    -- PART 1. Eight jobs, sixteen wordings -> eight rows. The tetris (v5) wording is canon.
    -- ==========================================================================================
    -- Canon chosen by the user: «v5 канон». It is also the better-formed set — «Монтаж короба
    -- (прямого) із гіпсокартону по периметру стелі» says which короб and out of what; «Монтаж
    -- короба прямого по периметру стелі» says neither. The retired row's price is remembered in
    -- drywall_v13_retired_baseline so PART 9 can tell "the master never touched our number" from
    -- "the master re-priced it", exactly as V83/V97 did.
    CREATE TABLE drywall_v13_retired_baseline AS
    SELECT lower(trim(name)) AS name_key, type, unit, suggested_price, name AS retired_name
    FROM catalog_templates
    WHERE trade = 'DRYWALL' AND name IN (
        'Монтаж короба прямого по периметру стелі',                       -- -> (прямого) із гіпсокартону, 550
        'Монтаж короба радіусного по периметру стелі',                    -- -> (радіусного) із гіпсокартону, 600
        'Радіусний короб ГКЛ по периметру',                               -- -> (радіусного) із гіпсокартону, 600
        'Монтаж перегородки ГКЛ 2 сторони в 1 шар',                       -- -> конструкцій (перегородки 2 сторони) в 1 шар, 800
        'Монтаж перегородки ГКЛ 2 сторони в 2 шари',                      -- -> ... в 2 шари, 900
        'Монтаж радіусної перегородки ГКЛ в 1 шар',                       -- -> радіусних конструкцій ... в 1 шар, 900
        'Монтаж радіусної перегородки ГКЛ в 2 шари',                      -- -> ... в 2 шари, 1000
        'Заробка стиків у гіпсокартоні поклейка серпянки',                -- -> split, see PART 4
        'Монтаж на висоті більше 3м надбавка',                            -- -> «Монтаж на висоті (більше 3м)», 20 %
        -- PART 3: масонry. Not drywall at all; every one of the seven is still shipped under
        -- BUILDER at the identical price, so nothing is lost to a master who has both trades.
        'Анкерування однієї стіни до іншої',
        'Кладка перегородки з піноблоку газоблоку від 50м2',
        'Кладка перегородки з піноблоку газоблоку до 50м2',
        'Кладка перегородки з цегли від 50м2',
        'Кладка перегородки з цегли до 50м2',
        'Кладка цегляної підстави під ванну',
        'Облаштування дверного пройому звуження розширення',
        -- PART 4: the glued row. Three operations in two different units under one LINEAR_METER
        -- price: armouring the joints is measured along the joint, fibreglass over a whole plane
        -- is measured in м², the perimeter is a third thing again. Replaced, not renamed, because
        -- one of its three children is a copy of a PAINTER row that already exists.
        'Заробка стиків у гіпсокартоні, поклейка склополотна (серпянки) на стики і по периметру'
    );

    -- The rename map for anything that still references a retired wording by name. The DRYWALL
    -- default bundles are rebuilt wholesale in PART 8, so this exists for the OTHER referrer:
    -- a master's own copy of a default bundle, forked on write by V113. That copy is our text,
    -- not his — leaving it pointing at a name we just deleted would price his line at 0.
    CREATE TEMP TABLE _renames (old_name text, new_name text) ON COMMIT DROP;
    INSERT INTO _renames VALUES
        ('Монтаж короба прямого по периметру стелі',        'Монтаж короба (прямого) із гіпсокартону по периметру стелі'),
        ('Монтаж короба радіусного по периметру стелі',     'Монтаж короба (радіусного) із гіпсокартону по периметру стелі'),
        ('Радіусний короб ГКЛ по периметру',                'Монтаж короба (радіусного) із гіпсокартону по периметру стелі'),
        ('Монтаж перегородки ГКЛ 2 сторони в 1 шар',        'Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар'),
        ('Монтаж перегородки ГКЛ 2 сторони в 2 шари',       'Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари'),
        ('Монтаж радіусної перегородки ГКЛ в 1 шар',        'Монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар'),
        ('Монтаж радіусної перегородки ГКЛ в 2 шари',       'Монтаж радіусних конструкцій (перегородки) із гіпсокартону в 2 шари'),
        ('Монтаж на висоті більше 3м надбавка',             'Монтаж на висоті (більше 3м)'),
        ('Заробка стиків у гіпсокартоні поклейка серпянки', 'Заповнення та армування стиків ГКЛ'),
        ('Заробка стиків у гіпсокартоні, поклейка склополотна (серпянки) на стики і по периметру',
                                                            'Заповнення та армування стиків ГКЛ');

    -- Retire the losers. price_insight_candidate FKs this table ON DELETE CASCADE: a pending
    -- crowd-price candidate for a wording we no longer ship should indeed disappear with it.
    DELETE FROM catalog_templates ct
    USING drywall_v13_retired_baseline b
    WHERE ct.trade = 'DRYWALL'
      AND lower(trim(ct.name)) = b.name_key AND ct.type = b.type AND ct.unit = b.unit;

    -- ==========================================================================================
    -- PART 2. The 345 % surcharge is gone with its row.
    -- ==========================================================================================
    -- «Монтаж на висоті більше 3м надбавка» was PERCENT with suggested_price = 345.00 — V31 wrote a
    -- money price into a percent row, so a master who picked it added 345 % to the estimate. Its
    -- tetris twin «Монтаж на висоті (більше 3м)» is the correct 20 % and is the canon above, so
    -- the bug is fixed by the merge rather than by a separate UPDATE. Asserted at the end.

    -- ==========================================================================================
    -- PART 3 / PART 4. Masonry retired; the glued joints row split.
    -- ==========================================================================================
    -- Both handled by the retirement list in PART 1. The three children of the glued row:
    --   «Заповнення та армування стиків ГКЛ»                  LM  100  — new wording, canon price
    --   «Проклеювання склополотном примикань і кутів»          LM   60  — the perimeter half
    --   «Поклейка склополотна»                                M2  160  — copied from PAINTER in
    --                                                                    PART 7, not re-worded
    -- The user confirmed the masonry call: «це не частина гіпсокартону, це треба прибрати».

    -- ==========================================================================================
    -- PART 5. Two renames.
    -- ==========================================================================================
    -- A brand in a catalog position name. The catalog describes the WORK and the RESULT; the brand
    -- belongs to a material, and only when the master wrote it himself. EN 14195 names the profile
    -- family neutrally, which is what a reinforced frame actually means here.
    UPDATE catalog_templates
    SET name = 'Монтаж каркасу посиленим профілем'
    WHERE trade = 'DRYWALL' AND name = 'Монтаж каркасу посиленим профілем Walraven TECE';

    -- Kept as its own product (user: «це різне») but renamed so it cannot read as a fourth,
    -- unnamed quality level sitting next to Q3/Q3+/Q4. It is the ordinary finish: joints and screws
    -- filled and sanded, no fibreglass, no light control.
    UPDATE catalog_templates
    SET name = 'Шпаклювання та шліфування гіпсокартону (без склополотна)',
        description = 'Звичайне оздоблення ГКЛ: шпаклювання площин і шліфування без суцільного '
                   || 'армування склополотном і без контролю ковзним світлом. Підходить під '
                   || 'шпалери та матову фарбу при розсіяному світлі. Для підготовки під '
                   || 'якісне або бокове освітлення беріть рівень Q3 / Q3+ / Q4.'
    WHERE trade = 'DRYWALL' AND name = 'Шпаклювання та шліфування гіпсокартону';

    -- ==========================================================================================
    -- PART 6. New positions.
    -- ==========================================================================================
    -- Two children of the split, five stages read off the master's matrix that this catalog did not
    -- carry anywhere, and the three levels themselves.
    INSERT INTO catalog_templates (id, trade, category, name, type, unit, suggested_price, added_in_version, description)
    VALUES
        -- the split
        (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
         'Заповнення та армування стиків ГКЛ', 'WORK', 'LINEAR_METER', 100.00, v_new_version,
         'Заповнення стику шпаклівкою з армувальною стрічкою (серпянка) і шліфування стику.'),
        (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
         'Проклеювання склополотном примикань і кутів', 'WORK', 'LINEAR_METER', 60.00, v_new_version,
         'Смуга склополотна по примиканнях до стін, стелі й підлоги та по внутрішніх кутах — '
         || 'там, де тріщина з’являється першою. Суцільне армування площин міряється в м² окремо.'),

        -- stages from the matrix that we did not carry
        (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
         'Заповнення стиків ГКЛ паперовою стрічкою високої щільності', 'WORK', 'LINEAR_METER', 150.00, v_new_version,
         'Стик на паперовій стрічці високої щільності замість серпянки — рівень Q4. Стрічка '
         || 'втоплюється в шпаклівку і шліфується, шов не проступає під бічним світлом.'),
        (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
         'Криючий ґрунт-наповнювач', 'WORK', 'M2', 120.00, v_new_version,
         'Криючий ґрунт з наповнювачем: вирівнює вбирання основи і робить видимими дефекти, '
         || 'які під фарбою вже не виправити.'),
        (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
         'Локальне дефектування', 'WORK', 'M2', 60.00, v_new_version,
         'Точкове усунення дефектів, знайдених після криючого ґрунту.'),
        (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
         'Мікрошліфування дефектів', 'WORK', 'M2', 60.00, v_new_version,
         'Локальне шліфування дефектованих місць без переходу на всю площину.'),
        (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
         'Вологе обезпилювання поверхні', 'WORK', 'M2', 40.00, v_new_version,
         'Вологе знепилення перед фарбуванням — знімає пил, який суха обробка лишає в порах. '
         || 'Обов’язкове для рівнів Q3+ і Q4.'),

        -- the three levels
        (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
         'Підготовка ГКЛ під фарбування · Q3 (економ)', 'WORK', 'M2', 1100.00, v_new_version,
         'Базовий рівень: заповнення стиків, шпаклювання під склополотно, армування склополотном, '
         || 'фінішне шпаклювання, криючий ґрунт. Контроль ковзним світлом — 1 точка, після '
         || 'криючого ґрунту. Вологе обезпилювання не виконується. Сумісні фарби: матові економ '
         || 'і середнього сегменту; сатин, напівглянець і глянець не допускаються. Гарантія: без '
         || 'видимих слідів інструменту при стандартному розсіяному освітленні.'),
        (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
         'Підготовка ГКЛ під фарбування · Q3+ (преміум)', 'WORK', 'M2', 1400.00, v_new_version,
         'Два цикли шпаклювання — до і після склополотна. Контроль ковзним світлом на 6 переходах, '
         || 'вологе обезпилювання перед фарбуванням обов’язкове. Сумісні фарби: матові та '
         || 'глибокоматові преміум-сегменту. При жорсткому бічному світлі можливі м’які світлотіні '
         || '— рівень розрахований на якісне загальне освітлення.'),
        (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
         'Підготовка ГКЛ під фарбування · Q4 (еліт)', 'WORK', 'M2', 1650.00, v_new_version,
         'Стики на паперовій стрічці високої щільності. Кожен ключовий перехід заблокований '
         || 'контролем ковзним світлом — 10 точок, дефект усувається на своєму етапі. Вологе '
         || 'обезпилювання обов’язкове. Сумісні фарби: будь-які, включно з сатином, напівглянцем '
         || 'і глянцем. Гарантія: відсутність світлотіней під будь-яким кутом освітлення, '
         || 'у тому числі боковим і трековим.');

    -- ==========================================================================================
    -- PART 7. Stages this catalog already ships under another trade — copied VERBATIM.
    -- ==========================================================================================
    -- Copied name, type, unit and price without a single edit. A master running DRYWALL + PAINTER
    -- still owns exactly one row (CatalogTemplateService.missingItems dedups on name+type+unit
    -- across trades, and CatalogItemResponse.sharedTrades makes that row visible under both trade
    -- chips); re-wording any of these would have given him two rows for one job — the very defect
    -- PART 1 spends its length undoing. Same move V70 made for the cross-trade bundles.
    INSERT INTO catalog_templates (id, trade, category, name, type, unit, suggested_price, added_in_version)
    SELECT gen_random_uuid(), 'DRYWALL', v.category, src.name, src.type, src.unit, src.suggested_price, v_new_version
    FROM (VALUES
            -- phase 1
            ('DEMOLITION', 'Демонтаж гіпсокартонної стелі',      'Підготовка'),
            ('DEMOLITION', 'Демонтаж перегородки з гіпсокартону','Підготовка'),
            ('PAINTER',    'Захист підлоги картоном',            'Підготовка'),
            ('PAINTER',    'Грунтування',                        'Підготовка'),
            -- phase 3, in the matrix's own order
            ('PAINTER',    'Базове шпаклювання під скловолокно', 'Оздоблення під фарбування'),
            ('PAINTER',    'Шліфування під скловолокно/склохолст','Оздоблення під фарбування'),
            ('PAINTER',    'Обезпилення поверхні',               'Оздоблення під фарбування'),
            ('PAINTER',    'Поклейка склополотна',               'Оздоблення під фарбування'),
            ('PAINTER',    'Шпаклювання фінішне (2–4 рази)',     'Оздоблення під фарбування'),
            ('PAINTER',    'Шліфування стін/стель (фінішне)',    'Оздоблення під фарбування')
         ) AS v(src_trade, src_name, category)
    JOIN catalog_templates src ON src.trade = v.src_trade AND src.name = v.src_name
    WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                      WHERE ct.trade = 'DRYWALL'
                        AND lower(trim(ct.name)) = lower(trim(src.name))
                        AND ct.type = src.type AND ct.unit = src.unit);

    -- The ONE row this migration adds outside DRYWALL, and it is an addition, never an edit.
    -- Stage 6 of the matrix is airless painting, which the master sells and PAINTER does not carry
    -- under any wording. It belongs to PAINTER, not here — the Q levels deliberately STOP before
    -- painting (user: «все має бути окремо»), because the paint is the client's choice and the
    -- painting is a separate job with a separate price. Orientir: PAINTER's own «Фарбування
    -- стін/стель (у кольорі)» 180.
    INSERT INTO catalog_templates (id, trade, category, name, type, unit, suggested_price, added_in_version, description)
    SELECT gen_random_uuid(), 'PAINTER', 'Фарбування',
           'Фарбування безповітряним методом (airless)', 'WORK', 'M2', 180.00, v_new_version,
           'Нанесення фарби безповітряним апаратом. Потребує укриття всіх суміжних поверхонь; '
           || 'дає рівний шар без слідів валика на великих площинах.'
    WHERE NOT EXISTS (SELECT 1 FROM catalog_templates
                      WHERE trade = 'PAINTER' AND lower(trim(name)) = 'фарбування безповітряним методом (airless)');

    -- ==========================================================================================
    -- PART 8. Everything that survives is re-filed by phase.
    -- ==========================================================================================
    -- «Звукоізоляція» is renamed to «Звукоізоляція та утеплення» and the two утеплення rows join it.
    -- The positions themselves — names, prices, units — are untouched, and so is their bundle:
    -- insulation is a parallel job with its own price, never glued into a ГКЛ position.
    UPDATE catalog_templates SET category = 'Звукоізоляція та утеплення'
    WHERE trade = 'DRYWALL'
      AND (category = 'Звукоізоляція'
           OR name IN ('Утеплення мінватою в один шар',      -- sat in «Інше»
                       'Утеплення ГКЛ стіродуром',           -- sat in «Підготовка»
                       'Монтаж акустичної мембрани'));       -- sat in «Стіни»

    UPDATE catalog_templates SET category = 'Надбавки'
    WHERE trade = 'DRYWALL' AND name = 'Монтаж на висоті (більше 3м)';

    -- Everything still sitting in a V72 object bucket is structure: frame, sheeting, boxes,
    -- niches, slopes, arches, openings, screens.
    UPDATE catalog_templates SET category = 'Каркас і обшивка'
    WHERE trade = 'DRYWALL'
      AND category IN ('Стелі', 'Стіни', 'Перегородки', 'Короби', 'Конструкції', 'Арка та декор ГКЛ', 'Інше')
      AND name NOT IN ('Шпаклювання та шліфування гіпсокартону (без склополотна)');

    UPDATE catalog_templates SET category = 'Оздоблення під фарбування'
    WHERE trade = 'DRYWALL' AND name = 'Шпаклювання та шліфування гіпсокартону (без склополотна)';

    -- ==========================================================================================
    -- PART 9. Fourteen bundles -> four.
    -- ==========================================================================================
    -- Eleven of the fourteen held 2-5 positions: «Короб під ванну» was two lines, «Стеля ГКЛ рівна»
    -- two. That is the shape V112 already rejected for PAINTER — «просто набір позицій, без
    -- будь-якої послідовності», and a master does not reach for a bundle to save himself three
    -- taps. What replaces them is what the user asked for: підготовка -> робота -> фініш, few and
    -- long, sort_order carrying the order the work is actually done in.
    --
    -- A bundle offers ALTERNATIVES as well as steps (в 1 шар / в 2 шари has always done this) —
    -- the master deletes the variant he is not building. The three Q levels are alternatives to
    -- EACH OTHER and to the piecemeal chain above them, which is why they sit at the end of their
    -- own bundle and never inside the assembly ones: a Q level already contains the joints and the
    -- fibreglass, so listing it beside them would bill the same work twice.
    -- template_default_override FKs template_id ON DELETE CASCADE, so a master's override row goes
    -- with the default it was hiding — while forked_template_id is ON DELETE SET NULL on the OTHER
    -- side, so his fork itself survives as an ordinary master-owned template. He keeps every edit
    -- he made; it simply stops being "my copy of a default" and becomes "my template".
    -- ORDER MATTERS. template_default_override.template_id is ON DELETE CASCADE, so the override
    -- rows for the bundles deleted on the next line disappear WITH them — and PART 10 needs those
    -- rows to find the master's forked copies and repoint their lines off the retired wordings.
    -- After the DELETE there is nothing left to join, and his fork would keep a line that applies
    -- at 0 ₴. So remember the forks first.
    CREATE TEMP TABLE _forks ON COMMIT DROP AS
    SELECT DISTINCT o.forked_template_id AS template_id
    FROM template_default_override o
    JOIN estimate_templates et ON et.id = o.template_id
    WHERE et.is_default AND et.trade = 'DRYWALL' AND o.forked_template_id IS NOT NULL;

    DELETE FROM estimate_templates
    WHERE is_default AND trade = 'DRYWALL' AND name <> 'ЗВУКОІЗОЛЯЦІЯ';   -- items cascade

    -- Its positions, their order and its own override rows are untouched — only the label, which
    -- was the last CAPS bundle name in the trade and would have read as a shout beside three
    -- sentence-case siblings. Same call V82 made when it stopped shipping the price list's caps.
    UPDATE estimate_templates SET name = 'Звукоізоляція та утеплення'
    WHERE is_default AND trade = 'DRYWALL' AND name = 'ЗВУКОІЗОЛЯЦІЯ';

    CREATE TEMP TABLE _bundles (bundle text, pos int, item text) ON COMMIT DROP;
    INSERT INTO _bundles VALUES
        ('Стеля з гіпсокартону',  1, 'Захист підлоги картоном'),
        ('Стеля з гіпсокартону',  2, 'Демонтаж гіпсокартонної стелі'),
        ('Стеля з гіпсокартону',  3, 'Грунтування'),
        ('Стеля з гіпсокартону',  4, 'Монтаж каркасу посиленим профілем'),
        ('Стеля з гіпсокартону',  5, 'Монтаж гіпсокартону на стелю рівну'),
        ('Стеля з гіпсокартону',  6, 'Монтаж гіпсокартону на стелю зі скосами'),
        ('Стеля з гіпсокартону',  7, 'Монтаж короба (прямого) із гіпсокартону по периметру стелі'),
        ('Стеля з гіпсокартону',  8, 'Монтаж короба (радіусного) із гіпсокартону по периметру стелі'),
        ('Стеля з гіпсокартону',  9, 'Монтаж ніші під прихований карниз короб під комунікації'),
        ('Стеля з гіпсокартону', 10, 'Облаштування ніші ГКЛ з підсвічуванням'),
        ('Стеля з гіпсокартону', 11, 'Монтаж треків прихованого карниза'),
        ('Стеля з гіпсокартону', 12, 'Монтаж профілю тіньового шва по периметру стелі'),
        ('Стеля з гіпсокартону', 13, 'Утеплення мінватою в один шар'),
        ('Стеля з гіпсокартону', 14, 'Вирізка отворів в гіпсокартоні'),
        ('Стеля з гіпсокартону', 15, 'Заповнення та армування стиків ГКЛ'),
        ('Стеля з гіпсокартону', 16, 'Проклеювання склополотном примикань і кутів'),
        ('Стеля з гіпсокартону', 17, 'Шпаклювання та шліфування гіпсокартону (без склополотна)'),
        ('Стеля з гіпсокартону', 18, 'Монтаж на висоті (більше 3м)'),

        ('Стіни та перегородки з гіпсокартону',  1, 'Захист підлоги картоном'),
        ('Стіни та перегородки з гіпсокартону',  2, 'Демонтаж перегородки з гіпсокартону'),
        ('Стіни та перегородки з гіпсокартону',  3, 'Грунтування'),
        ('Стіни та перегородки з гіпсокартону',  4, 'Монтаж каркасу посиленим профілем'),
        ('Стіни та перегородки з гіпсокартону',  5, 'Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар'),
        ('Стіни та перегородки з гіпсокартону',  6, 'Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари'),
        ('Стіни та перегородки з гіпсокартону',  7, 'Монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар'),
        ('Стіни та перегородки з гіпсокартону',  8, 'Монтаж радіусних конструкцій (перегородки) із гіпсокартону в 2 шари'),
        ('Стіни та перегородки з гіпсокартону',  9, 'Монтаж гіпсокартону на стіни'),
        ('Стіни та перегородки з гіпсокартону', 10, 'Монтаж гіпсокартону на клей'),
        ('Стіни та перегородки з гіпсокартону', 11, 'Утеплення мінватою в один шар'),
        ('Стіни та перегородки з гіпсокартону', 12, 'Монтаж укосів із гіпсокартону'),
        ('Стіни та перегородки з гіпсокартону', 13, 'Обшивка інсталяції в т.ч. отвори'),
        ('Стіни та перегородки з гіпсокартону', 14, 'Монтаж екрану ванни з підступком'),
        ('Стіни та перегородки з гіпсокартону', 15, 'Вирізка отворів в гіпсокартоні'),
        ('Стіни та перегородки з гіпсокартону', 16, 'Заповнення та армування стиків ГКЛ'),
        ('Стіни та перегородки з гіпсокартону', 17, 'Проклеювання склополотном примикань і кутів'),
        ('Стіни та перегородки з гіпсокартону', 18, 'Шпаклювання та шліфування гіпсокартону (без склополотна)'),
        ('Стіни та перегородки з гіпсокартону', 19, 'Монтаж на висоті (більше 3м)'),

        -- The matrix, in its own order. Rows 1-15 are the chain priced stage by stage; rows 16-18
        -- are the same chain sold turnkey at a level. Take one or the other, never both.
        ('Підготовка ГКЛ під фарбування',  1, 'Заповнення та армування стиків ГКЛ'),
        ('Підготовка ГКЛ під фарбування',  2, 'Заповнення стиків ГКЛ паперовою стрічкою високої щільності'),
        ('Підготовка ГКЛ під фарбування',  3, 'Проклеювання склополотном примикань і кутів'),
        ('Підготовка ГКЛ під фарбування',  4, 'Грунтування'),
        ('Підготовка ГКЛ під фарбування',  5, 'Базове шпаклювання під скловолокно'),
        ('Підготовка ГКЛ під фарбування',  6, 'Шліфування під скловолокно/склохолст'),
        ('Підготовка ГКЛ під фарбування',  7, 'Обезпилення поверхні'),
        ('Підготовка ГКЛ під фарбування',  8, 'Поклейка склополотна'),
        ('Підготовка ГКЛ під фарбування',  9, 'Шпаклювання фінішне (2–4 рази)'),
        ('Підготовка ГКЛ під фарбування', 10, 'Шліфування стін/стель (фінішне)'),
        ('Підготовка ГКЛ під фарбування', 11, 'Криючий ґрунт-наповнювач'),
        ('Підготовка ГКЛ під фарбування', 12, 'Локальне дефектування'),
        ('Підготовка ГКЛ під фарбування', 13, 'Мікрошліфування дефектів'),
        ('Підготовка ГКЛ під фарбування', 14, 'Вологе обезпилювання поверхні'),
        ('Підготовка ГКЛ під фарбування', 15, 'Шпаклювання та шліфування гіпсокартону (без склополотна)'),
        ('Підготовка ГКЛ під фарбування', 16, 'Підготовка ГКЛ під фарбування · Q3 (економ)'),
        ('Підготовка ГКЛ під фарбування', 17, 'Підготовка ГКЛ під фарбування · Q3+ (преміум)'),
        ('Підготовка ГКЛ під фарбування', 18, 'Підготовка ГКЛ під фарбування · Q4 (еліт)');

    INSERT INTO estimate_templates (id, owner_id, name, trade, is_default)
    SELECT gen_random_uuid(), NULL, bundle, 'DRYWALL', true
    FROM (SELECT DISTINCT bundle FROM _bundles) b;

    -- Type and unit are read back from the catalog rather than repeated here: the bundle preview
    -- shows the item's own unit and applying it then overwrites that with the catalog's, so any
    -- disagreement is a lie shown to the master (SeedCatalogInvariantsIntegrationTest pins it).
    INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
    SELECT gen_random_uuid(), et.id, ct.name, ct.type, ct.unit, b.pos
    FROM _bundles b
    JOIN estimate_templates et ON et.is_default AND et.trade = 'DRYWALL' AND et.name = b.bundle
    JOIN catalog_templates ct ON ct.trade = 'DRYWALL' AND ct.name = b.item;

    -- A typo in _bundles would silently ship a bundle that is short a line, so count the join.
    SELECT count(*) INTO v_orphans
    FROM _bundles b
    WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct WHERE ct.trade = 'DRYWALL' AND ct.name = b.item);
    IF v_orphans > 0 THEN
        RAISE EXCEPTION 'V116: % bundle position(s) name a catalog row that does not exist', v_orphans;
    END IF;

    -- ==========================================================================================
    -- PART 10. The same changes reach the masters who already registered.
    -- ==========================================================================================
    -- V83 / V97 pattern, with ONE correction that matters here. Their delete guard asked only
    -- "does catalog_templates still carry this name/type/unit ANYWHERE" — with no trade filter.
    -- That is fine when the retired rows are unique to the trade being rebuilt; it is wrong here,
    -- because all seven masonry positions are still shipped under BUILDER at the identical price,
    -- so the guard would have blocked every single masonry deletion. The guard below asks the
    -- question that was always meant: does any trade THIS MASTER HAS still ship this position? A
    -- master with DRYWALL + BUILDER keeps his masonry (he is a builder, he lays block); a
    -- DRYWALL-only master loses it, which is the whole point of the change.
    SELECT COUNT(DISTINCT user_id) INTO v_masters FROM user_trades WHERE trade = 'DRYWALL';

    CREATE TEMP TABLE _removed ON COMMIT DROP AS
    SELECT DISTINCT ci.id, ci.owner_id
    FROM catalog_items ci
    JOIN drywall_v13_retired_baseline b
      ON b.name_key = lower(trim(ci.name)) AND b.type = ci.type AND b.unit = ci.unit
    WHERE ci.source = 'LIBRARY'
      AND ci.default_price = b.suggested_price
      AND EXISTS (SELECT 1 FROM user_trades ut WHERE ut.user_id = ci.owner_id AND ut.trade = 'DRYWALL')
      AND NOT EXISTS (
          SELECT 1 FROM catalog_templates ct
          JOIN user_trades ut ON ut.user_id = ci.owner_id AND ut.trade = ct.trade
          WHERE lower(trim(ct.name)) = lower(trim(ci.name))
            AND ct.type = ci.type AND ct.unit = ci.unit);

    -- His own forked copies of OUR default bundles are repointed before the row disappears — the
    -- text in a V113 fork is ours, and a line naming a position he no longer owns applies at 0 ₴.
    -- Templates he wrote himself are never touched.
    UPDATE estimate_template_items i
    SET name = r.new_name
    FROM _renames r, _forks f
    WHERE i.template_id = f.template_id
      AND lower(trim(i.name)) = lower(trim(r.old_name))
      AND EXISTS (SELECT 1 FROM catalog_templates ct
                  WHERE ct.trade = 'DRYWALL' AND ct.name = r.new_name
                    AND ct.type = i.type AND ct.unit = i.unit);

    DELETE FROM catalog_items ci USING _removed r WHERE ci.id = r.id;
    GET DIAGNOSTICS v_removed = ROW_COUNT;

    -- The renames of PART 5 reach his LIBRARY copy only while it still carries OUR price.
    UPDATE catalog_items ci
    SET name = 'Монтаж каркасу посиленим профілем'
    WHERE ci.source = 'LIBRARY' AND ci.name = 'Монтаж каркасу посиленим профілем Walraven TECE'
      AND ci.default_price = 400.00
      AND NOT EXISTS (SELECT 1 FROM catalog_items x WHERE x.owner_id = ci.owner_id
                        AND lower(trim(x.name)) = 'монтаж каркасу посиленим профілем'
                        AND x.type = ci.type AND x.unit = ci.unit);

    UPDATE catalog_items ci
    SET name = 'Шпаклювання та шліфування гіпсокартону (без склополотна)'
    WHERE ci.source = 'LIBRARY' AND ci.name = 'Шпаклювання та шліфування гіпсокартону'
      AND ci.default_price = 130.00
      AND NOT EXISTS (SELECT 1 FROM catalog_items x WHERE x.owner_id = ci.owner_id
                        AND lower(trim(x.name)) = 'шпаклювання та шліфування гіпсокартону (без склополотна)'
                        AND x.type = ci.type AND x.unit = ci.unit);

    -- Keyed exactly like ux_catalog_items_owner_name_type_unit: an existing row — whatever its
    -- source or price — blocks the insert rather than colliding with it. Note the copied PAINTER
    -- stages are in this set, so a master with both trades gets nothing new for them: he already
    -- owns the row.
    CREATE TEMP TABLE _added ON COMMIT DROP AS
    SELECT gen_random_uuid() AS id, t.user_id AS owner_id, ct.name, ct.type, ct.unit,
           ct.suggested_price AS default_price, ct.category, ct.description
    FROM (SELECT DISTINCT user_id FROM user_trades WHERE trade = 'DRYWALL') t
    CROSS JOIN catalog_templates ct
    WHERE ct.trade = 'DRYWALL' AND ct.added_in_version = v_new_version
      AND NOT EXISTS (
          SELECT 1 FROM catalog_items ci
          WHERE ci.owner_id = t.user_id
            AND lower(trim(ci.name)) = lower(trim(ct.name))
            AND ci.type = ct.type AND ci.unit = ct.unit);

    INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category, trade, source, description)
    SELECT id, owner_id, name, type, unit, default_price, category, 'DRYWALL', 'LIBRARY', description FROM _added;
    GET DIAGNOSTICS v_added = ROW_COUNT;

    -- Category is display-only, so the phases reach existing masters too — otherwise a master who
    -- registered yesterday keeps reading his catalog by object while a new one reads it by phase.
    -- Only LIBRARY rows: a category the master set himself is his.
    UPDATE catalog_items ci
    SET category = ct.category
    FROM catalog_templates ct
    WHERE ct.trade = 'DRYWALL'
      AND ci.trade = 'DRYWALL' AND ci.source = 'LIBRARY'
      AND lower(trim(ci.name)) = lower(trim(ct.name))
      AND ci.type = ct.type AND ci.unit = ct.unit
      AND ci.category IS DISTINCT FROM ct.category;

    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed)
    SELECT gen_random_uuid(), owner_id, 'COUNT',
           COUNT(*) FILTER (WHERE src = 'add'), COUNT(*) FILTER (WHERE src = 'del')
    FROM (SELECT owner_id, 'add' AS src FROM _added
          UNION ALL
          SELECT owner_id, 'del' AS src FROM _removed) x
    GROUP BY owner_id;

    UPDATE users u
    SET last_synced_catalog_version = (SELECT MAX(added_in_version) FROM catalog_templates)
    WHERE EXISTS (SELECT 1 FROM user_trades ut WHERE ut.user_id = u.id AND ut.trade = 'DRYWALL');

    -- ==========================================================================================
    -- PART 11. Self-checks — the migration refuses to land half-done.
    -- ==========================================================================================
    IF EXISTS (SELECT 1 FROM catalog_templates
               WHERE trade = 'DRYWALL' AND unit = 'PERCENT' AND suggested_price > 100) THEN
        RAISE EXCEPTION 'V116: a PERCENT position still carries a money price';
    END IF;

    IF EXISTS (SELECT 1 FROM catalog_templates WHERE trade = 'DRYWALL' AND category = 'Кладка') THEN
        RAISE EXCEPTION 'V116: masonry is still filed under DRYWALL';
    END IF;

    IF EXISTS (SELECT 1 FROM catalog_templates
               WHERE trade = 'DRYWALL'
                 AND (category IS NULL   -- NOT IN is never true for NULL; a filed-nowhere row would slip
                      OR category NOT IN ('Підготовка', 'Каркас і обшивка', 'Оздоблення під фарбування',
                                          'Звукоізоляція та утеплення', 'Надбавки'))) THEN
        RAISE EXCEPTION 'V116: a DRYWALL position is filed outside the five phases';
    END IF;

    IF EXISTS (SELECT 1 FROM catalog_templates WHERE trade = 'DRYWALL' AND name ILIKE '%walraven%') THEN
        RAISE EXCEPTION 'V116: a brand name survived in a position name';
    END IF;

    SELECT count(*) INTO v_dupes FROM (
        SELECT 1 FROM catalog_templates WHERE trade = 'DRYWALL'
        GROUP BY regexp_replace(lower(translate(name, '*×', 'хх')), '[^0-9a-zа-яіїєґ]', '', 'g'), type, unit
        HAVING count(*) > 1) d;
    IF v_dupes > 0 THEN
        RAISE EXCEPTION 'V116: % duplicate wording group(s) left in DRYWALL', v_dupes;
    END IF;

    SELECT count(*) INTO v_total FROM catalog_templates WHERE trade = 'DRYWALL';
    RAISE NOTICE 'V116 drywall: % positions, version %, % masters, +% / -% catalog rows',
                 v_total, v_new_version, v_masters, v_added, v_removed;
END $$;

DROP TABLE drywall_v13_retired_baseline;
