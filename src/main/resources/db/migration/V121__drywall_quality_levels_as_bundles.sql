-- V121. A finish level is a BUNDLE, not a catalog position — and a bundle can explain itself
--       to the client.
--
-- ============ WHAT WAS WRONG ==================================================================
-- V116 sold the three finish levels off the master's technology matrix as three CATALOG POSITIONS
-- («Підготовка ГКЛ під фарбування · Q3 (економ)» 1100 ₴/м², Q3+ 1400, Q4 1650), each carrying a
-- five-sentence description that listed the whole chain of works inside it. Two things broke.
--
--   1. A level is a SEQUENCE OF WORKS, so it is a bundle. Its own description said so out loud —
--      «заповнення стиків, шпаклювання під склополотно, армування склополотном, фінішне
--      шпаклювання, криючий ґрунт» is five positions this catalog already ships, squeezed into
--      one line's explanation. The master: «це не може бути однією позицією… це має бути
--      шаблоном, тобто на кожен рівень робимо шаблон з специфічним набором позицій».
--   2. The description had nowhere to live. Rendered inline under the line (V119) it ran the full
--      width of the estimate board and «все пливе»; in the client portal it wrapped into a tall
--      block while the position NAME above it was ellipsized to «Базове шпаклювання під с…».
--      The text is now behind an (i) in the app and behind a tap in the portal, so a position
--      keeps at most one short sentence and the long text belongs to the bundle.
--
-- ============ AND Q1/Q2 EXIST =================================================================
-- Q1…Q4 is not a Knauf table — it is how the whole industry grades a drywall surface, and the
-- master went and checked («тобто це не тільки кнауф таке робить, а і інші виробники»). His own
-- matrix starts at Q3 because he sells painting; a drywall job that ends under TILE (Q1) or under
-- WALLPAPER (Q2) is a different, cheaper contract he was quoting by hand.
--
-- So FIVE bundles ship, not four: Q1, Q2, Q3, Q3+, Q4. **Q3+ is the one judgement call here.**
-- It is not in the industry table — it is the master's own middle tier, fully specified in his
-- matrix (28 stages, 6 light checks) and priced at 1400 ₴/м². Dropping it to match the textbook
-- would delete a product he sells; keeping it costs one more row in a list he can hide per-master
-- anyway (V113). Q1 and Q2 are the industry's, Q3/Q3+/Q4 are his, and they compose one ladder.
--
-- ============ NO NEW CATALOG POSITIONS, SO NO VERSION BUMP ====================================
-- Every stage these five bundles name already exists — V116 read them off the same matrix and
-- V117 added the joint sanding. This migration only DELETES three positions, so
-- MAX(added_in_version) does not move and the catalog version stays 15: a version bump exists to
-- push NEW rows into masters' catalogs, and there are none. For the same reason V118's ranking
-- re-run is not needed — deletions leave every remaining sort_order distinct and non-zero.
--
-- ============ THE TWO NEW COLUMNS =============================================================
-- estimate_templates.description — the level explained in the master's and the CLIENT's words.
-- estimates.quality_note        — a SNAPSHOT of it, taken when the bundle is applied, rendered
--                                 under the table in the portal and in the PDF. A snapshot, never
--                                 a join, for the same reason estimate_items.description is one
--                                 (V119): the client signed THAT wording.

ALTER TABLE estimate_templates ADD COLUMN description VARCHAR(1000);
COMMENT ON COLUMN estimate_templates.description IS
    'What this bundle means, in the client''s words. Copied onto estimates.quality_note when the '
    'bundle is applied, and shown to the client under the estimate table.';

ALTER TABLE estimates ADD COLUMN quality_note VARCHAR(1000);
COMMENT ON COLUMN estimates.quality_note IS
    'Snapshot of the applied template''s description (the finish level and its tolerances). A '
    'snapshot, never a join: re-wording the bundle must not change a signed estimate.';

DO $$
DECLARE
    v_masters  int := 0;
    v_removed  int := 0;
    v_orphans  int := 0;
    v_bundle   uuid;
BEGIN
    -- ==========================================================================================
    -- PART 1. The three level POSITIONS are retired.
    -- ==========================================================================================
    -- Baseline first, so PART 5 can tell «the master never touched our number» from «the master
    -- re-priced it» — the V83/V97/V116 pattern.
    CREATE TEMP TABLE _retired ON COMMIT DROP AS
    SELECT lower(trim(name)) AS name_key, type, unit, suggested_price
    FROM catalog_templates
    WHERE trade = 'DRYWALL' AND name IN (
        'Підготовка ГКЛ під фарбування · Q3 (економ)',
        'Підготовка ГКЛ під фарбування · Q3+ (преміум)',
        'Підготовка ГКЛ під фарбування · Q4 (еліт)');

    IF (SELECT count(*) FROM _retired) <> 3 THEN
        RAISE EXCEPTION 'V121: expected the three V116 level positions, found %',
            (SELECT count(*) FROM _retired);
    END IF;

    DELETE FROM catalog_templates ct
    USING _retired r
    WHERE ct.trade = 'DRYWALL'
      AND lower(trim(ct.name)) = r.name_key AND ct.type = r.type AND ct.unit = r.unit;

    -- ==========================================================================================
    -- PART 2. The descriptions that survive are trimmed to a hint.
    -- ==========================================================================================
    -- «можливо щось мінімальне лишаємо, але і то тільки в (і)». Only a couple of rows were long
    -- enough to be the problem; the rest of the trade already reads as one short sentence. The Q
    -- references are rewritten onto the full Q1…Q4 ladder while we are in here.
    UPDATE catalog_templates
    SET description = 'Звичайне оздоблення ГКЛ без склополотна і без контролю ковзним світлом — '
                   || 'це рівень Q2, під шпалери та фактурні фарби.'
    WHERE trade = 'DRYWALL' AND name = 'Шпаклювання та шліфування гіпсокартону (без склополотна)';

    UPDATE catalog_templates
    SET description = 'Смуга склополотна по примиканнях і внутрішніх кутах — там, де тріщина '
                   || 'з’являється першою.'
    WHERE trade = 'DRYWALL' AND name = 'Проклеювання склополотном примикань і кутів';

    UPDATE catalog_templates
    SET description = 'Стик на паперовій стрічці високої щільності замість серпянки — рівень Q4.'
    WHERE trade = 'DRYWALL' AND name = 'Заповнення стиків ГКЛ паперовою стрічкою високої щільності';

    UPDATE catalog_templates
    SET description = 'Вологе знепилення перед фарбуванням — знімає пил, який суха обробка лишає '
                   || 'в порах. Обов’язкове для Q3+ і Q4.'
    WHERE trade = 'DRYWALL' AND name = 'Вологе обезпилювання поверхні';

    UPDATE catalog_templates
    SET description = 'Шліфування заповненого стику до площини аркуша. Окремий етап у Q2, Q3+ і Q4.'
    WHERE trade = 'DRYWALL' AND name = 'Шліфування стиків ГКЛ';

    -- description is not on CatalogItemRequest — a master cannot author or edit it — so pushing
    -- our new wording onto his LIBRARY copies overwrites nothing of his.
    UPDATE catalog_items ci
    SET description = ct.description
    FROM catalog_templates ct
    WHERE ct.trade = 'DRYWALL'
      AND ci.trade = 'DRYWALL' AND ci.source = 'LIBRARY'
      AND lower(trim(ci.name)) = lower(trim(ct.name))
      AND ci.type = ct.type AND ci.unit = ct.unit
      AND ci.description IS DISTINCT FROM ct.description;

    -- ==========================================================================================
    -- PART 3. The single «Підготовка ГКЛ під фарбування» bundle gives way to the five levels.
    -- ==========================================================================================
    -- Nothing is lost: Q4 carries every stage that bundle listed except «Шпаклювання та
    -- шліфування гіпсокартону (без склополотна)», which is exactly Q2's line.
    --
    -- ORDER MATTERS, same trap as V116 PART 9. template_default_override.template_id is
    -- ON DELETE CASCADE, so the override rows pointing at this default vanish WITH it — and they
    -- are the only way to find a master's forked copy. Fix the forks BEFORE the delete, or a fork
    -- keeps three lines naming positions PART 1 just retired and applies each of them at 0 ₴.
    SELECT id INTO v_bundle
    FROM estimate_templates
    WHERE is_default AND trade = 'DRYWALL' AND name = 'Підготовка ГКЛ під фарбування';

    IF v_bundle IS NULL THEN
        RAISE EXCEPTION 'V121: the finishing bundle V116 created is gone';
    END IF;

    DELETE FROM estimate_template_items i
    USING template_default_override o
    WHERE o.template_id = v_bundle
      AND i.template_id = o.forked_template_id
      AND lower(trim(i.name)) IN (SELECT name_key FROM _retired);

    -- A template the master wrote HIMSELF is never touched — if he typed a level position into
    -- his own bundle, that line is his, and it simply stops resolving to a price the same way any
    -- hand-typed line does.
    DELETE FROM estimate_templates WHERE id = v_bundle;   -- items + override rows cascade

    -- ==========================================================================================
    -- PART 4. Five levels, each a sequence.
    -- ==========================================================================================
    -- Q1/Q2 are short on purpose. The «a 3-4-line bundle is not worth reaching for» rule (V112) is
    -- about a bundle whose only value is saving taps; here the SHORTNESS is the product — Q1 is
    -- genuinely «стики і саморізи, далі плитка», and what the master reaches for is the named
    -- level and the paragraph the client reads, not the three lines.
    CREATE TEMP TABLE _levels (bundle text, pos int, item text) ON COMMIT DROP;
    INSERT INTO _levels VALUES
        ('Підготовка ГКЛ · Q1 — під плитку та панелі', 1, 'Заповнення та армування стиків ГКЛ'),
        ('Підготовка ГКЛ · Q1 — під плитку та панелі', 2, 'Шліфування стиків ГКЛ'),
        ('Підготовка ГКЛ · Q1 — під плитку та панелі', 3, 'Грунтування'),

        ('Підготовка ГКЛ · Q2 — під шпалери', 1, 'Заповнення та армування стиків ГКЛ'),
        ('Підготовка ГКЛ · Q2 — під шпалери', 2, 'Шліфування стиків ГКЛ'),
        ('Підготовка ГКЛ · Q2 — під шпалери', 3, 'Грунтування'),
        ('Підготовка ГКЛ · Q2 — під шпалери', 4, 'Шпаклювання та шліфування гіпсокартону (без склополотна)'),
        ('Підготовка ГКЛ · Q2 — під шпалери', 5, 'Обезпилення поверхні'),

        -- The matrix's own Q3 chain: 22 stages, and no separate joint sanding (his stage 1.2
        -- appears in Q3+ and Q4 only). A stage the matrix repeats — priming, знепилення — is
        -- listed ONCE; how many coats is the quantity, and the quantity is the master's.
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)',  1, 'Заповнення та армування стиків ГКЛ'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)',  2, 'Грунтування'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)',  3, 'Базове шпаклювання під скловолокно'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)',  4, 'Шліфування під скловолокно/склохолст'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)',  5, 'Обезпилення поверхні'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)',  6, 'Проклеювання склополотном примикань і кутів'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)',  7, 'Поклейка склополотна'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)',  8, 'Шпаклювання фінішне (2–4 рази)'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)',  9, 'Шліфування стін/стель (фінішне)'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)', 10, 'Криючий ґрунт-наповнювач'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)', 11, 'Локальне дефектування'),
        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)', 12, 'Мікрошліфування дефектів'),

        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)',  1, 'Заповнення та армування стиків ГКЛ'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)',  2, 'Шліфування стиків ГКЛ'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)',  3, 'Грунтування'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)',  4, 'Базове шпаклювання під скловолокно'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)',  5, 'Шліфування під скловолокно/склохолст'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)',  6, 'Обезпилення поверхні'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)',  7, 'Проклеювання склополотном примикань і кутів'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)',  8, 'Поклейка склополотна'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)',  9, 'Шпаклювання фінішне (2–4 рази)'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)', 10, 'Шліфування стін/стель (фінішне)'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)', 11, 'Криючий ґрунт-наповнювач'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)', 12, 'Локальне дефектування'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)', 13, 'Мікрошліфування дефектів'),
        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)', 14, 'Вологе обезпилювання поверхні'),

        -- Q4 differs from Q3+ in exactly one line: the joint is made on high-density paper tape.
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)',  1, 'Заповнення стиків ГКЛ паперовою стрічкою високої щільності'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)',  2, 'Шліфування стиків ГКЛ'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)',  3, 'Грунтування'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)',  4, 'Базове шпаклювання під скловолокно'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)',  5, 'Шліфування під скловолокно/склохолст'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)',  6, 'Обезпилення поверхні'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)',  7, 'Проклеювання склополотном примикань і кутів'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)',  8, 'Поклейка склополотна'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)',  9, 'Шпаклювання фінішне (2–4 рази)'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)', 10, 'Шліфування стін/стель (фінішне)'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)', 11, 'Криючий ґрунт-наповнювач'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)', 12, 'Локальне дефектування'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)', 13, 'Мікрошліфування дефектів'),
        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)', 14, 'Вологе обезпилювання поверхні');

    -- The paragraph the CLIENT reads under the table. Q3/Q3+/Q4 are the master's own matrix,
    -- re-typed rather than paraphrased; Q1/Q2 are the industry definitions he brought.
    CREATE TEMP TABLE _level_notes (bundle text, note text) ON COMMIT DROP;
    INSERT INTO _level_notes VALUES
        ('Підготовка ГКЛ · Q1 — під плитку та панелі',
         'Рівень Q1 — базове заповнення. Стики закриваються шпаклівкою з армувальною стрічкою, '
      || 'головки саморізів замазуються, шов шліфується до площини аркуша. Суцільне шпаклювання '
      || 'площин не виконується, тому під фарбу така поверхня не готова. Під що підходить: '
      || 'керамічна плитка, стінові панелі, груба фактурна штукатурка — оздоблення, яке саме '
      || 'перекриває фактуру аркуша.'),

        ('Підготовка ГКЛ · Q2 — під шпалери',
         'Рівень Q2 — стандартне оздоблення. Стик шпаклюється широко, перехід згладжується до '
      || 'площини аркуша, поверхня шліфується і знепилюється. Суцільного армування склополотном і '
      || 'контролю ковзним світлом немає. Під що підходить: стандартні та рельєфні шпалери, '
      || 'фактурні фарби, рідкі шпалери. Допуски: під бічним чи акцентним світлом переходи можуть '
      || 'проглядатися.'),

        ('Підготовка ГКЛ · Q3 — під матову фарбу (економ)',
         'Рівень Q3 (економ) — суцільна підготовка під фарбу. Стики заповнюються, площини '
      || 'шпаклюються під склополотно, армуються склополотном, далі фінішне шпаклювання і криючий '
      || 'ґрунт-наповнювач. Контроль ковзним світлом — 1 точка, після криючого ґрунту; дрібні '
      || 'дефекти попередніх шарів можуть лишитися під наступними. Вологе обезпилювання перед '
      || 'фарбуванням не виконується. Сумісні фарби: матові економ і середнього сегменту; сатин, '
      || 'напівглянець і глянець не допускаються. Гарантія: відсутність видимих слідів інструменту '
      || 'при стандартному розсіяному освітленні — при боковому чи акцентному світлі світлотіні '
      || 'допускаються.'),

        ('Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)',
         'Рівень Q3+ (преміум) — два цикли шпаклювання, до і після склополотна, зі шліфуванням '
      || 'стиків окремим етапом. Контроль ковзним світлом на 6 критичних переходах, вологе '
      || 'обезпилювання перед фарбуванням обов’язкове. Сумісні фарби: тільки матові та '
      || 'глибокоматові преміум-сегменту; сатин, напівглянець і глянець не допускаються. Допуски: '
      || 'при жорсткому боковому світлі можливі м’які незначні світлотіні — матова структура '
      || 'фарби поглинає їх при якісному загальному освітленні. Оцінювати Q3+ за критеріями Q4 '
      || 'під прожектором некоректно.'),

        ('Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)',
         'Рівень Q4 (еліт) — максимальна підготовка. Стики виконуються паперовою стрічкою високої '
      || 'щільності. Кожен ключовий перехід заблокований контролем ковзним світлом — 10 точок, '
      || 'дефект усувається на своєму етапі, а не закопується під наступний шар. Вологе '
      || 'обезпилювання перед фарбуванням обов’язкове. Сумісні фарби: будь-які — матові, '
      || 'глибокоматові, сатинові, напівглянцеві, глянцеві. Гарантія: відсутність світлотіней під '
      || 'будь-яким кутом освітлення, включно з боковим, трековим і акцентним.');

    INSERT INTO estimate_templates (id, owner_id, name, trade, is_default, description)
    SELECT gen_random_uuid(), NULL, n.bundle, 'DRYWALL', true, n.note
    FROM _level_notes n;

    -- Type and unit are read back from the catalog, never repeated here: the preview shows the
    -- bundle line's own unit and applying it then takes the catalog's, so any disagreement is a
    -- lie shown to the master (SeedCatalogInvariantsIntegrationTest pins it).
    INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
    SELECT gen_random_uuid(), et.id, ct.name, ct.type, ct.unit, l.pos
    FROM _levels l
    JOIN estimate_templates et ON et.is_default AND et.trade = 'DRYWALL' AND et.name = l.bundle
    JOIN catalog_templates ct ON ct.trade = 'DRYWALL' AND ct.name = l.item;

    SELECT count(*) INTO v_orphans
    FROM _levels l
    WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct
                      WHERE ct.trade = 'DRYWALL' AND ct.name = l.item);
    IF v_orphans > 0 THEN
        RAISE EXCEPTION 'V121: % bundle position(s) name a catalog row that does not exist', v_orphans;
    END IF;

    -- ==========================================================================================
    -- PART 5. The masters who already hold the three retired positions.
    -- ==========================================================================================
    -- The V116 guard, unchanged and still trade-aware: drop his LIBRARY copy only while it still
    -- carries OUR price (he never re-priced it) and only when NO trade he actually has still ships
    -- that name. These three wordings are DRYWALL-only, so in practice the second clause always
    -- passes — it stays because a trade-blind guard is the bug V116 had to fix.
    SELECT COUNT(DISTINCT user_id) INTO v_masters FROM user_trades WHERE trade = 'DRYWALL';

    CREATE TEMP TABLE _removed ON COMMIT DROP AS
    SELECT DISTINCT ci.id, ci.owner_id
    FROM catalog_items ci
    JOIN _retired r
      ON r.name_key = lower(trim(ci.name)) AND r.type = ci.type AND r.unit = ci.unit
    WHERE ci.source = 'LIBRARY'
      AND ci.default_price = r.suggested_price
      AND NOT EXISTS (
          SELECT 1 FROM catalog_templates ct
          JOIN user_trades ut ON ut.user_id = ci.owner_id AND ut.trade = ct.trade
          WHERE lower(trim(ct.name)) = lower(trim(ci.name))
            AND ct.type = ci.type AND ct.unit = ci.unit);

    DELETE FROM catalog_items ci USING _removed r WHERE ci.id = r.id;
    GET DIAGNOSTICS v_removed = ROW_COUNT;

    -- One deploy, one notice — the V117/V120 rule. Only an undismissed COUNT notice is topped up.
    UPDATE catalog_update_notices n
    SET positions_removed = n.positions_removed + x.cnt
    FROM (SELECT owner_id, count(*) AS cnt FROM _removed GROUP BY owner_id) x
    WHERE n.user_id = x.owner_id AND n.kind = 'COUNT' AND n.dismissed_at IS NULL;

    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed)
    SELECT gen_random_uuid(), x.owner_id, 'COUNT', 0, x.cnt
    FROM (SELECT owner_id, count(*) AS cnt FROM _removed GROUP BY owner_id) x
    WHERE NOT EXISTS (SELECT 1 FROM catalog_update_notices n
                      WHERE n.user_id = x.owner_id AND n.kind = 'COUNT' AND n.dismissed_at IS NULL);

    -- ==========================================================================================
    -- PART 6. Self-checks.
    -- ==========================================================================================
    IF EXISTS (SELECT 1 FROM catalog_templates
               WHERE trade = 'DRYWALL' AND name LIKE 'Підготовка ГКЛ під фарбування ·%') THEN
        RAISE EXCEPTION 'V121: a finish level is still sold as a catalog position';
    END IF;

    -- The whole point of the round: a position keeps a hint, a bundle keeps the paragraph.
    IF EXISTS (SELECT 1 FROM catalog_templates
               WHERE trade = 'DRYWALL' AND length(description) > 200) THEN
        RAISE EXCEPTION 'V121: a DRYWALL position still carries a paragraph-length description';
    END IF;

    IF (SELECT count(*) FROM estimate_templates
        WHERE is_default AND trade = 'DRYWALL' AND description IS NOT NULL) <> 5 THEN
        RAISE EXCEPTION 'V121: expected five described level bundles';
    END IF;

    IF EXISTS (SELECT 1 FROM estimate_templates et
               JOIN _level_notes n ON n.bundle = et.name
               WHERE et.is_default AND et.trade = 'DRYWALL'
                 AND (SELECT count(*) FROM estimate_template_items i WHERE i.template_id = et.id)
                     <> (SELECT count(*) FROM _levels l WHERE l.bundle = et.name)) THEN
        RAISE EXCEPTION 'V121: a level bundle is short a line';
    END IF;

    IF EXISTS (SELECT et.id FROM estimate_templates et
               JOIN estimate_template_items i ON i.template_id = et.id
               WHERE et.is_default AND et.trade = 'DRYWALL'
               GROUP BY et.id
               HAVING count(DISTINCT i.sort_order) <> count(*)) THEN
        RAISE EXCEPTION 'V121: two lines of a DRYWALL bundle share a sort_order';
    END IF;

    -- Every default bundle line must resolve to a catalog position, or it applies at 0 ₴.
    SELECT count(*) INTO v_orphans
    FROM estimate_template_items i
    JOIN estimate_templates et ON et.id = i.template_id
    WHERE et.is_default AND et.trade = 'DRYWALL'
      AND NOT EXISTS (SELECT 1 FROM catalog_templates ct
                      WHERE ct.trade = 'DRYWALL' AND lower(trim(ct.name)) = lower(trim(i.name))
                        AND ct.type = i.type AND ct.unit = i.unit);
    IF v_orphans > 0 THEN
        RAISE EXCEPTION 'V121: % default bundle line(s) would apply at 0 UAH', v_orphans;
    END IF;

    RAISE NOTICE 'V121 drywall: 5 level bundles, -3 positions, % masters, -% catalog rows',
                 v_masters, v_removed;
END $$;
