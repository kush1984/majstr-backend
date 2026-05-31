-- Add category to the global starter templates and re-seed with logical
-- categories per trade. Templates are global read-only reference data
-- (no table references them by id — CatalogItem copies values, not FKs),
-- so a delete + re-insert is safe and does NOT touch any user's own
-- catalog_items copied earlier.

ALTER TABLE catalog_templates ADD COLUMN category VARCHAR(100);

DELETE FROM catalog_templates;

INSERT INTO catalog_templates (id, trade, category, name, type, unit, suggested_price) VALUES
-- =========================================================================
-- ELECTRICAL (18) — Кабельні роботи / Розетки та вимикачі / Щиток та автоматика
-- =========================================================================
(gen_random_uuid(), 'ELECTRICAL', 'Кабельні роботи',      'Прокладка кабелю в гофрі',           'WORK',     'M',     45.00),
(gen_random_uuid(), 'ELECTRICAL', 'Кабельні роботи',      'Прокладка кабелю відкритим способом','WORK',     'M',     35.00),
(gen_random_uuid(), 'ELECTRICAL', 'Кабельні роботи',      'Штроблення стіни (цегла)',           'WORK',     'M',     80.00),
(gen_random_uuid(), 'ELECTRICAL', 'Кабельні роботи',      'Штроблення стіни (бетон)',           'WORK',     'M',    150.00),
(gen_random_uuid(), 'ELECTRICAL', 'Розетки та вимикачі',  'Встановлення розетки',               'WORK',     'PIECE',180.00),
(gen_random_uuid(), 'ELECTRICAL', 'Розетки та вимикачі',  'Встановлення вимикача',              'WORK',     'PIECE',180.00),
(gen_random_uuid(), 'ELECTRICAL', 'Розетки та вимикачі',  'Встановлення точкового світильника', 'WORK',     'PIECE',220.00),
(gen_random_uuid(), 'ELECTRICAL', 'Розетки та вимикачі',  'Монтаж люстри',                      'WORK',     'PIECE',450.00),
(gen_random_uuid(), 'ELECTRICAL', 'Щиток та автоматика',  'Монтаж електрощитка до 12 модулів',  'WORK',     'PIECE',1500.00),
(gen_random_uuid(), 'ELECTRICAL', 'Щиток та автоматика',  'Монтаж електрощитка 13-36 модулів',  'WORK',     'PIECE',2800.00),
(gen_random_uuid(), 'ELECTRICAL', 'Щиток та автоматика',  'Встановлення автомата в щиток',      'WORK',     'PIECE', 80.00),
(gen_random_uuid(), 'ELECTRICAL', 'Кабельні роботи',      'Демонтаж старої проводки',           'WORK',     'M',     25.00),
(gen_random_uuid(), 'ELECTRICAL', 'Кабельні роботи',      'Кабель ВВГнг 3х2.5',                 'MATERIAL', 'M',     38.50),
(gen_random_uuid(), 'ELECTRICAL', 'Кабельні роботи',      'Кабель ВВГнг 3х1.5',                 'MATERIAL', 'M',     25.00),
(gen_random_uuid(), 'ELECTRICAL', 'Кабельні роботи',      'Гофра ПВХ 16мм',                     'MATERIAL', 'M',      8.00),
(gen_random_uuid(), 'ELECTRICAL', 'Розетки та вимикачі',  'Розетка Schneider Asfora',           'MATERIAL', 'PIECE', 95.00),
(gen_random_uuid(), 'ELECTRICAL', 'Розетки та вимикачі',  'Вимикач Schneider Asfora',           'MATERIAL', 'PIECE',110.00),
(gen_random_uuid(), 'ELECTRICAL', 'Щиток та автоматика',  'Автомат Schneider Easy9 16A',        'MATERIAL', 'PIECE',130.00),

-- =========================================================================
-- PLUMBING (18) — Труби / Сантехприлади / Каналізація
-- =========================================================================
(gen_random_uuid(), 'PLUMBING',   'Сантехприлади',        'Монтаж унітазу',                     'WORK',     'PIECE',1200.00),
(gen_random_uuid(), 'PLUMBING',   'Сантехприлади',        'Монтаж раковини',                    'WORK',     'PIECE',800.00),
(gen_random_uuid(), 'PLUMBING',   'Сантехприлади',        'Монтаж змішувача',                   'WORK',     'PIECE',350.00),
(gen_random_uuid(), 'PLUMBING',   'Сантехприлади',        'Монтаж душової кабіни',              'WORK',     'PIECE',2500.00),
(gen_random_uuid(), 'PLUMBING',   'Сантехприлади',        'Монтаж акрилової ванни',             'WORK',     'PIECE',1800.00),
(gen_random_uuid(), 'PLUMBING',   'Сантехприлади',        'Монтаж рушникосушки',                'WORK',     'PIECE',600.00),
(gen_random_uuid(), 'PLUMBING',   'Труби',                'Прокладка труби водопостачання',     'WORK',     'M',     90.00),
(gen_random_uuid(), 'PLUMBING',   'Каналізація',          'Прокладка каналізаційної труби',     'WORK',     'M',    110.00),
(gen_random_uuid(), 'PLUMBING',   'Труби',                'Заміна стояка водопостачання',       'WORK',     'M',    250.00),
(gen_random_uuid(), 'PLUMBING',   'Каналізація',          'Заміна стояка каналізації',          'WORK',     'M',    350.00),
(gen_random_uuid(), 'PLUMBING',   'Труби',                'Встановлення лічильника води',       'WORK',     'PIECE',450.00),
(gen_random_uuid(), 'PLUMBING',   'Сантехприлади',        'Демонтаж старої сантехніки',         'WORK',     'PIECE',350.00),
(gen_random_uuid(), 'PLUMBING',   'Труби',                'Труба поліпропіленова PN20 25мм',    'MATERIAL', 'M',     35.00),
(gen_random_uuid(), 'PLUMBING',   'Труби',                'Фітинг поліпропіленовий кутовий',    'MATERIAL', 'PIECE', 18.00),
(gen_random_uuid(), 'PLUMBING',   'Сантехприлади',        'Унітаз компакт Cersanit',            'MATERIAL', 'PIECE',4500.00),
(gen_random_uuid(), 'PLUMBING',   'Сантехприлади',        'Раковина Cersanit',                  'MATERIAL', 'PIECE',2200.00),
(gen_random_uuid(), 'PLUMBING',   'Сантехприлади',        'Змішувач Grohe',                     'MATERIAL', 'PIECE',3500.00),
(gen_random_uuid(), 'PLUMBING',   'Труби',                'Гнучкий шланг 50см',                 'MATERIAL', 'PIECE', 85.00),

-- =========================================================================
-- TILING (18) — Підготовка основи / Укладка / Затирка
-- =========================================================================
(gen_random_uuid(), 'TILING',     'Укладка',              'Укладка плитки на підлогу',          'WORK',     'M2',   350.00),
(gen_random_uuid(), 'TILING',     'Укладка',              'Укладка плитки на стіну',            'WORK',     'M2',   400.00),
(gen_random_uuid(), 'TILING',     'Укладка',              'Укладка мозаїки',                    'WORK',     'M2',   700.00),
(gen_random_uuid(), 'TILING',     'Укладка',              'Укладка плитки великого формату',    'WORK',     'M2',   550.00),
(gen_random_uuid(), 'TILING',     'Підготовка основи',    'Демонтаж старої плитки',             'WORK',     'M2',   120.00),
(gen_random_uuid(), 'TILING',     'Затирка',              'Затирка швів',                       'WORK',     'M2',    60.00),
(gen_random_uuid(), 'TILING',     'Підготовка основи',    'Підготовка поверхні (грунтовка)',    'WORK',     'M2',    50.00),
(gen_random_uuid(), 'TILING',     'Підготовка основи',    'Гідроізоляція санвузла',             'WORK',     'M2',   180.00),
(gen_random_uuid(), 'TILING',     'Підготовка основи',    'Стяжка під плитку',                  'WORK',     'M2',   220.00),
(gen_random_uuid(), 'TILING',     'Укладка',              'Різання плитки фігурне',             'WORK',     'M',     45.00),
(gen_random_uuid(), 'TILING',     'Укладка',              'Встановлення хрестиків / СВП',       'WORK',     'M2',    30.00),
(gen_random_uuid(), 'TILING',     'Укладка',              'Клей для плитки Ceresit CM 11 25кг', 'MATERIAL', 'PIECE',280.00),
(gen_random_uuid(), 'TILING',     'Затирка',              'Затирка для швів Ceresit CE 40 2кг', 'MATERIAL', 'PIECE',220.00),
(gen_random_uuid(), 'TILING',     'Підготовка основи',    'Гідроізоляція Ceresit CR 65 5кг',    'MATERIAL', 'PIECE',350.00),
(gen_random_uuid(), 'TILING',     'Підготовка основи',    'Грунтовка Ceresit CT 17 10л',        'MATERIAL', 'PIECE',580.00),
(gen_random_uuid(), 'TILING',     'Укладка',              'Хрестики дистанційні 2мм 100шт',     'MATERIAL', 'SET',   25.00),
(gen_random_uuid(), 'TILING',     'Укладка',              'Кутики ПВХ для плитки 2.5м',         'MATERIAL', 'PIECE', 65.00),
(gen_random_uuid(), 'TILING',     'Укладка',              'Профіль алюмінієвий 2.5м',           'MATERIAL', 'PIECE',180.00),

-- =========================================================================
-- GENERAL (20) — Демонтаж / Штукатурні роботи / Малярні роботи / Підлога.
-- A few items (ceiling, doors, windows) fit none of the four buckets and
-- are intentionally left uncategorized -> they land in "Без категорії".
-- =========================================================================
(gen_random_uuid(), 'GENERAL',    'Демонтаж',             'Демонтаж стіни цегляної',            'WORK',     'M2',   280.00),
(gen_random_uuid(), 'GENERAL',    'Демонтаж',             'Демонтаж стіни гіпсокартонної',      'WORK',     'M2',   120.00),
(gen_random_uuid(), 'GENERAL',    'Штукатурні роботи',    'Зведення стіни з газоблоку',         'WORK',     'M2',   350.00),
(gen_random_uuid(), 'GENERAL',    'Штукатурні роботи',    'Монтаж гіпсокартонної стіни',        'WORK',     'M2',   280.00),
(gen_random_uuid(), 'GENERAL',    'Штукатурні роботи',    'Штукатурка стін',                    'WORK',     'M2',   180.00),
(gen_random_uuid(), 'GENERAL',    'Штукатурні роботи',    'Шпаклівка стін',                     'WORK',     'M2',   130.00),
(gen_random_uuid(), 'GENERAL',    'Штукатурні роботи',    'Грунтовка стін',                     'WORK',     'M2',    35.00),
(gen_random_uuid(), 'GENERAL',    'Малярні роботи',       'Фарбування стін у 2 шари',           'WORK',     'M2',    95.00),
(gen_random_uuid(), 'GENERAL',    'Малярні роботи',       'Поклейка шпалер',                    'WORK',     'M2',   110.00),
(gen_random_uuid(), 'GENERAL',    NULL,                   'Монтаж натяжної стелі',              'WORK',     'M2',   250.00),
(gen_random_uuid(), 'GENERAL',    'Підлога',              'Стяжка підлоги',                     'WORK',     'M2',   220.00),
(gen_random_uuid(), 'GENERAL',    'Підлога',              'Укладка ламінату',                   'WORK',     'M2',   130.00),
(gen_random_uuid(), 'GENERAL',    'Підлога',              'Монтаж плінтуса',                    'WORK',     'M',     45.00),
(gen_random_uuid(), 'GENERAL',    NULL,                   'Монтаж міжкімнатних дверей',         'WORK',     'PIECE',1500.00),
(gen_random_uuid(), 'GENERAL',    NULL,                   'Монтаж металопластикового вікна',    'WORK',     'PIECE',1800.00),
(gen_random_uuid(), 'GENERAL',    'Демонтаж',             'Прибирання сміття після ремонту',    'WORK',     'HOUR',  250.00),
(gen_random_uuid(), 'GENERAL',    'Штукатурні роботи',    'Шпаклівка Knauf Rotband Pasta 18кг', 'MATERIAL', 'PIECE',480.00),
(gen_random_uuid(), 'GENERAL',    'Штукатурні роботи',    'Грунтовка глибокого проникнення 10л','MATERIAL', 'PIECE',320.00),
(gen_random_uuid(), 'GENERAL',    'Малярні роботи',       'Фарба інтер''єрна Sniezka 10л',      'MATERIAL', 'PIECE',1200.00),
(gen_random_uuid(), 'GENERAL',    'Штукатурні роботи',    'Гіпсокартон 12.5мм 1.2х2.5м',        'MATERIAL', 'PIECE',380.00);
