-- Collapse the positions that exist twice in the DEFAULT catalog.
--
-- The tetris import (V50) could not see the older rows it duplicated: those had been written with
-- punctuation and connecting words stripped, so a punctuation-insensitive comparison still saw two
-- different strings. The result is one work sold under two names — and, because a default template
-- references a position BY NAME, the big tetris bundles and the small older bundles ended up
-- pointing at different rows for the same job. A master got both in their catalog and could not
-- tell which one the estimate would price.
--
-- Two passes. The first takes only groups a string test can prove: identical apart from
-- punctuation, a dimension separator, word order, or the spelling of one word. The second takes
-- groups where the older row is the same work with its punctuation and connecting words stripped
-- («Монтаж котельної котел бойлер насоси крани фільтра» is «Монтаж котельної (котел, бойлер,
-- насоси, крани, фільтра) - від»), which no string rule can decide but reading them can.
--
-- Pairs that differ by a REAL word are not duplicates and are left alone: «Монтаж» vs «Демонтаж»
-- риштування, одно- vs двоскатного даху, «від 50м.п.» vs «до 50м.п.» price tiers, дротового vs
-- бездротового, three- vs four-sided shelves, пластиковий vs чавунний стояк.
--
-- Three groups are left for the owner because the two rows carry different UNITS, which means they
-- may not be one position at all: «Армування кладки» (M2 50 vs LINEAR_METER 90), «Щебінь гранітний
-- фракція 5-20» (M3 vs T — crushed stone is legitimately sold by volume OR weight) and «Укладання
-- плитки на слой більше 1см» (M2 825 vs PERCENT 20, a rate vs a surcharge).
--
-- Surviving price: the newer (tetris) figure, as agreed with the owner. Surviving name: the newer
-- wording, except where tetris misspells what the older row got right.
--
-- Template items are repointed at the surviving name in the same statement, so every bundle —
-- default or master-saved — resolves to one catalog row and gets a price.

-- BUILDER: Гідроізоляція покрівлі мастика і євроруберойд — tetris price, older spelling
UPDATE catalog_templates SET suggested_price = 300.00
 WHERE trade = 'BUILDER' AND lower(name) = 'гідроізоляція покрівлі мастика і євроруберойд';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'гідроізоляція покрівлі мастика і єврорубероид';
UPDATE estimate_template_items SET name = 'Гідроізоляція покрівлі мастика і євроруберойд', unit = 'M2'
 WHERE lower(name) IN ('гідроізоляція покрівлі мастика і євроруберойд', 'гідроізоляція покрівлі мастика і єврорубероид') AND name <> 'Гідроізоляція покрівлі мастика і євроруберойд';

-- BUILDER: Прокладка комунікацій — tetris price, older spelling
UPDATE catalog_templates SET suggested_price = 400.00
 WHERE trade = 'BUILDER' AND lower(name) = 'прокладка комунікацій';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'прокладка коммунікацій';
UPDATE estimate_template_items SET name = 'Прокладка комунікацій', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('прокладка комунікацій', 'прокладка коммунікацій') AND name <> 'Прокладка комунікацій';

-- BUILDER: Установка закладних під комунікації — tetris price, older spelling
UPDATE catalog_templates SET suggested_price = 400.00
 WHERE trade = 'BUILDER' AND lower(name) = 'установка закладних під комунікації';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'установка закладних під коммунікації';
UPDATE estimate_template_items SET name = 'Установка закладних під комунікації', unit = 'PIECE'
 WHERE lower(name) IN ('установка закладних під комунікації', 'установка закладних під коммунікації') AND name <> 'Установка закладних під комунікації';

-- PLUMBING: Врізка в стояк водопроводу — same price, older spelling
UPDATE catalog_templates SET suggested_price = 1500.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'врізка в стояк водопроводу';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'врізка в стояк водопровода';
UPDATE estimate_template_items SET name = 'Врізка в стояк водопроводу', unit = 'PIECE'
 WHERE lower(name) IN ('врізка в стояк водопроводу', 'врізка в стояк водопровода') AND name <> 'Врізка в стояк водопроводу';

-- PLUMBING: Прокладка труб каналізації — tetris price, older grammar
UPDATE catalog_templates SET suggested_price = 220.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'прокладка труб каналізації';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'прокладка труб (каналізація)';
UPDATE estimate_template_items SET name = 'Прокладка труб каналізації', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('прокладка труб каналізації', 'прокладка труб (каналізація)') AND name <> 'Прокладка труб каналізації';

-- PLUMBING: Установка ванни простої — tetris price, older spelling
UPDATE catalog_templates SET suggested_price = 3000.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'установка ванни простої';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'установки ванни простої';
UPDATE estimate_template_items SET name = 'Установка ванни простої', unit = 'PIECE'
 WHERE lower(name) IN ('установка ванни простої', 'установки ванни простої') AND name <> 'Установка ванни простої';

-- FLOORING: Монтаж алюмінієвого плінтуса — newer of two OLD rows; no tetris row exists
UPDATE catalog_templates SET suggested_price = 312.00
 WHERE trade = 'FLOORING' AND lower(name) = 'монтаж алюмінієвого плінтуса';
DELETE FROM catalog_templates WHERE trade = 'FLOORING' AND lower(name) = 'монтаж плінтуса алюмінієвого';
UPDATE estimate_template_items SET name = 'Монтаж алюмінієвого плінтуса', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('монтаж алюмінієвого плінтуса', 'монтаж плінтуса алюмінієвого') AND name <> 'Монтаж алюмінієвого плінтуса';

-- BUILDER: Кладка стіни з керамоблока 2НФ 25х12 — tetris price
UPDATE catalog_templates SET suggested_price = 3500.00
 WHERE trade = 'BUILDER' AND lower(name) = 'кладка стіни з керамоблока 2нф 25х12';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'кладка стіни з керамоблока 2нф 25*12';
UPDATE estimate_template_items SET name = 'Кладка стіни з керамоблока 2НФ 25х12', unit = 'M3'
 WHERE lower(name) IN ('кладка стіни з керамоблока 2нф 25х12', 'кладка стіни з керамоблока 2нф 25*12') AND name <> 'Кладка стіни з керамоблока 2НФ 25х12';

-- BUILDER: Кладка стіни з керамоблока 38х60х20 — tetris price
UPDATE catalog_templates SET suggested_price = 3000.00
 WHERE trade = 'BUILDER' AND lower(name) = 'кладка стіни з керамоблока 38х60х20';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'кладка стіни з керамоблока 38*60*20';
UPDATE estimate_template_items SET name = 'Кладка стіни з керамоблока 38х60х20', unit = 'M3'
 WHERE lower(name) IN ('кладка стіни з керамоблока 38х60х20', 'кладка стіни з керамоблока 38*60*20') AND name <> 'Кладка стіни з керамоблока 38х60х20';

-- ELECTRICAL: Штроблення під електрокабель 20х20 мм (в бетоні) — tetris price
UPDATE catalog_templates SET name = 'Штроблення під електрокабель 20х20 мм (в бетоні)', suggested_price = 220.00
 WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 20*20 мм (в бетоні)';
DELETE FROM catalog_templates WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 20х20 в бетоні';
UPDATE estimate_template_items SET name = 'Штроблення під електрокабель 20х20 мм (в бетоні)', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('штроблення під електрокабель 20х20 мм (в бетоні)', 'штроблення під електрокабель 20*20 мм (в бетоні)', 'штроблення під електрокабель 20х20 в бетоні') AND name <> 'Штроблення під електрокабель 20х20 мм (в бетоні)';

-- ELECTRICAL: Штроблення під електрокабель 20х20 мм (в цеглі) — tetris price (LOWER than the old 140)
UPDATE catalog_templates SET name = 'Штроблення під електрокабель 20х20 мм (в цеглі)', suggested_price = 130.00
 WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 20*20 мм (в цеглі)';
DELETE FROM catalog_templates WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 20х20 в цеглі';
UPDATE estimate_template_items SET name = 'Штроблення під електрокабель 20х20 мм (в цеглі)', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('штроблення під електрокабель 20х20 мм (в цеглі)', 'штроблення під електрокабель 20*20 мм (в цеглі)', 'штроблення під електрокабель 20х20 в цеглі') AND name <> 'Штроблення під електрокабель 20х20 мм (в цеглі)';

-- ELECTRICAL: Штроблення під електрокабель 20х40 мм (в цеглі) — tetris price
UPDATE catalog_templates SET name = 'Штроблення під електрокабель 20х40 мм (в цеглі)', suggested_price = 150.00
 WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 20*40 мм (в цеглі)';
DELETE FROM catalog_templates WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 20х40 в цеглі';
UPDATE estimate_template_items SET name = 'Штроблення під електрокабель 20х40 мм (в цеглі)', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('штроблення під електрокабель 20х40 мм (в цеглі)', 'штроблення під електрокабель 20*40 мм (в цеглі)', 'штроблення під електрокабель 20х40 в цеглі') AND name <> 'Штроблення під електрокабель 20х40 мм (в цеглі)';

-- ELECTRICAL: Штроблення під електрокабель 20х60 мм (в цеглі) — tetris price
UPDATE catalog_templates SET name = 'Штроблення під електрокабель 20х60 мм (в цеглі)', suggested_price = 170.00
 WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 20*60 мм (в цеглі)';
DELETE FROM catalog_templates WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 20х60 в цеглі';
UPDATE estimate_template_items SET name = 'Штроблення під електрокабель 20х60 мм (в цеглі)', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('штроблення під електрокабель 20х60 мм (в цеглі)', 'штроблення під електрокабель 20*60 мм (в цеглі)', 'штроблення під електрокабель 20х60 в цеглі') AND name <> 'Штроблення під електрокабель 20х60 мм (в цеглі)';

-- ELECTRICAL: Штроблення під електрокабель 40х20 мм (в бетоні) — tetris price
UPDATE catalog_templates SET name = 'Штроблення під електрокабель 40х20 мм (в бетоні)', suggested_price = 250.00
 WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 40*20 мм (в бетоні)';
DELETE FROM catalog_templates WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 40х20 в бетоні';
UPDATE estimate_template_items SET name = 'Штроблення під електрокабель 40х20 мм (в бетоні)', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('штроблення під електрокабель 40х20 мм (в бетоні)', 'штроблення під електрокабель 40*20 мм (в бетоні)', 'штроблення під електрокабель 40х20 в бетоні') AND name <> 'Штроблення під електрокабель 40х20 мм (в бетоні)';

-- ELECTRICAL: Штроблення під електрокабель 60х20 мм (в бетоні) — tetris price
UPDATE catalog_templates SET name = 'Штроблення під електрокабель 60х20 мм (в бетоні)', suggested_price = 300.00
 WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 60*20 мм (в бетоні)';
DELETE FROM catalog_templates WHERE trade = 'ELECTRICAL' AND lower(name) = 'штроблення під електрокабель 60х20 в бетоні';
UPDATE estimate_template_items SET name = 'Штроблення під електрокабель 60х20 мм (в бетоні)', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('штроблення під електрокабель 60х20 мм (в бетоні)', 'штроблення під електрокабель 60*20 мм (в бетоні)', 'штроблення під електрокабель 60х20 в бетоні') AND name <> 'Штроблення під електрокабель 60х20 мм (в бетоні)';

-- BUILDER: Установка дерев'яного або металевого стовпа паркану — tetris price
UPDATE catalog_templates SET suggested_price = 350.00
 WHERE trade = 'BUILDER' AND lower(name) = 'установка дерев''яного або металевого стовпа паркану';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'установка дерев''яного металевого стовпа паркану';
UPDATE estimate_template_items SET name = 'Установка дерев''яного або металевого стовпа паркану', unit = 'PIECE'
 WHERE lower(name) IN ('установка дерев''яного або металевого стовпа паркану', 'установка дерев''яного металевого стовпа паркану') AND name <> 'Установка дерев''яного або металевого стовпа паркану';

-- BUILDER: Копка траншей глибиною 0,5 м і шириною 35см — tetris price
UPDATE catalog_templates SET suggested_price = 400.00
 WHERE trade = 'BUILDER' AND lower(name) = 'копка траншей глибиною 0,5 м і шириною 35см';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'копка траншей глибиною 0.5м шириною 35см';
UPDATE estimate_template_items SET name = 'Копка траншей глибиною 0,5 м і шириною 35см', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('копка траншей глибиною 0,5 м і шириною 35см', 'копка траншей глибиною 0.5м шириною 35см') AND name <> 'Копка траншей глибиною 0,5 м і шириною 35см';

-- BUILDER: Монтаж шлакоблоків/євроблоків для стовбчика — tetris price (1350 -> 400)
UPDATE catalog_templates SET suggested_price = 400.00
 WHERE trade = 'BUILDER' AND lower(name) = 'монтаж шлакоблоків/євроблоків для стовбчика';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'монтаж шлакоблоків для стовбчика';
UPDATE estimate_template_items SET name = 'Монтаж шлакоблоків/євроблоків для стовбчика', unit = 'PIECE'
 WHERE lower(name) IN ('монтаж шлакоблоків/євроблоків для стовбчика', 'монтаж шлакоблоків для стовбчика') AND name <> 'Монтаж шлакоблоків/євроблоків для стовбчика';

-- BUILDER: Планування ділянки і нівелювання - від — tetris price (1350 -> 5000)
UPDATE catalog_templates SET suggested_price = 5000.00
 WHERE trade = 'BUILDER' AND lower(name) = 'планування ділянки і нівелювання - від';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'планування ділянки і нівелювання';
UPDATE estimate_template_items SET name = 'Планування ділянки і нівелювання - від', unit = 'PIECE'
 WHERE lower(name) IN ('планування ділянки і нівелювання - від', 'планування ділянки і нівелювання') AND name <> 'Планування ділянки і нівелювання - від';

-- BUILDER: Укладання бруківки типу "Старе місто" на гарцовку — both 550
UPDATE catalog_templates SET suggested_price = 550.00
 WHERE trade = 'BUILDER' AND lower(name) = 'укладання бруківки типу "старе місто" на гарцовку';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'укладання бруківки старе місто на гарцовку';
UPDATE estimate_template_items SET name = 'Укладання бруківки типу "Старе місто" на гарцовку', unit = 'M2'
 WHERE lower(name) IN ('укладання бруківки типу "старе місто" на гарцовку', 'укладання бруківки старе місто на гарцовку') AND name <> 'Укладання бруківки типу "Старе місто" на гарцовку';

-- BUILDER: Кладка стін з газоблоку або піноблоку 200 мм — both 3000
UPDATE catalog_templates SET suggested_price = 3000.00
 WHERE trade = 'BUILDER' AND lower(name) = 'кладка стін з газоблоку або піноблоку 200 мм';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'кладка стін з газоблоку піноблоку 200 мм';
UPDATE estimate_template_items SET name = 'Кладка стін з газоблоку або піноблоку 200 мм', unit = 'M3'
 WHERE lower(name) IN ('кладка стін з газоблоку або піноблоку 200 мм', 'кладка стін з газоблоку піноблоку 200 мм') AND name <> 'Кладка стін з газоблоку або піноблоку 200 мм';

-- BUILDER: Кладка стін з газоблоку або піноблоку 300 мм — both 2300
UPDATE catalog_templates SET suggested_price = 2300.00
 WHERE trade = 'BUILDER' AND lower(name) = 'кладка стін з газоблоку або піноблоку 300 мм';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'кладка стін з газоблоку піноблоку 300 мм';
UPDATE estimate_template_items SET name = 'Кладка стін з газоблоку або піноблоку 300 мм', unit = 'M3'
 WHERE lower(name) IN ('кладка стін з газоблоку або піноблоку 300 мм', 'кладка стін з газоблоку піноблоку 300 мм') AND name <> 'Кладка стін з газоблоку або піноблоку 300 мм';

-- BUILDER: Підшива даху сайдінгом, профнастилом — tetris price, older spelling (профнасілом -> профнастилом)
UPDATE catalog_templates SET name = 'Підшива даху сайдінгом, профнастилом', suggested_price = 600.00
 WHERE trade = 'BUILDER' AND lower(name) = 'підшива даху сайдінгом профнастилом';
DELETE FROM catalog_templates WHERE trade = 'BUILDER' AND lower(name) = 'підшива даху сайдінгом, профнасілом';
UPDATE estimate_template_items SET name = 'Підшива даху сайдінгом, профнастилом', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('підшива даху сайдінгом, профнастилом', 'підшива даху сайдінгом профнастилом', 'підшива даху сайдінгом, профнасілом') AND name <> 'Підшива даху сайдінгом, профнастилом';

-- ELECTRICAL: Установка тимчасового освітлення та розеток (на час ремонту) — tetris price
UPDATE catalog_templates SET suggested_price = 100.00
 WHERE trade = 'ELECTRICAL' AND lower(name) = 'установка тимчасового освітлення та розеток (на час ремонту)';
DELETE FROM catalog_templates WHERE trade = 'ELECTRICAL' AND lower(name) = 'установка тимчасового освітлення розеток на час ремонту';
UPDATE estimate_template_items SET name = 'Установка тимчасового освітлення та розеток (на час ремонту)', unit = 'PIECE'
 WHERE lower(name) IN ('установка тимчасового освітлення та розеток (на час ремонту)', 'установка тимчасового освітлення розеток на час ремонту') AND name <> 'Установка тимчасового освітлення та розеток (на час ремонту)';

-- ELECTRICAL: Установка автомата перекидного двополюсного — tetris price, older spelling
UPDATE catalog_templates SET suggested_price = 600.00
 WHERE trade = 'ELECTRICAL' AND lower(name) = 'установка автомата перекидного двополюсного';
DELETE FROM catalog_templates WHERE trade = 'ELECTRICAL' AND lower(name) = 'установка автомата перекидного двухполюсного';
UPDATE estimate_template_items SET name = 'Установка автомата перекидного двополюсного', unit = 'PIECE'
 WHERE lower(name) IN ('установка автомата перекидного двополюсного', 'установка автомата перекидного двухполюсного') AND name <> 'Установка автомата перекидного двополюсного';

-- ELECTRICAL: Монтаж люка-ревізії під ТВ, інтернет — tetris price, older grammar
UPDATE catalog_templates SET name = 'Монтаж люка-ревізії під ТВ, інтернет', suggested_price = 450.00
 WHERE trade = 'ELECTRICAL' AND lower(name) = 'монтаж люка-ревізії під тв інтернет';
DELETE FROM catalog_templates WHERE trade = 'ELECTRICAL' AND lower(name) = 'монтаж люка-ревізія під тв, інтернет';
UPDATE estimate_template_items SET name = 'Монтаж люка-ревізії під ТВ, інтернет', unit = 'PIECE'
 WHERE lower(name) IN ('монтаж люка-ревізії під тв, інтернет', 'монтаж люка-ревізії під тв інтернет', 'монтаж люка-ревізія під тв, інтернет') AND name <> 'Монтаж люка-ревізії під ТВ, інтернет';

-- ELECTRICAL: Установка та підключення електроплити, варочної поверхні — tetris price
UPDATE catalog_templates SET suggested_price = 1500.00
 WHERE trade = 'ELECTRICAL' AND lower(name) = 'установка та підключення електроплити, варочної поверхні';
DELETE FROM catalog_templates WHERE trade = 'ELECTRICAL' AND lower(name) = 'установка та підключення електроплити варочної';
UPDATE estimate_template_items SET name = 'Установка та підключення електроплити, варочної поверхні', unit = 'PIECE'
 WHERE lower(name) IN ('установка та підключення електроплити, варочної поверхні', 'установка та підключення електроплити варочної') AND name <> 'Установка та підключення електроплити, варочної поверхні';

-- FLOORING: Монтаж та виготовлення ніші під плінтус прихованого монтажу — tetris price
UPDATE catalog_templates SET suggested_price = 300.00
 WHERE trade = 'FLOORING' AND lower(name) = 'монтаж та виготовлення ніші під плінтус прихованого монтажу';
DELETE FROM catalog_templates WHERE trade = 'FLOORING' AND lower(name) = 'монтаж та виготовлення ніші під плінтус прихованого';
UPDATE estimate_template_items SET name = 'Монтаж та виготовлення ніші під плінтус прихованого монтажу', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('монтаж та виготовлення ніші під плінтус прихованого монтажу', 'монтаж та виготовлення ніші під плінтус прихованого') AND name <> 'Монтаж та виготовлення ніші під плінтус прихованого монтажу';

-- FLOORING: Підготовка поверхні (очищення і т.п.) — tetris price (246 -> 60)
UPDATE catalog_templates SET suggested_price = 60.00
 WHERE trade = 'FLOORING' AND lower(name) = 'підготовка поверхні (очищення і т.п.)';
DELETE FROM catalog_templates WHERE trade = 'FLOORING' AND lower(name) = 'підготовка поверхні очищення';
UPDATE estimate_template_items SET name = 'Підготовка поверхні (очищення і т.п.)', unit = 'M2'
 WHERE lower(name) IN ('підготовка поверхні (очищення і т.п.)', 'підготовка поверхні очищення') AND name <> 'Підготовка поверхні (очищення і т.п.)';

-- PAINTER: Установка перфорованих кутів на укоси, кути — tetris price
UPDATE catalog_templates SET suggested_price = 120.00
 WHERE trade = 'PAINTER' AND lower(name) = 'установка перфорованих кутів на укоси, кути';
DELETE FROM catalog_templates WHERE trade = 'PAINTER' AND lower(name) = 'установка перфорованих кутів на укоси';
UPDATE estimate_template_items SET name = 'Установка перфорованих кутів на укоси, кути', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('установка перфорованих кутів на укоси, кути', 'установка перфорованих кутів на укоси') AND name <> 'Установка перфорованих кутів на укоси, кути';

-- PAINTER: Фарбування труб (газової, опалення і ін.) — tetris price
UPDATE catalog_templates SET suggested_price = 220.00
 WHERE trade = 'PAINTER' AND lower(name) = 'фарбування труб (газової, опалення і ін.)';
DELETE FROM catalog_templates WHERE trade = 'PAINTER' AND lower(name) = 'фарбування труб газової опалення';
UPDATE estimate_template_items SET name = 'Фарбування труб (газової, опалення і ін.)', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('фарбування труб (газової, опалення і ін.)', 'фарбування труб газової опалення') AND name <> 'Фарбування труб (газової, опалення і ін.)';

-- PLUMBING: Установка ніжок під радіатор із нижнім підключенням — both 300
UPDATE catalog_templates SET suggested_price = 300.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'установка ніжок під радіатор із нижнім підключенням';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'установка ніжок під радіатор з нижнім підключенням';
UPDATE estimate_template_items SET name = 'Установка ніжок під радіатор із нижнім підключенням', unit = 'PIECE'
 WHERE lower(name) IN ('установка ніжок під радіатор із нижнім підключенням', 'установка ніжок під радіатор з нижнім підключенням') AND name <> 'Установка ніжок під радіатор із нижнім підключенням';

-- PLUMBING: Установка змішувача прихованого типу для душа (біде) — both 1500
UPDATE catalog_templates SET suggested_price = 1500.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'установка змішувача прихованого типу для душа (біде)';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'установка змішувача прихованого типу для душа';
UPDATE estimate_template_items SET name = 'Установка змішувача прихованого типу для душа (біде)', unit = 'PIECE'
 WHERE lower(name) IN ('установка змішувача прихованого типу для душа (біде)', 'установка змішувача прихованого типу для душа') AND name <> 'Установка змішувача прихованого типу для душа (біде)';

-- PLUMBING: Обв'язка котла (один котел - 4 точки) - від — tetris price
UPDATE catalog_templates SET suggested_price = 800.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'обв''язка котла (один котел - 4 точки) - від';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'обв''язка котла один котел 4 точки';
UPDATE estimate_template_items SET name = 'Обв''язка котла (один котел - 4 точки) - від', unit = 'POINT'
 WHERE lower(name) IN ('обв''язка котла (один котел - 4 точки) - від', 'обв''язка котла один котел 4 точки') AND name <> 'Обв''язка котла (один котел - 4 точки) - від';

-- PLUMBING: Установка ванни із гідромасажем — tetris price (1800 -> 7500)
UPDATE catalog_templates SET suggested_price = 7500.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'установка ванни із гідромасажем';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'установка ванни з гідромасажем';
UPDATE estimate_template_items SET name = 'Установка ванни із гідромасажем', unit = 'PIECE'
 WHERE lower(name) IN ('установка ванни із гідромасажем', 'установка ванни з гідромасажем') AND name <> 'Установка ванни із гідромасажем';

-- PLUMBING: Зняття і встановлення радіатора (під час ремонту - один раз) — tetris price (900 -> 450)
UPDATE catalog_templates SET suggested_price = 450.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'зняття і встановлення радіатора (під час ремонту - один раз)';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'зняття і встановлення радіатора під час ремонту';
UPDATE estimate_template_items SET name = 'Зняття і встановлення радіатора (під час ремонту - один раз)', unit = 'PIECE'
 WHERE lower(name) IN ('зняття і встановлення радіатора (під час ремонту - один раз)', 'зняття і встановлення радіатора під час ремонту') AND name <> 'Зняття і встановлення радіатора (під час ремонту - один раз)';

-- PLUMBING: Прокладання труб з ізоляцією — both 100
UPDATE catalog_templates SET suggested_price = 100.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'прокладання труб з ізоляцією';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'прокладання труб з ізоляцією (вода)';
UPDATE estimate_template_items SET name = 'Прокладання труб з ізоляцією', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('прокладання труб з ізоляцією', 'прокладання труб з ізоляцією (вода)') AND name <> 'Прокладання труб з ізоляцією';

-- PLUMBING: Установка люка-ревізії простого — tetris price, older grammar
UPDATE catalog_templates SET suggested_price = 400.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'установка люка-ревізії простого';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'установка люка-ревізія (простого)';
UPDATE estimate_template_items SET name = 'Установка люка-ревізії простого', unit = 'PIECE'
 WHERE lower(name) IN ('установка люка-ревізії простого', 'установка люка-ревізія (простого)') AND name <> 'Установка люка-ревізії простого';

-- PLUMBING: Штроблення в цеглі, газобетоні — tetris price
UPDATE catalog_templates SET suggested_price = 300.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'штроблення в цеглі, газобетоні';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'штроблення в цеглі газобетоні (вода)';
UPDATE estimate_template_items SET name = 'Штроблення в цеглі, газобетоні', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('штроблення в цеглі, газобетоні', 'штроблення в цеглі газобетоні (вода)') AND name <> 'Штроблення в цеглі, газобетоні';

-- PLUMBING: Прокладання труб на хомутах — tetris price (25 -> 180)
UPDATE catalog_templates SET suggested_price = 180.00
 WHERE trade = 'PLUMBING' AND lower(name) = 'прокладання труб на хомутах';
DELETE FROM catalog_templates WHERE trade = 'PLUMBING' AND lower(name) = 'прокладання труб на хомутах (вода)';
UPDATE estimate_template_items SET name = 'Прокладання труб на хомутах', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('прокладання труб на хомутах', 'прокладання труб на хомутах (вода)') AND name <> 'Прокладання труб на хомутах';

-- TILING: Виготовлення та монтаж кнопки інсталяції з плитки — tetris price, older grammar
UPDATE catalog_templates SET suggested_price = 9000.00
 WHERE trade = 'TILING' AND lower(name) = 'виготовлення та монтаж кнопки інсталяції з плитки';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'виготовлення та монтаж кнопка інсталяції з плитки';
UPDATE estimate_template_items SET name = 'Виготовлення та монтаж кнопки інсталяції з плитки', unit = 'PIECE'
 WHERE lower(name) IN ('виготовлення та монтаж кнопки інсталяції з плитки', 'виготовлення та монтаж кнопка інсталяції з плитки') AND name <> 'Виготовлення та монтаж кнопки інсталяції з плитки';

-- TILING: Порізка плитки під кутом в 45 градусів — both 500
UPDATE catalog_templates SET suggested_price = 500.00
 WHERE trade = 'TILING' AND lower(name) = 'порізка плитки під кутом в 45 градусів';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'порізка плитки під кутом 45 градусів';
UPDATE estimate_template_items SET name = 'Порізка плитки під кутом в 45 градусів', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('порізка плитки під кутом в 45 градусів', 'порізка плитки під кутом 45 градусів') AND name <> 'Порізка плитки під кутом в 45 градусів';

-- TILING: Піддон з плитки в рівень з підлогою (комплекс робіт) — tetris price (1984 placeholder)
UPDATE catalog_templates SET suggested_price = 10000.00
 WHERE trade = 'TILING' AND lower(name) = 'піддон з плитки в рівень з підлогою (комплекс робіт)';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'піддон з плитки в рівень з підлогою комплекс';
UPDATE estimate_template_items SET name = 'Піддон з плитки в рівень з підлогою (комплекс робіт)', unit = 'PIECE'
 WHERE lower(name) IN ('піддон з плитки в рівень з підлогою (комплекс робіт)', 'піддон з плитки в рівень з підлогою комплекс') AND name <> 'Піддон з плитки в рівень з підлогою (комплекс робіт)';

-- TILING: Піддон з плитки з бортиком (комплекс робіт) — tetris price (1984 placeholder)
UPDATE catalog_templates SET suggested_price = 16000.00
 WHERE trade = 'TILING' AND lower(name) = 'піддон з плитки з бортиком (комплекс робіт)';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'піддон з плитки з бортиком комплекс';
UPDATE estimate_template_items SET name = 'Піддон з плитки з бортиком (комплекс робіт)', unit = 'PIECE'
 WHERE lower(name) IN ('піддон з плитки з бортиком (комплекс робіт)', 'піддон з плитки з бортиком комплекс') AND name <> 'Піддон з плитки з бортиком (комплекс робіт)';

-- TILING: Укладка та порізка плитки на підступок прямі (без кута 45) — tetris price
UPDATE catalog_templates SET suggested_price = 1350.00
 WHERE trade = 'TILING' AND lower(name) = 'укладка та порізка плитки на підступок прямі (без кута 45)';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладка плитки на підступок прямі без кута 45';
UPDATE estimate_template_items SET name = 'Укладка та порізка плитки на підступок прямі (без кута 45)', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('укладка та порізка плитки на підступок прямі (без кута 45)', 'укладка плитки на підступок прямі без кута 45') AND name <> 'Укладка та порізка плитки на підступок прямі (без кута 45)';

-- TILING: Укладка та порізка плитки на сходи прямі (без кута 45) — tetris price
UPDATE catalog_templates SET suggested_price = 1350.00
 WHERE trade = 'TILING' AND lower(name) = 'укладка та порізка плитки на сходи прямі (без кута 45)';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладка плитки на сходи прямі без кута 45';
UPDATE estimate_template_items SET name = 'Укладка та порізка плитки на сходи прямі (без кута 45)', unit = 'LINEAR_METER'
 WHERE lower(name) IN ('укладка та порізка плитки на сходи прямі (без кута 45)', 'укладка плитки на сходи прямі без кута 45') AND name <> 'Укладка та порізка плитки на сходи прямі (без кута 45)';

-- TILING: Укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна) — tetris price
UPDATE catalog_templates SET suggested_price = 1200.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання декоративної плитки під цеглу камінь';
UPDATE estimate_template_items SET name = 'Укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)', unit = 'M2'
 WHERE lower(name) IN ('укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)', 'укладання декоративної плитки під цеглу камінь') AND name <> 'Укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)';

-- TILING: Укладання плитки по діагоналі (+ до ціни укладання) — tetris price
UPDATE catalog_templates SET suggested_price = 20.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки по діагоналі (+ до ціни укладання)';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки по діагоналі надбавка';
UPDATE estimate_template_items SET name = 'Укладання плитки по діагоналі (+ до ціни укладання)', unit = 'PERCENT'
 WHERE lower(name) IN ('укладання плитки по діагоналі (+ до ціни укладання)', 'укладання плитки по діагоналі надбавка') AND name <> 'Укладання плитки по діагоналі (+ до ціни укладання)';

-- TILING: Виготовлення поличок з плитки в три сторони (шириною від 30см) — tetris price (1984 placeholder)
UPDATE catalog_templates SET suggested_price = 3500.00
 WHERE trade = 'TILING' AND lower(name) = 'виготовлення поличок з плитки в три сторони (шириною від 30см)';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'виготовлення поличок з плитки три сторони від 30см';
UPDATE estimate_template_items SET name = 'Виготовлення поличок з плитки в три сторони (шириною від 30см)', unit = 'PIECE'
 WHERE lower(name) IN ('виготовлення поличок з плитки в три сторони (шириною від 30см)', 'виготовлення поличок з плитки три сторони від 30см') AND name <> 'Виготовлення поличок з плитки в три сторони (шириною від 30см)';

-- TILING: Виготовлення поличок з плитки в три сторони (шириною до 30см) — tetris price (1984 placeholder)
UPDATE catalog_templates SET suggested_price = 3000.00
 WHERE trade = 'TILING' AND lower(name) = 'виготовлення поличок з плитки в три сторони (шириною до 30см)';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'виготовлення поличок з плитки три сторони до 30см';
UPDATE estimate_template_items SET name = 'Виготовлення поличок з плитки в три сторони (шириною до 30см)', unit = 'PIECE'
 WHERE lower(name) IN ('виготовлення поличок з плитки в три сторони (шириною до 30см)', 'виготовлення поличок з плитки три сторони до 30см') AND name <> 'Виготовлення поличок з плитки в три сторони (шириною до 30см)';

-- TILING: Виготовлення поличок з плитки в чотири сторони (шириною від 30см) — tetris price (1984 placeholder)
UPDATE catalog_templates SET suggested_price = 4000.00
 WHERE trade = 'TILING' AND lower(name) = 'виготовлення поличок з плитки в чотири сторони (шириною від 30см)';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'виготовлення поличок з плитки чотири сторони від 30см';
UPDATE estimate_template_items SET name = 'Виготовлення поличок з плитки в чотири сторони (шириною від 30см)', unit = 'PIECE'
 WHERE lower(name) IN ('виготовлення поличок з плитки в чотири сторони (шириною від 30см)', 'виготовлення поличок з плитки чотири сторони від 30см') AND name <> 'Виготовлення поличок з плитки в чотири сторони (шириною від 30см)';

-- TILING: Виготовлення поличок з плитки в чотири сторони (шириною до 30см) — tetris price (1984 placeholder)
UPDATE catalog_templates SET suggested_price = 3500.00
 WHERE trade = 'TILING' AND lower(name) = 'виготовлення поличок з плитки в чотири сторони (шириною до 30см)';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'виготовлення поличок з плитки чотири сторони до 30см';
UPDATE estimate_template_items SET name = 'Виготовлення поличок з плитки в чотири сторони (шириною до 30см)', unit = 'PIECE'
 WHERE lower(name) IN ('виготовлення поличок з плитки в чотири сторони (шириною до 30см)', 'виготовлення поличок з плитки чотири сторони до 30см') AND name <> 'Виготовлення поличок з плитки в чотири сторони (шириною до 30см)';

-- TILING: Укладання плитки 1000х1000 — tetris price
UPDATE catalog_templates SET suggested_price = 1200.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1000х1000';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1000*1000';
UPDATE estimate_template_items SET name = 'Укладання плитки 1000х1000', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 1000х1000', 'укладання плитки 1000*1000') AND name <> 'Укладання плитки 1000х1000';

-- TILING: Укладання плитки 1200х1200 — tetris price
UPDATE catalog_templates SET suggested_price = 1350.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200х1200';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200*1200';
UPDATE estimate_template_items SET name = 'Укладання плитки 1200х1200', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 1200х1200', 'укладання плитки 1200*1200') AND name <> 'Укладання плитки 1200х1200';

-- TILING: Укладання плитки 1200х200 — tetris price
UPDATE catalog_templates SET suggested_price = 950.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200х200';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200*200';
UPDATE estimate_template_items SET name = 'Укладання плитки 1200х200', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 1200х200', 'укладання плитки 1200*200') AND name <> 'Укладання плитки 1200х200';

-- TILING: Укладання плитки 1200х200 ялинкою — tetris price
UPDATE catalog_templates SET suggested_price = 1500.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200х200 ялинкою';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200*200 ялинкою';
UPDATE estimate_template_items SET name = 'Укладання плитки 1200х200 ялинкою', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 1200х200 ялинкою', 'укладання плитки 1200*200 ялинкою') AND name <> 'Укладання плитки 1200х200 ялинкою';

-- TILING: Укладання плитки 1200х2500 — tetris price
UPDATE catalog_templates SET suggested_price = 5000.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200х2500';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200*2500';
UPDATE estimate_template_items SET name = 'Укладання плитки 1200х2500', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 1200х2500', 'укладання плитки 1200*2500') AND name <> 'Укладання плитки 1200х2500';

-- TILING: Укладання плитки 1200х3000 — tetris price
UPDATE catalog_templates SET suggested_price = 6000.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200х3000';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200*3000';
UPDATE estimate_template_items SET name = 'Укладання плитки 1200х3000', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 1200х3000', 'укладання плитки 1200*3000') AND name <> 'Укладання плитки 1200х3000';

-- TILING: Укладання плитки 1200х600 — tetris price
UPDATE catalog_templates SET suggested_price = 1100.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200х600';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1200*600';
UPDATE estimate_template_items SET name = 'Укладання плитки 1200х600', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 1200х600', 'укладання плитки 1200*600') AND name <> 'Укладання плитки 1200х600';

-- TILING: Укладання плитки 1500х1500 — tetris price
UPDATE catalog_templates SET suggested_price = 1500.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1500х1500';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1500*1500';
UPDATE estimate_template_items SET name = 'Укладання плитки 1500х1500', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 1500х1500', 'укладання плитки 1500*1500') AND name <> 'Укладання плитки 1500х1500';

-- TILING: Укладання плитки 1500х750 — tetris price
UPDATE catalog_templates SET suggested_price = 1200.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1500х750';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 1500*750';
UPDATE estimate_template_items SET name = 'Укладання плитки 1500х750', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 1500х750', 'укладання плитки 1500*750') AND name <> 'Укладання плитки 1500х750';

-- TILING: Укладання плитки 600х600 — tetris price
UPDATE catalog_templates SET suggested_price = 950.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 600х600';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 600*600';
UPDATE estimate_template_items SET name = 'Укладання плитки 600х600', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 600х600', 'укладання плитки 600*600') AND name <> 'Укладання плитки 600х600';

-- TILING: Укладання плитки 800х800 — tetris price
UPDATE catalog_templates SET suggested_price = 1100.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 800х800';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 800*800';
UPDATE estimate_template_items SET name = 'Укладання плитки 800х800', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 800х800', 'укладання плитки 800*800') AND name <> 'Укладання плитки 800х800';

-- TILING: Укладання плитки 900х900 — tetris price
UPDATE catalog_templates SET suggested_price = 1200.00
 WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 900х900';
DELETE FROM catalog_templates WHERE trade = 'TILING' AND lower(name) = 'укладання плитки 900*900';
UPDATE estimate_template_items SET name = 'Укладання плитки 900х900', unit = 'M2'
 WHERE lower(name) IN ('укладання плитки 900х900', 'укладання плитки 900*900') AND name <> 'Укладання плитки 900х900';

-- Two rows carry «*» as the dimension separator without a «х» twin to merge with. Left alone
-- they keep the catalog inconsistent: a master typing «50х250» in the search box would not find
-- «50*250». Renamed in both tables in one statement pair, so name matching stays intact.
UPDATE catalog_templates       SET name = replace(name, '*', 'х') WHERE name LIKE '%*%';
UPDATE estimate_template_items SET name = replace(name, '*', 'х') WHERE name LIKE '%*%';


-- What THIS migration is answerable for: no bundle may still point at a wording it removed.
DO $$
DECLARE stranded int; preexisting int;
BEGIN
  SELECT count(*) INTO stranded
  FROM estimate_template_items
  WHERE lower(trim(name)) IN (
         'виготовлення поличок з плитки три сторони від 30см',
         'виготовлення поличок з плитки три сторони до 30см',
         'виготовлення поличок з плитки чотири сторони від 30см',
         'виготовлення поличок з плитки чотири сторони до 30см',
         'виготовлення та монтаж кнопка інсталяції з плитки',
         'врізка в стояк водопровода',
         'гідроізоляція покрівлі мастика і єврорубероид',
         'зняття і встановлення радіатора під час ремонту',
         'кладка стін з газоблоку піноблоку 200 мм',
         'кладка стін з газоблоку піноблоку 300 мм',
         'кладка стіни з керамоблока 2нф 25*12',
         'кладка стіни з керамоблока 38*60*20',
         'копка траншей глибиною 0.5м шириною 35см',
         'монтаж люка-ревізія під тв, інтернет',
         'монтаж люка-ревізії під тв інтернет',
         'монтаж плінтуса алюмінієвого',
         'монтаж та виготовлення ніші під плінтус прихованого',
         'монтаж шлакоблоків для стовбчика',
         'обв''язка котла один котел 4 точки',
         'планування ділянки і нівелювання',
         'порізка плитки під кутом 45 градусів',
         'прокладання труб з ізоляцією (вода)',
         'прокладання труб на хомутах (вода)',
         'прокладка коммунікацій',
         'прокладка труб (каналізація)',
         'підготовка поверхні очищення',
         'піддон з плитки в рівень з підлогою комплекс',
         'піддон з плитки з бортиком комплекс',
         'підшива даху сайдінгом профнастилом',
         'підшива даху сайдінгом, профнасілом',
         'укладання бруківки старе місто на гарцовку',
         'укладання декоративної плитки під цеглу камінь',
         'укладання плитки 1000*1000',
         'укладання плитки 1200*1200',
         'укладання плитки 1200*200',
         'укладання плитки 1200*200 ялинкою',
         'укладання плитки 1200*2500',
         'укладання плитки 1200*3000',
         'укладання плитки 1200*600',
         'укладання плитки 1500*1500',
         'укладання плитки 1500*750',
         'укладання плитки 600*600',
         'укладання плитки 800*800',
         'укладання плитки 900*900',
         'укладання плитки по діагоналі надбавка',
         'укладка плитки на підступок прямі без кута 45',
         'укладка плитки на сходи прямі без кута 45',
         'установка автомата перекидного двухполюсного',
         'установка ванни з гідромасажем',
         'установка дерев''яного металевого стовпа паркану',
         'установка закладних під коммунікації',
         'установка змішувача прихованого типу для душа',
         'установка люка-ревізія (простого)',
         'установка ніжок під радіатор з нижнім підключенням',
         'установка перфорованих кутів на укоси',
         'установка та підключення електроплити варочної',
         'установка тимчасового освітлення розеток на час ремонту',
         'установки ванни простої',
         'фарбування труб газової опалення',
         'штроблення в цеглі газобетоні (вода)',
         'штроблення під електрокабель 20*20 мм (в бетоні)',
         'штроблення під електрокабель 20*20 мм (в цеглі)',
         'штроблення під електрокабель 20*40 мм (в цеглі)',
         'штроблення під електрокабель 20*60 мм (в цеглі)',
         'штроблення під електрокабель 20х20 в бетоні',
         'штроблення під електрокабель 20х20 в цеглі',
         'штроблення під електрокабель 20х40 в цеглі',
         'штроблення під електрокабель 20х60 в цеглі',
         'штроблення під електрокабель 40*20 мм (в бетоні)',
         'штроблення під електрокабель 40х20 в бетоні',
         'штроблення під електрокабель 60*20 мм (в бетоні)',
         'штроблення під електрокабель 60х20 в бетоні');
  IF stranded > 0 THEN
    RAISE EXCEPTION 'дедуп лишив % позицій шаблонів на видалених назвах', stranded;
  END IF;

  -- A bundle position with no catalog row in its trade is NOT necessarily this migration's doing
  -- and must not block a deploy: an admin can rename or delete a default catalog position at any
  -- time (AdminCatalogTemplateService.update/delete) and nothing re-points the bundles that named
  -- it. The first version of this check raised instead of warning and stopped the app from
  -- starting over two positions it had never touched.
  SELECT count(*) INTO preexisting
  FROM estimate_template_items i
  JOIN estimate_templates et ON et.id = i.template_id AND et.is_default
  WHERE et.trade IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM catalog_templates c
                    WHERE lower(c.name) = lower(i.name) AND c.trade = et.trade);
  IF preexisting > 0 THEN
    RAISE WARNING '% позицій шаблонів не мають позиції в каталозі свого ремесла — вони отримають '
                  'ціну 0 при застосуванні шаблону. Не наслідок цієї міграції (найпевніше правка '
                  'через адмінку); список: SELECT et.trade, et.name, i.name FROM '
                  'estimate_template_items i JOIN estimate_templates et ON et.id=i.template_id '
                  'AND et.is_default WHERE et.trade IS NOT NULL AND NOT EXISTS (SELECT 1 FROM '
                  'catalog_templates c WHERE lower(c.name)=lower(i.name) AND c.trade=et.trade);',
                  preexisting;
  END IF;
END $$;
