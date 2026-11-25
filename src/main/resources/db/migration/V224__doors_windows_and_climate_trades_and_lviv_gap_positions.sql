-- =================================================================================================
-- V224 — two new trades, DOORS_WINDOWS («Двері та вікна») and CLIMATE («Клімат і вентиляція»),
-- and 161 new work positions across eleven trades (2026-11-25)
--
-- Source: a Lviv-oblast average price sheet (76 screenshots, 560 lines, 29 sections) compared line by
-- line with the default library; every job the library had no position for is added here. Each price
-- is the MEDIAN of the public sources found for it (rabotniki.ua / budver.com averages by city, firms'
-- price lists, kabanchik) with the Lviv figure as ONE source among them; the comment under each row
-- names them. A position only the Lviv sheet prices ships at the Lviv figure (owner ruling,
-- 2026-11-25, marked [one source]); a [thin] row rests on two sources. Brick-wall demolition 1,5 / 2
-- bricks ships at the market median 270 / 300 although the library's «в цеглину» is 380 (owner
-- ruling). Names are the work, no brand; established names are left alone (owner: no renames).
--
-- The two new trades: a door / window fitter and an AC / ventilation installer are specialities of
-- their own, so their positions are a trade rather than BUILDER / PLUMBING rows a floor layer or a
-- plumber would wade through. A master who also fits doors ticks the trade. Each new trade also
-- SHARES a few positions other trades already ship (V220: a row per trade) at their current price:
-- door / window removal, drip edges; the AC power line, ventilation duct and grille.
--
-- V176 / V216's shape for additions: ONE new catalog version and NOTHING pushed — a master takes them
-- with «Додати нові з довідника» (`last_synced_catalog_version` NOT advanced), no notice. No material
-- norms, no default bundles for the new trades. V118's ranking is re-run with the two trades placed
-- after METAL (V120's rule).
--
-- Nothing refuses on the live library's state: a position the trade already sells is skipped with a
-- WARNING. Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 0. Allow the two trades wherever a trade is constrained. template_trade_override keeps its own
--    list (it never took METAL either): default bundles exist for neither new trade.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE user_trades        DROP CONSTRAINT user_trades_trade_check;
ALTER TABLE user_trades        ADD  CONSTRAINT user_trades_trade_check
    CHECK (trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'METAL', 'DOORS_WINDOWS', 'CLIMATE', 'GENERAL', 'OTHER'));
ALTER TABLE catalog_templates  DROP CONSTRAINT catalog_templates_trade_check;
ALTER TABLE catalog_templates  ADD  CONSTRAINT catalog_templates_trade_check
    CHECK (trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'METAL', 'DOORS_WINDOWS', 'CLIMATE', 'GENERAL', 'OTHER'));
ALTER TABLE catalog_items      DROP CONSTRAINT catalog_items_trade_check;
ALTER TABLE catalog_items      ADD  CONSTRAINT catalog_items_trade_check
    CHECK (trade IS NULL OR trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'METAL', 'DOORS_WINDOWS', 'CLIMATE', 'GENERAL', 'OTHER'));
ALTER TABLE estimate_templates DROP CONSTRAINT estimate_templates_trade_check;
ALTER TABLE estimate_templates ADD  CONSTRAINT estimate_templates_trade_check
    CHECK (trade IS NULL OR trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'METAL', 'DOORS_WINDOWS', 'CLIMATE', 'GENERAL', 'OTHER'));
ALTER TABLE estimate_items     DROP CONSTRAINT estimate_items_trade_check;
ALTER TABLE estimate_items     ADD  CONSTRAINT estimate_items_trade_check
    CHECK (trade IS NULL OR trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'METAL', 'DOORS_WINDOWS', 'CLIMATE', 'GENERAL', 'OTHER'));
ALTER TABLE material_norm      DROP CONSTRAINT material_norm_trade_check;
ALTER TABLE material_norm      ADD  CONSTRAINT material_norm_trade_check
    CHECK (trade IS NULL OR trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'METAL', 'DOORS_WINDOWS', 'CLIMATE', 'GENERAL', 'OTHER'));

CREATE TEMP TABLE _pos (trade varchar(50), category varchar(100), name varchar(255), unit varchar(20),
                        price numeric(15,2), description varchar(500)) ON COMMIT DROP;

INSERT INTO _pos VALUES
    -- DOORS_WINDOWS
    ('DOORS_WINDOWS', 'Двері', 'Встановлення міжкімнатних дверей', 'PIECE', 2200.00, NULL),
        -- portes 1300; олімп 1850; одеса 1000; budver 2750; біржі 2820; Lviv 2500
    ('DOORS_WINDOWS', 'Двері', 'Встановлення двостулкових міжкімнатних дверей', 'PIECE', 2000.00, NULL),
        -- portes 1450; дніпро 2000; Lviv 3000  [thin]
    ('DOORS_WINDOWS', 'Двері', 'Встановлення вхідних металевих дверей', 'PIECE', 3300.00, NULL),
        -- rabotniki 4115; харків 4250; дніпро 3250; metaldoors 2400; domremont 1200; kabanchik 1500; Lviv 3300
    ('DOORS_WINDOWS', 'Двері', 'Встановлення розсувних дверей-купе (одна стулка)', 'PIECE', 2500.00, NULL),
        -- одеса 1300; агрегатор 3000; Lviv 2500  [thin]
    ('DOORS_WINDOWS', 'Двері', 'Встановлення розсувних дверей-купе (дві стулки)', 'PIECE', 3000.00, NULL),
        -- Lviv 3000  [one source]
    ('DOORS_WINDOWS', 'Двері', 'Встановлення дверей-гармошки', 'PIECE', 1200.00, NULL),
        -- одеса 1200; helpo 700 (old); Lviv 2700  [thin]
    ('DOORS_WINDOWS', 'Двері', 'Встановлення доборів (комплект)', 'SET', 200.00, 'Комплект на один дверний блок.'),
        -- portes 250; олімп 200; homedoors 200; одеса 150; Lviv 140/м.п.≈700
    ('DOORS_WINDOWS', 'Двері', 'Встановлення лиштви (комплект)', 'SET', 250.00, 'Комплект на один бік дверного блоку.'),
        -- олімп 200; portes 250; біржа 220; одеса 400; Lviv 85/м.п.≈425
    ('DOORS_WINDOWS', 'Двері', 'Врізка замка', 'PIECE', 350.00, NULL),
        -- олімп 250; дніпро 200; homedoors 250; одеса 450; біржі 730; Lviv 620
    ('DOORS_WINDOWS', 'Двері', 'Врізка петлі', 'PIECE', 150.00, NULL),
        -- олімп 150; дніпро 50; homedoors 100; біржа 275; Lviv 240
    ('DOORS_WINDOWS', 'Двері', 'Врізка шпінгалета', 'PIECE', 240.00, NULL),
        -- Lviv 240  [one source]
    ('DOORS_WINDOWS', 'Двері', 'Встановлення доводчика', 'PIECE', 750.00, NULL),
        -- rabotniki 753; київ 943; budver 1210; одеса 400; Lviv 650
    ('DOORS_WINDOWS', 'Двері', 'Встановлення обмежувача ходу дверей', 'PIECE', 160.00, NULL),
        -- Lviv 160  [one source]
    ('DOORS_WINDOWS', 'Вікна', 'Монтаж вікон ПВХ', 'M2', 570.00, NULL),
        -- rabotniki 601; київ 573; харків 534; рівне 551; tvoeokno 760; запоріжжя 450; Lviv 650
    ('DOORS_WINDOWS', 'Вікна', 'Монтаж алюмінієвих вікон', 'M2', 800.00, NULL),
        -- market-vikna 900; alumil 800; rama 700; Lviv 730; market-vikna збірні 1000
    ('DOORS_WINDOWS', 'Вікна', 'Монтаж дерев''яних віконних блоків', 'M2', 700.00, NULL),
        -- Lviv 700  [one source]
    ('DOORS_WINDOWS', 'Вікна', 'Монтаж пластикових укосів', 'LINEAR_METER', 340.00, NULL),
        -- київ 300; зведення 382; okna-shop 220; лінія вікон 370; remontdoma 750; Lviv 280
    ('DOORS_WINDOWS', 'Вікна', 'Монтаж підвіконня ПВХ', 'LINEAR_METER', 350.00, NULL),
        -- rabotniki 366; київ 349; Lviv 300
    ('DOORS_WINDOWS', 'Вікна', 'Монтаж дерев''яного підвіконня', 'LINEAR_METER', 320.00, NULL),
        -- Lviv 320  [one source]
    ('DOORS_WINDOWS', 'Вікна', 'Монтаж підвіконня з натурального каменю', 'LINEAR_METER', 750.00, NULL),
        -- Lviv 750  [one source]
    ('DOORS_WINDOWS', 'Вікна', 'Встановлення жалюзі', 'PIECE', 360.00, 'За один виріб.'),
        -- budver 363; budver 385; rabotniki 383; rabotniki рулонні 350; rabotniki 150; Lviv 215/м²
    ('DOORS_WINDOWS', 'Вікна', 'Монтаж тканинних і пластикових ролет', 'M2', 480.00, NULL),
        -- rabotniki 481; київ 495; Lviv 470
    ('DOORS_WINDOWS', 'Вікна', 'Монтаж захисних металевих ролет', 'M2', 1250.00, NULL),
        -- rabotniki 1445; київ 1347; budver 1056; Lviv 1150
    -- CLIMATE
    ('CLIMATE', 'Кондиціонери', 'Монтаж кондиціонера 7–12 тис. BTU', 'PIECE', 6800.00, NULL),
        -- rabotniki одеса 7200; запоріжжя 7200; кам'янське 7014; полтава 6533; ів-франківськ 5848; Lviv 4500
    ('CLIMATE', 'Кондиціонери', 'Монтаж кондиціонера 15–18 тис. BTU', 'PIECE', 8900.00, NULL),
        -- одеса 9025; запоріжжя 9025; кам'янське 8729; ів-франківськ 9027; полтава 8025; Lviv 5300
    ('CLIMATE', 'Кондиціонери', 'Монтаж кондиціонера 24 тис. BTU', 'PIECE', 9950.00, NULL),
        -- одеса 9950; запоріжжя 9950; кам'янське 9943; ів-франківськ 10273; полтава 8950; Lviv 6200
    ('CLIMATE', 'Кондиціонери', 'Монтаж кондиціонера 30–36 тис. BTU', 'PIECE', 11000.00, NULL),
        -- одеса 11300; запоріжжя 11300; кам'янське 10675; ів-франківськ 11583; полтава 10050; Lviv 7200
    ('CLIMATE', 'Опалення повітрям', 'Монтаж теплової завіси до 1 м', 'PIECE', 2700.00, NULL),
        -- alterair 3200; techhome 2100; vencon 5300; Lviv 2200
    ('CLIMATE', 'Опалення повітрям', 'Монтаж теплової завіси понад 1 м', 'PIECE', 4000.00, NULL),
        -- alterair 4000; vencon 5300; агрегатор 7200; Lviv 2600/3300/3800
    ('CLIMATE', 'Опалення повітрям', 'Монтаж інфрачервоного обігрівача', 'PIECE', 1900.00, NULL),
        -- Lviv 1900  [one source]
    ('CLIMATE', 'Вентиляція', 'Монтаж припливно-витяжної установки до 300 м³/год', 'PIECE', 4700.00, NULL),
        -- stryk від 3500; vitalclimat 4900; Lviv 4700  [thin]
    ('CLIMATE', 'Вентиляція', 'Монтаж припливно-витяжної установки до 700 м³/год', 'PIECE', 8000.00, NULL),
        -- kingsystem від 8500; Lviv 7500  [thin]
    ('CLIMATE', 'Вентиляція', 'Монтаж припливно-витяжної установки до 1500 м³/год', 'PIECE', 10000.00, NULL),
        -- kingsystem від 8500; Lviv 11500  [thin]
    ('CLIMATE', 'Вентиляція', 'Монтаж припливно-витяжної установки понад 1500 м³/год', 'PIECE', 18000.00, NULL),
        -- Lviv 18000 / 25000  [one source]
    ('CLIMATE', 'Вентиляція', 'Монтаж канального вентилятора Ø100–160 мм', 'PIECE', 580.00, NULL),
        -- kingsystem 880; ensy 500; tehno-master 500; stryk 2000; rabotniki 424; Lviv 650
    ('CLIMATE', 'Вентиляція', 'Монтаж канального вентилятора Ø200–400 мм', 'PIECE', 1400.00, NULL),
        -- ensy (до 40 кг) 1500; Lviv 850 / 1400  [thin]
    ('CLIMATE', 'Вентиляція', 'Монтаж прямокутного канального вентилятора', 'PIECE', 1700.00, NULL),
        -- Lviv 1700 / 2400  [one source]
    ('CLIMATE', 'Вентиляція', 'Монтаж радіального вентилятора', 'PIECE', 2400.00, NULL),
        -- Lviv 2400  [one source]
    ('CLIMATE', 'Вентиляція', 'Монтаж дахового вентилятора', 'PIECE', 2900.00, NULL),
        -- Lviv 2900  [one source]
    ('CLIMATE', 'Вентиляція', 'Монтаж круглого повітропроводу Ø до 200 мм', 'LINEAR_METER', 180.00, NULL),
        -- kingsystem 190; ensy 200; stryk 140; kt-1 80; Lviv 175
    ('CLIMATE', 'Вентиляція', 'Монтаж круглого повітропроводу Ø250–400 мм', 'LINEAR_METER', 190.00, NULL),
        -- kt-1 140; Lviv 190  [thin]
    ('CLIMATE', 'Вентиляція', 'Монтаж круглого повітропроводу Ø450–800 мм', 'LINEAR_METER', 330.00, NULL),
        -- Lviv 330  [one source]
    ('CLIMATE', 'Вентиляція', 'Монтаж прямокутного повітропроводу', 'M2', 330.00, NULL),
        -- kingsystem 400; tehno-master 220; Lviv 330
    -- PLUMBING
    ('PLUMBING', 'Котел', 'Монтаж газового одноконтурного котла до 32 кВт', 'PIECE', 4000.00, NULL),
        -- rabotniki двоконтурний 4278 (аналог); Lviv 3200  [thin; = наш двоконтурний 4000]
    ('PLUMBING', 'Котел', 'Монтаж підлогового газового або дизельного котла до 60 кВт', 'PIECE', 5900.00, NULL),
        -- rabotniki до 55 кВт 6514; Lviv 5200  [thin]
    ('PLUMBING', 'Котел', 'Монтаж підлогового газового або дизельного котла понад 60 кВт', 'PIECE', 8500.00, NULL),
        -- rabotniki 55–100 кВт 8879; rabotniki 8533; Lviv 8000
    ('PLUMBING', 'Котел', 'Монтаж твердопаливного котла понад 50 кВт', 'PIECE', 11000.00, NULL),
        -- Lviv 11000 (до 55 кВт: rabotniki 9700, суми 10832, ковель 8945, львів 9771)  [one source for >50]
    ('PLUMBING', 'Котел', 'Монтаж пелетного котла понад 50 кВт', 'PIECE', 14400.00, NULL),
        -- Lviv 15000; бровари 12550; суми 14433 (без поділу потужності)  [thin]
    ('PLUMBING', 'Опалення', 'Монтаж поліпропіленових труб опалення Ø55–63 мм', 'LINEAR_METER', 160.00, NULL),
        -- rabotniki 184; Lviv 125  [thin]
    ('PLUMBING', 'Опалення', 'Монтаж металопластикових труб опалення Ø55–63 мм', 'LINEAR_METER', 120.00, NULL),
        -- Lviv 115  [one source]
    ('PLUMBING', 'Опалення', 'Монтаж мідних труб опалення Ø35–54 мм', 'LINEAR_METER', 210.00, NULL),
        -- rabotniki мідь 224; budver 148; ремонт-львів 200; Lviv 210
    ('PLUMBING', 'Водопостачання', 'Монтаж металопластикових труб Ø16–40 мм', 'LINEAR_METER', 70.00, NULL),
        -- rabotniki 91; budver 55; Lviv 70
    ('PLUMBING', 'Водопостачання', 'Монтаж поліпропіленових труб Ø16–40 мм', 'LINEAR_METER', 80.00, NULL),
        -- rabotniki 82; budver 55; тернопіль 100; ремонт-львів 160; Lviv 75
    ('PLUMBING', 'Водопостачання', 'Монтаж мідних труб Ø15–28 мм', 'LINEAR_METER', 180.00, NULL),
        -- rabotniki 224; budver 148; ремонт-львів 200; Lviv 160
    ('PLUMBING', 'Опалення', 'Монтаж теплоізоляції на трубопровід', 'LINEAR_METER', 35.00, NULL),
        -- ремонт-львів 30; Lviv 40  [thin]
    ('PLUMBING', 'Опалення', 'Монтаж автоматичного повітровідвідника', 'PIECE', 240.00, NULL),
        -- ремонт-львів 100; Lviv 370  [thin]
    ('PLUMBING', 'Опалення', 'Монтаж термометра або термоманометра', 'PIECE', 290.00, NULL),
        -- Lviv 370; наш манометр 200  [thin]
    ('PLUMBING', 'Опалення', 'Укладання водяної теплої підлоги (труба по готовій основі)', 'M2', 320.00, NULL),
        -- ремонт-львів 270; дніпро 350; харків 250; alterair 550; Lviv 320
    ('PLUMBING', 'Сантехприлади', 'Заміна зливного бачка унітазу', 'PIECE', 850.00, NULL),
        -- san-sanych 1300; home-master 1550; santehniks 850; rimeik 500; Lviv 750
    ('PLUMBING', 'Сантехприлади', 'Заміна гофри унітазу', 'PIECE', 300.00, NULL),
        -- київ 775; ів-франківськ 150; харків 200; Lviv 400
    ('PLUMBING', 'Сантехприлади', 'Монтаж пластикових шторок на ванну', 'PIECE', 1000.00, NULL),
        -- grs 400; santehnik.in.ua 1000; Lviv 1100
    ('PLUMBING', 'Сантехприлади', 'Монтаж обв''язки ванни (сифон, перелив)', 'PIECE', 650.00, NULL),
        -- santehnik.in.ua 650; san-sanych 900; restvann 700; тернопіль 340; Lviv 430
    ('PLUMBING', 'Сантехприлади', 'Заміна гнучкої підводки', 'PIECE', 220.00, NULL),
        -- rabotniki 224; черкаси 250; черкаси монтаж 200; delay-krasivo 280; Lviv 190
    ('PLUMBING', 'Сантехприлади', 'Монтаж термостатичного змішувача', 'PIECE', 750.00, NULL),
        -- черкаси 750; київ 750; київ 1600; Lviv 750
    ('PLUMBING', 'Сантехприлади', 'Герметизація швів раковини', 'LINEAR_METER', 170.00, NULL),
        -- Lviv 170; наша герметизація ванни 175  [thin]
    -- BUILDER
    ('BUILDER', 'Земляні', 'Розробка ґрунту екскаватором', 'M3', 180.00, NULL),
        -- stroitel 210; rating-remont 120; rating-remont котлован 150; Lviv 220
    ('BUILDER', 'Земляні', 'Зачищення дна траншеї, котловану вручну', 'M2', 75.00, NULL),
        -- Lviv 75  [one source]
    ('BUILDER', 'Земляні', 'Буріння ями під стовп', 'PIECE', 150.00, NULL),
        -- rating-remont вручну 150; технікою 120; Lviv 270/м.п.  [thin]
    ('BUILDER', 'Земляні', 'Планування ділянки технікою', 'M2', 60.00, NULL),
        -- rating-remont 15; Lviv 90  [thin]
    ('BUILDER', 'Земляні', 'Виїмка корита під доріжки', 'M2', 185.00, NULL),
        -- Lviv 185  [one source]
    ('BUILDER', 'Земляні', 'Влаштування ущільненої основи з піску', 'M3', 750.00, NULL),
        -- rabotniki 710; rabotniki львів 830; Lviv 750
    ('BUILDER', 'Земляні', 'Влаштування ущільненої основи зі щебеню', 'M3', 800.00, NULL),
        -- Lviv 800  [one source]
    ('BUILDER', 'Фундамент', 'Монолітний стрічковий фундамент (опалубка, арматура, бетонування)', 'M3', 3800.00, NULL),
        -- budver 3300; хмельницький 3970; дніпро 4600; київ 2000; агрегатор 3800; Lviv 3800
    ('BUILDER', 'Фундамент', 'Монолітний ростверк (опалубка, арматура, бетон)', 'M3', 4150.00, NULL),
        -- підрядник 3500; Lviv 4800  [thin]
    ('BUILDER', 'Фундамент', 'Монолітна підпірна стінка', 'M3', 4800.00, NULL),
        -- rabotniki 4653; budver 6875; rabotniki львів 3875; Lviv 5000
    ('BUILDER', 'Споруди', 'Монолітна чаша басейну', 'M3', 5200.00, NULL),
        -- Lviv 5200  [one source]
    ('BUILDER', 'Споруди', 'Монолітна плита балкона', 'M2', 2550.00, NULL),
        -- Lviv 2550  [one source]
    ('BUILDER', 'Фундамент', 'Монолітний фундамент під колони', 'M3', 4200.00, NULL),
        -- Lviv 4200  [one source]
    ('BUILDER', 'Фундамент', 'Посилення фундаменту бетонною підливкою', 'M3', 3600.00, NULL),
        -- Lviv 3600  [one source]
    ('BUILDER', 'Стяжка', 'Бетонна підлога з топінгом', 'M2', 530.00, NULL),
        -- rabotniki 540; київ 640; budver 429; optima 400; budova 520; Lviv 650
    ('BUILDER', 'Стяжка', 'Бетонна підлога зі шліфуванням (затирання машиною)', 'M2', 550.00, NULL),
        -- Lviv 550  [one source]
    ('BUILDER', 'Покрівля', 'Монтаж мауерлату', 'LINEAR_METER', 170.00, NULL),
        -- budver 132; вінниця 143; бердичів 165; rating-remont 200; novii-dom 300; Lviv 180
    ('BUILDER', 'Покрівля', 'Монтаж коника', 'LINEAR_METER', 170.00, NULL),
        -- rating-remont 90; вінниця 165; ів-франківськ 187; кропивницький 154; Lviv 200
    ('BUILDER', 'Покрівля', 'Монтаж покрівельного аератора', 'PIECE', 400.00, NULL),
        -- Lviv 400  [one source]
    ('BUILDER', 'Покрівля', 'Монтаж сланцевої покрівлі', 'M2', 750.00, NULL),
        -- Lviv 750  [one source]
    ('BUILDER', 'Покрівля', 'Монтаж суцільної обрешітки з дошки', 'M2', 160.00, NULL),
        -- київ 300; обрешітка 110; Lviv 160
    ('BUILDER', 'Покрівля', 'Настил з фанери або ОСП по кроквах', 'M2', 170.00, NULL),
        -- ів-франківськ 132; кропивницький 154; хмельницький 165; київ 200; Lviv 170
    ('BUILDER', 'Покрівля', 'Каркас карнизних і фронтонних звисів', 'LINEAR_METER', 170.00, NULL),
        -- Lviv 170  [one source]
    ('BUILDER', 'Покрівля', 'Фарбування карнизних і торцевих звисів у 2 шари', 'LINEAR_METER', 160.00, NULL),
        -- Lviv 160  [one source]
    ('BUILDER', 'Кладка', 'Кладка зі склоблоків', 'M2', 800.00, NULL),
        -- kabanchik (категорія) 760; Lviv 850  [thin]
    ('BUILDER', 'Кладка', 'Бутова кладка з природного каменю', 'M3', 3650.00, NULL),
        -- підрядник 3000; Lviv 4300  [thin]
    ('BUILDER', 'Кладка', 'Кладка перегородок з гіпсових пазогребневих плит', 'M2', 550.00, NULL),
        -- budver 818; хмельницький 550; тернопіль 536; харків 300; Lviv 620
    ('BUILDER', 'Гідроізоляція', 'Обклеювальна (рулонна) гідроізоляція горизонтальна', 'M2', 160.00, NULL),
        -- rabotniki 150; київ 160; stroitel 150; optima 280; arsel 400; Lviv 160
    ('BUILDER', 'Гідроізоляція', 'Обклеювальна (рулонна) гідроізоляція вертикальна', 'M2', 170.00, NULL),
        -- ті самі джерела; Lviv 170
    ('BUILDER', 'Гідроізоляція', 'Обмазувальна гідроізоляція бітумною мастикою (1 шар)', 'M2', 150.00, NULL),
        -- rabotniki 149; stroitel 145; optima 180; Lviv 120 / 125
    ('BUILDER', 'Гідроізоляція', 'Гідроізоляція вводу комунікацій у фундамент', 'PIECE', 700.00, NULL),
        -- waterproof 650; Lviv 620 / 750 / 1100
    ('BUILDER', 'Стяжка', 'Засипна ізоляція керамзитом', 'M3', 520.00, NULL),
        -- засипка (аналог) budver 523; засипка з ущільненням 688; Lviv 340  [thin]
    ('BUILDER', 'Стяжка', 'Шумоізоляція під стяжку (рулон або мати)', 'M2', 120.00, NULL),
        -- Lviv 120  [one source]
    ('BUILDER', 'Дерев''яні будинки', 'Монтаж СІП-панелей', 'M2', 480.00, NULL),
        -- rabotniki київ 445; rabotniki 496; Lviv 480
    ('BUILDER', 'Дерев''яні будинки', 'Каркасний будинок без оздоблення (коробка)', 'M2', 8900.00, 'За м² площі будинку: каркас, обшивка, перекриття, дах; без оздоблення та інженерії.'),
        -- rabotniki 9246; rabotniki SIP 6633; asgroup 11200; Lviv 8500
    ('BUILDER', 'Дерев''яні будинки', 'Зведення стін з профільованого бруса', 'M3', 11000.00, NULL),
        -- Lviv 11000  [one source]
    ('BUILDER', 'Дерев''яні будинки', 'Зведення стін з оциліндрованої колоди', 'M3', 11400.00, NULL),
        -- Lviv 11400  [one source]
    ('BUILDER', 'Дерев''яні будинки', 'Конопатка міжвінцевих швів', 'LINEAR_METER', 80.00, NULL),
        -- Lviv 80  [one source]
    ('BUILDER', 'Дерев''яні будинки', 'Монтаж чорнової стелі з дошки', 'M2', 140.00, NULL),
        -- Lviv 140  [one source]
    ('BUILDER', 'Дерев''яні будинки', 'Оздоблення блок-хаусом', 'M2', 440.00, NULL),
        -- rabotniki 465; Lviv 420  [thin]
    ('BUILDER', 'Дерев''яні будинки', 'Оздоблення імітацією бруса', 'M2', 420.00, NULL),
        -- rabotniki фальшбрус 448; Lviv 400  [thin]
    ('BUILDER', 'Фасад', 'Утеплення фасаду мінватою (клей, дюбелі, без армування)', 'M2', 450.00, NULL),
        -- vip-oksher приклеювання з дюбелями 620; Lviv 270 (наш пінопласт той самий обсяг — 300)  [thin]
    ('BUILDER', 'Паркан', 'Монтаж паркану з полікарбонату', 'M2', 450.00, NULL),
        -- budver 440; rabotniki 406; rabotniki львів 465; Lviv 730
    ('BUILDER', 'Благоустрій', 'Мощення бруківки на готову основу', 'M2', 340.00, NULL),
        -- київ 400; київ 400; гарантспецбуд львів 200; Lviv 270
    -- DRYWALL
    ('DRYWALL', 'Каркас і обшивка', 'Монтаж другого рівня стелі з ГКЛ (прямі елементи)', 'LINEAR_METER', 440.00, NULL),
        -- budver 308; budver 440; rabotniki 499; приклад 305; Lviv 470
    ('DRYWALL', 'Каркас і обшивка', 'Монтаж другого рівня стелі з ГКЛ (криволінійні елементи)', 'LINEAR_METER', 500.00, NULL),
        -- budver 385; budver 418; rabotniki 588; Lviv 570
    ('DRYWALL', 'Каркас і обшивка', 'Влаштування арки з ГКЛ', 'PIECE', 1300.00, NULL),
        -- budver 1100; budver 935; rabotniki 1343; харків 1360; одеса 2000; Lviv 1300
    -- PAINTER
    ('PAINTER', 'Стелі', 'Монтаж рейкової стелі', 'M2', 270.00, NULL),
        -- budver 120; budver 150; київ 300; дніпро 265; луцьк 350; Lviv 280
    ('PAINTER', 'Стелі', 'Монтаж профілю «прищіпка» для тканинної натяжної стелі', 'LINEAR_METER', 80.00, NULL),
        -- stelio ПВХ 80 (аналог); Lviv 75  [thin]
    ('PAINTER', 'Стелі', 'Окантовка труби в натяжній стелі', 'PIECE', 250.00, NULL),
        -- Lviv 250  [one source]
    ('PAINTER', 'Шпалери', 'Поклейка бамбукових шпалер', 'M2', 260.00, NULL),
        -- kabanchik 140; budver 198; rabotniki київ 282; teplokomfort 260; Lviv 270
    ('PAINTER', 'Шпалери', 'Поклейка коркових шпалер', 'M2', 230.00, NULL),
        -- budver 181; poslugy 225; teplokomfort 260; Lviv 235
    ('PAINTER', 'Шпалери', 'Поклейка текстильних шпалер', 'M2', 240.00, NULL),
        -- budver 187; budver 176; rabotniki київ 276; teplokomfort 240; Lviv 255
    ('PAINTER', 'Шпалери', 'Поклейка флокових шпалер', 'M2', 245.00, NULL),
        -- Lviv 245  [one source]
    ('PAINTER', 'Шпалери', 'Поклейка шпалер у два рівні (з розділенням)', 'M2', 200.00, NULL),
        -- Lviv 195  [one source]
    ('PAINTER', 'Шпалери', 'Наклеювання шпалерного бордюру', 'LINEAR_METER', 70.00, NULL),
        -- budver 165; budver 143; ктс 50; studia-remonta львів 70; Lviv 70
    ('PAINTER', 'Декоративна штукатурка', 'Декоративна штукатурка «кракелюр»', 'M2', 630.00, NULL),
        -- rabotniki 629; хмельницький 579; кривий ріг 747; teplokomfort 900; Lviv 570
    ('PAINTER', 'Декоративна штукатурка', 'Декоративна штукатурка «креос»', 'M2', 470.00, NULL),
        -- Lviv 470  [one source]
    ('PAINTER', 'Оздоблення', 'Обшивка стін панелями МДФ', 'M2', 310.00, NULL),
        -- rabotniki 327; rabotniki 311; meter 220; Lviv 300
    ('PAINTER', 'Оздоблення', 'Обшивка стелі панелями МДФ', 'M2', 330.00, NULL),
        -- Lviv 320 (стіни 310)  [one source]
    ('PAINTER', 'Вагонка', 'Обшивка стін пластиковою вагонкою', 'M2', 300.00, NULL),
        -- rabotniki 327; rabotniki 311; meter 220; Lviv 285
    -- TILING
    ('TILING', 'Облицювання сходів', 'Укладання плитки на радіусні сходинки', 'LINEAR_METER', 1000.00, NULL),
        -- remontbud 810; Lviv 1200  [thin]
    ('TILING', 'Укладання плитки', 'Монтаж декоративного куточка для плитки', 'LINEAR_METER', 170.00, NULL),
        -- Lviv 170  [one source]
    ('TILING', 'Оброблення плитки та вироби', 'Свердління отвору в керамічній плитці', 'PIECE', 230.00, NULL),
        -- remmo львів 200; тернопіль 230; Lviv 240
    ('TILING', 'Оброблення плитки та вироби', 'Свердління отвору в керамограніті', 'PIECE', 340.00, NULL),
        -- kabanchik київ 620; remmo львів 250; тернопіль 320; stroitel 360; Lviv 340
    -- ELECTRICAL
    ('ELECTRICAL', 'Освітлення', 'Монтаж гака для люстри', 'PIECE', 180.00, NULL),
        -- eden-bud 132; Lviv 230  [thin]
    ('ELECTRICAL', 'Розетки', 'Установка диммера', 'PIECE', 310.00, NULL),
        -- san-sanych 400; Lviv 220  [thin]
    ('ELECTRICAL', 'Кабель', 'Протягування кабелю в прокладену гофру', 'LINEAR_METER', 25.00, NULL),
        -- Lviv 25  [one source]
    ('ELECTRICAL', 'Генератор та АВР', 'Монтаж однофазного інвертора з підключенням до готової лінії', 'PIECE', 4500.00, NULL),
        -- energy-dk стіна+мережа 4000; 220help 6000; Lviv 4500
    ('ELECTRICAL', 'Генератор та АВР', 'Монтаж трифазного інвертора з підключенням до готової лінії', 'PIECE', 7500.00, NULL),
        -- Lviv 7500 (огляд uaportal: система $400–500)  [thin]
    ('ELECTRICAL', 'Генератор та АВР', 'Підключення акумуляторної батареї до інвертора', 'PIECE', 2000.00, NULL),
        -- energy-dk 2000; Lviv 2000  [thin]
    ('ELECTRICAL', 'Генератор та АВР', 'Ручний перемикач вводу I-0-II однофазний', 'PIECE', 1500.00, NULL),
        -- elektryky київ 1800; Lviv 1200  [thin]
    ('ELECTRICAL', 'Генератор та АВР', 'Ручний перемикач вводу I-0-II трифазний', 'PIECE', 1800.00, NULL),
        -- Lviv 1800  [one source]
    ('ELECTRICAL', 'Генератор та АВР', 'Монтаж сонячної панелі на скатний дах (кріплення, панель, підключення)', 'PIECE', 2000.00, 'За одну панель.'),
        -- energy-dk 2100; alterno 2000; дніпро $25≈1040; Lviv 900+1100=2000
    -- DEMOLITION
    ('DEMOLITION', 'Демонтаж стін', 'Демонтаж залізобетонної стіни до 80 мм', 'M2', 550.00, NULL),
        -- Lviv 550  [one source]
    ('DEMOLITION', 'Демонтаж стін', 'Демонтаж залізобетонної стіни до 160 мм', 'M2', 900.00, NULL),
        -- rating-remont 1200; житомир 980; ів-франківськ 1020; stroitel 800; київ 750; Lviv 750
    ('DEMOLITION', 'Демонтаж стін', 'Демонтаж залізобетонної стіни до 210 мм', 'M2', 1100.00, NULL),
        -- rating-remont 1200; Lviv 1000  [thin]
    ('DEMOLITION', 'Демонтаж стін', 'Демонтаж цегляної стіни в 1,5 цегли', 'M2', 270.00, NULL),
        -- rating-remont 180; житомир 160; ів-франківськ 153; stroitel 360; budver 468; Lviv 360  [CONFLICT: наша стіна в 1 цеглу 380]
    ('DEMOLITION', 'Демонтаж стін', 'Демонтаж цегляної стіни у 2 цегли', 'M2', 300.00, NULL),
        -- rating-remont 240; житомир 220; ів-франківськ 204; stroitel 360; budver 468; Lviv 480  [CONFLICT: наша стіна в 1 цеглу 380]
    ('DEMOLITION', 'Демонтаж стін', 'Демонтаж цегляної стіни у 2,5 цегли', 'M2', 590.00, NULL),
        -- stroitel 555; Lviv 630  [thin]
    ('DEMOLITION', 'Демонтаж підлоги', 'Демонтаж керамограніту', 'M2', 160.00, NULL),
        -- budver 104; budver київ 154; rbk-alba 175; Lviv 165
    ('DEMOLITION', 'Демонтаж стін', 'Зняття старої шпаклівки', 'M2', 85.00, NULL),
        -- 70; київ 77; київ 100; budver 84; budver київ 110; львів 70; Lviv 120
    ('DEMOLITION', 'Демонтаж підлоги', 'Зняття ковроліну', 'M2', 70.00, NULL),
        -- rating-remont 30; stroitel 80; київ 55; київ 90; rbk-alba 245; Lviv 60
    ('DEMOLITION', 'Демонтаж стін', 'Зняття штукатурки з армуванням', 'M2', 190.00, NULL),
        -- Lviv 165 (у Львові звичайна 145, у нас звичайна 170)  [one source; масштаб до нашої]
    ('DEMOLITION', 'Демонтаж стін', 'Демонтаж облицювання стін з ГКЛ на клею', 'M2', 150.00, NULL),
        -- Lviv 150  [one source]
    ('DEMOLITION', 'Демонтаж столярки', 'Демонтаж віконного блоку', 'PIECE', 310.00, NULL),
        -- market-vikna легкий 250; Lviv 370  [thin]
    ('DEMOLITION', 'Демонтаж сантехніки', 'Демонтаж чавунної ванни', 'PIECE', 640.00, NULL),
        -- Lviv 640  [one source]
    -- METAL
    ('METAL', 'Зварювальні та монтажні роботи', 'Зварювання сталевої труби Ду15–20 (за стик)', 'POINT', 230.00, NULL),
        -- огляд 225; дніпро 100; київ 450; київ фірма 1000; Lviv 222
    ('METAL', 'Зварювальні та монтажні роботи', 'Зварювання сталевої труби Ду25–32 (за стик)', 'POINT', 380.00, NULL),
        -- огляд 225; київ 450; київ фірма 1000; Lviv 300
    ('METAL', 'Зварювальні та монтажні роботи', 'Зварювання сталевої труби Ду40–50 (за стик)', 'POINT', 450.00, NULL),
        -- огляд 450; київ 450; київ фірма 1000; Lviv 395
    ('METAL', 'Зварювальні та монтажні роботи', 'Зварювання металу товщиною до 6 мм', 'LINEAR_METER', 480.00, NULL),
        -- огляд MMA 100; sbk 1100; 2–5 грн/см≈350; Lviv 5–7 грн/см≈600
    ('METAL', 'Зварювальні та монтажні роботи', 'Зварювання металу товщиною 7–14 мм', 'LINEAR_METER', 1100.00, NULL),
        -- Lviv 9–14 грн/см≈1100  [one source]
    ('METAL', 'Зварювальні та монтажні роботи', 'Зварювання металу товщиною 15–20 мм', 'LINEAR_METER', 1800.00, NULL),
        -- Lviv 18 грн/см=1800  [one source]
    ('METAL', 'Зварювальні та монтажні роботи', 'Заміна радіатора зі зварюванням', 'PIECE', 3000.00, NULL),
        -- домова служба львів 3500; київ 3000; Lviv 1750
    -- FLOORING
    ('FLOORING', 'Підлога', 'Укладання кварцвінілу на самоклеючій основі', 'M2', 250.00, NULL),
        -- budver 374; rabotniki 326; київ 250; київ замкова 150; pidloga 120; київ 225; Lviv 280
    ('FLOORING', 'Плінтус', 'Покриття плінтуса лаком', 'LINEAR_METER', 110.00, NULL);
        -- kabanchik київ 130; kabanchik ів-франківськ 70; rabotniki фарбування 123; Lviv 100

-- Positions other trades already ship, shared into the new trades at their current price (V220).
-- «Демонтаж віконного блоку» is new above and read from _pos; the rest from the live library.
CREATE TEMP TABLE _share (from_trade varchar(50), name varchar(255), to_trade varchar(50),
                          category varchar(100)) ON COMMIT DROP;
INSERT INTO _share VALUES
    ('DEMOLITION', 'Демонтаж міжкімнатних дверей з коробкою', 'DOORS_WINDOWS', 'Демонтаж'),
    ('DEMOLITION', 'Демонтаж вхідних дверей',                 'DOORS_WINDOWS', 'Демонтаж'),
    ('DEMOLITION', 'Демонтаж наличників і дверних укосів',    'DOORS_WINDOWS', 'Демонтаж'),
    ('DEMOLITION', 'Демонтаж вікна балконного блоку',         'DOORS_WINDOWS', 'Демонтаж'),
    ('DEMOLITION', 'Демонтаж підвіконня',                     'DOORS_WINDOWS', 'Демонтаж'),
    ('DEMOLITION', 'Демонтаж віконного блоку',                'DOORS_WINDOWS', 'Демонтаж'),
    ('BUILDER',    'Монтаж відливів на вікна',                'DOORS_WINDOWS', 'Вікна'),
    ('ELECTRICAL', 'Прокладання окремої лінії під кондиціонер з підключенням', 'CLIMATE', 'Кондиціонери'),
    ('PLUMBING',   'Монтаж вентиляції',                       'CLIMATE',       'Вентиляція'),
    ('PLUMBING',   'Монтаж вентиляційної решітки',            'CLIMATE',       'Вентиляція');

INSERT INTO _pos
SELECT s.to_trade, s.category, s.name, src.unit, src.price, src.description
  FROM _share s
  JOIN LATERAL (
        SELECT x.unit, x.price, x.description
          FROM (SELECT ct.unit, ct.suggested_price AS price, ct.description
                  FROM catalog_templates ct
                 WHERE ct.trade = s.from_trade AND ct.type = 'WORK' AND ct.name = s.name
                UNION ALL
                SELECT p.unit, p.price, p.description
                  FROM _pos p
                 WHERE p.trade = s.from_trade AND p.name = s.name) x
         LIMIT 1) src ON true;

-- -------------------------------------------------------------------------------------------------
-- 1. Skip, with a WARNING, anything the live library already sells in that trade.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_bad text;
BEGIN
    SELECT string_agg(p.trade || ': ' || p.name, '; ') INTO v_bad FROM _pos p
     WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                    WHERE ct.trade = p.trade AND ct.type = 'WORK' AND ct.unit = p.unit
                      AND lower(btrim(ct.name)) = lower(btrim(p.name)));
    IF v_bad IS NOT NULL THEN
        RAISE WARNING 'V224: position(s) already in their trade, skipped: %', v_bad;
        DELETE FROM _pos p
         WHERE EXISTS (SELECT 1 FROM catalog_templates ct
                        WHERE ct.trade = p.trade AND ct.type = 'WORK' AND ct.unit = p.unit
                          AND lower(btrim(ct.name)) = lower(btrim(p.name)));
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- 2. New positions under ONE new version; nothing pushed, `last_synced_catalog_version` untouched.
-- -------------------------------------------------------------------------------------------------
INSERT INTO catalog_templates (id, trade, category, name, type, unit, suggested_price, description,
                               added_in_version)
SELECT gen_random_uuid(), p.trade, p.category, p.name, 'WORK', p.unit, p.price, p.description,
       v.version
  FROM _pos p
 CROSS JOIN (SELECT COALESCE(MAX(added_in_version), 0) + 1 AS version FROM catalog_templates) v;

-- -------------------------------------------------------------------------------------------------
-- 3. RE-RANK, exactly as V216 did, with DOORS_WINDOWS and CLIMATE after METAL.
-- -------------------------------------------------------------------------------------------------
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               ORDER BY
                   array_position(ARRAY['ELECTRICAL','PLUMBING','TILING','BUILDER','PAINTER','DRYWALL','FLOORING','DEMOLITION','METAL','DOORS_WINDOWS','CLIMATE','GENERAL','OTHER'],
                                  trade::text),
                   CASE
                       WHEN trade = 'DRYWALL' THEN
                           CASE category
                               WHEN 'Підготовка та захист'        THEN 1
                               WHEN 'Каркас і обшивка'            THEN 2
                               WHEN 'Звукоізоляція та утеплення'  THEN 3
                               WHEN 'Оздоблення під фарбування'   THEN 4
                               WHEN 'Надбавки'                    THEN 8
                               ELSE 5
                           END
                       WHEN category = 'Демонтаж' OR category LIKE 'Демонтаж %' THEN 1
                       WHEN category IN ('Підготовка', 'Підготовка та захист',
                                         'Підготовчі роботи', 'Земляні') THEN 2
                       WHEN category IN ('Надбавки', 'Організаційні послуги', 'Сміття') THEN 8
                       WHEN category = 'Інше' THEN 9
                       WHEN category IS NULL THEN 10
                       ELSE 5
                   END,
                   category,
                   lower(name),
                   id) AS position
      FROM catalog_templates
)
UPDATE catalog_templates ct SET sort_order = ranked.position
  FROM ranked WHERE ct.id = ranked.id;

WITH item_key AS (
    SELECT ci.id,
           ci.owner_id,
           ci.name,
           COALESCE(
               (SELECT ct.sort_order FROM catalog_templates ct
                 WHERE ct.trade = ci.trade
                   AND lower(trim(ct.name)) = lower(trim(ci.name))
                   AND ct.type = ci.type AND ct.unit = ci.unit
                 LIMIT 1),
               (SELECT min(ct.sort_order) FROM catalog_templates ct
                 WHERE ct.trade = ci.trade
                   AND ct.category IS NOT DISTINCT FROM ci.category),
               1000000) AS rank_key
      FROM catalog_items ci
),
ordered AS (
    SELECT id, ROW_NUMBER() OVER (
               PARTITION BY owner_id
               ORDER BY rank_key, lower(name), id) - 1 AS position
      FROM item_key
)
UPDATE catalog_items ci SET sort_order = ordered.position
  FROM ordered WHERE ci.id = ordered.id;

-- -------------------------------------------------------------------------------------------------
-- 4. Self-check, about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_n int;
    v_version int := (SELECT MAX(added_in_version) FROM catalog_templates);
BEGIN
    SELECT count(*) INTO v_n FROM catalog_templates ct JOIN _pos p
        ON ct.trade = p.trade AND ct.name = p.name AND ct.unit = p.unit
       AND ct.suggested_price = p.price AND ct.added_in_version = v_version;
    IF v_n <> (SELECT count(*) FROM _pos) THEN
        RAISE EXCEPTION 'V224: % of % new position(s) landed under version %',
            v_n, (SELECT count(*) FROM _pos), v_version;
    END IF;
    IF (SELECT count(*) FROM catalog_templates WHERE trade IN ('DOORS_WINDOWS', 'CLIMATE')) = 0 THEN
        RAISE EXCEPTION 'V224: the two new trades carry no positions';
    END IF;
    RAISE NOTICE 'V224: % new position(s) under catalog version %', v_n, v_version;
END $$;
