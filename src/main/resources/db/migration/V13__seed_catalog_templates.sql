-- Seed: starter catalog items by trade. Copied into a user's own catalog
-- on registration so they never start with an empty library.
-- All text and prices in Ukrainian/UAH; prices are realistic for 2026.

INSERT INTO catalog_templates (id, trade, name, type, unit, suggested_price) VALUES
-- =========================================================================
-- ELECTRICAL (18)
-- =========================================================================
(gen_random_uuid(), 'ELECTRICAL', 'Прокладка кабелю в гофрі',           'WORK',     'M',     45.00),
(gen_random_uuid(), 'ELECTRICAL', 'Прокладка кабелю відкритим способом','WORK',     'M',     35.00),
(gen_random_uuid(), 'ELECTRICAL', 'Штроблення стіни (цегла)',           'WORK',     'M',     80.00),
(gen_random_uuid(), 'ELECTRICAL', 'Штроблення стіни (бетон)',           'WORK',     'M',    150.00),
(gen_random_uuid(), 'ELECTRICAL', 'Встановлення розетки',               'WORK',     'PIECE',180.00),
(gen_random_uuid(), 'ELECTRICAL', 'Встановлення вимикача',              'WORK',     'PIECE',180.00),
(gen_random_uuid(), 'ELECTRICAL', 'Встановлення точкового світильника', 'WORK',     'PIECE',220.00),
(gen_random_uuid(), 'ELECTRICAL', 'Монтаж люстри',                      'WORK',     'PIECE',450.00),
(gen_random_uuid(), 'ELECTRICAL', 'Монтаж електрощитка до 12 модулів',  'WORK',     'PIECE',1500.00),
(gen_random_uuid(), 'ELECTRICAL', 'Монтаж електрощитка 13-36 модулів',  'WORK',     'PIECE',2800.00),
(gen_random_uuid(), 'ELECTRICAL', 'Встановлення автомата в щиток',      'WORK',     'PIECE', 80.00),
(gen_random_uuid(), 'ELECTRICAL', 'Демонтаж старої проводки',           'WORK',     'M',     25.00),
(gen_random_uuid(), 'ELECTRICAL', 'Кабель ВВГнг 3х2.5',                 'MATERIAL', 'M',     38.50),
(gen_random_uuid(), 'ELECTRICAL', 'Кабель ВВГнг 3х1.5',                 'MATERIAL', 'M',     25.00),
(gen_random_uuid(), 'ELECTRICAL', 'Гофра ПВХ 16мм',                     'MATERIAL', 'M',      8.00),
(gen_random_uuid(), 'ELECTRICAL', 'Розетка Schneider Asfora',           'MATERIAL', 'PIECE', 95.00),
(gen_random_uuid(), 'ELECTRICAL', 'Вимикач Schneider Asfora',           'MATERIAL', 'PIECE',110.00),
(gen_random_uuid(), 'ELECTRICAL', 'Автомат Schneider Easy9 16A',        'MATERIAL', 'PIECE',130.00),

-- =========================================================================
-- PLUMBING (18)
-- =========================================================================
(gen_random_uuid(), 'PLUMBING',   'Монтаж унітазу',                     'WORK',     'PIECE',1200.00),
(gen_random_uuid(), 'PLUMBING',   'Монтаж раковини',                    'WORK',     'PIECE',800.00),
(gen_random_uuid(), 'PLUMBING',   'Монтаж змішувача',                   'WORK',     'PIECE',350.00),
(gen_random_uuid(), 'PLUMBING',   'Монтаж душової кабіни',              'WORK',     'PIECE',2500.00),
(gen_random_uuid(), 'PLUMBING',   'Монтаж акрилової ванни',             'WORK',     'PIECE',1800.00),
(gen_random_uuid(), 'PLUMBING',   'Монтаж рушникосушки',                'WORK',     'PIECE',600.00),
(gen_random_uuid(), 'PLUMBING',   'Прокладка труби водопостачання',     'WORK',     'M',     90.00),
(gen_random_uuid(), 'PLUMBING',   'Прокладка каналізаційної труби',     'WORK',     'M',    110.00),
(gen_random_uuid(), 'PLUMBING',   'Заміна стояка водопостачання',       'WORK',     'M',    250.00),
(gen_random_uuid(), 'PLUMBING',   'Заміна стояка каналізації',          'WORK',     'M',    350.00),
(gen_random_uuid(), 'PLUMBING',   'Встановлення лічильника води',       'WORK',     'PIECE',450.00),
(gen_random_uuid(), 'PLUMBING',   'Демонтаж старої сантехніки',         'WORK',     'PIECE',350.00),
(gen_random_uuid(), 'PLUMBING',   'Труба поліпропіленова PN20 25мм',    'MATERIAL', 'M',     35.00),
(gen_random_uuid(), 'PLUMBING',   'Фітинг поліпропіленовий кутовий',    'MATERIAL', 'PIECE', 18.00),
(gen_random_uuid(), 'PLUMBING',   'Унітаз компакт Cersanit',            'MATERIAL', 'PIECE',4500.00),
(gen_random_uuid(), 'PLUMBING',   'Раковина Cersanit',                  'MATERIAL', 'PIECE',2200.00),
(gen_random_uuid(), 'PLUMBING',   'Змішувач Grohe',                     'MATERIAL', 'PIECE',3500.00),
(gen_random_uuid(), 'PLUMBING',   'Гнучкий шланг 50см',                 'MATERIAL', 'PIECE', 85.00),

-- =========================================================================
-- TILING (18)
-- =========================================================================
(gen_random_uuid(), 'TILING',     'Укладка плитки на підлогу',          'WORK',     'M2',   350.00),
(gen_random_uuid(), 'TILING',     'Укладка плитки на стіну',            'WORK',     'M2',   400.00),
(gen_random_uuid(), 'TILING',     'Укладка мозаїки',                    'WORK',     'M2',   700.00),
(gen_random_uuid(), 'TILING',     'Укладка плитки великого формату',    'WORK',     'M2',   550.00),
(gen_random_uuid(), 'TILING',     'Демонтаж старої плитки',             'WORK',     'M2',   120.00),
(gen_random_uuid(), 'TILING',     'Затирка швів',                       'WORK',     'M2',    60.00),
(gen_random_uuid(), 'TILING',     'Підготовка поверхні (грунтовка)',    'WORK',     'M2',    50.00),
(gen_random_uuid(), 'TILING',     'Гідроізоляція санвузла',             'WORK',     'M2',   180.00),
(gen_random_uuid(), 'TILING',     'Стяжка під плитку',                  'WORK',     'M2',   220.00),
(gen_random_uuid(), 'TILING',     'Різання плитки фігурне',             'WORK',     'M',     45.00),
(gen_random_uuid(), 'TILING',     'Встановлення хрестиків / СВП',       'WORK',     'M2',    30.00),
(gen_random_uuid(), 'TILING',     'Клей для плитки Ceresit CM 11 25кг', 'MATERIAL', 'PIECE',280.00),
(gen_random_uuid(), 'TILING',     'Затирка для швів Ceresit CE 40 2кг', 'MATERIAL', 'PIECE',220.00),
(gen_random_uuid(), 'TILING',     'Гідроізоляція Ceresit CR 65 5кг',    'MATERIAL', 'PIECE',350.00),
(gen_random_uuid(), 'TILING',     'Грунтовка Ceresit CT 17 10л',        'MATERIAL', 'PIECE',580.00),
(gen_random_uuid(), 'TILING',     'Хрестики дистанційні 2мм 100шт',     'MATERIAL', 'SET',   25.00),
(gen_random_uuid(), 'TILING',     'Кутики ПВХ для плитки 2.5м',         'MATERIAL', 'PIECE', 65.00),
(gen_random_uuid(), 'TILING',     'Профіль алюмінієвий 2.5м',           'MATERIAL', 'PIECE',180.00),

-- =========================================================================
-- GENERAL (20)
-- =========================================================================
(gen_random_uuid(), 'GENERAL',    'Демонтаж стіни цегляної',            'WORK',     'M2',   280.00),
(gen_random_uuid(), 'GENERAL',    'Демонтаж стіни гіпсокартонної',      'WORK',     'M2',   120.00),
(gen_random_uuid(), 'GENERAL',    'Зведення стіни з газоблоку',         'WORK',     'M2',   350.00),
(gen_random_uuid(), 'GENERAL',    'Монтаж гіпсокартонної стіни',        'WORK',     'M2',   280.00),
(gen_random_uuid(), 'GENERAL',    'Штукатурка стін',                    'WORK',     'M2',   180.00),
(gen_random_uuid(), 'GENERAL',    'Шпаклівка стін',                     'WORK',     'M2',   130.00),
(gen_random_uuid(), 'GENERAL',    'Грунтовка стін',                     'WORK',     'M2',    35.00),
(gen_random_uuid(), 'GENERAL',    'Фарбування стін у 2 шари',           'WORK',     'M2',    95.00),
(gen_random_uuid(), 'GENERAL',    'Поклейка шпалер',                    'WORK',     'M2',   110.00),
(gen_random_uuid(), 'GENERAL',    'Монтаж натяжної стелі',              'WORK',     'M2',   250.00),
(gen_random_uuid(), 'GENERAL',    'Стяжка підлоги',                     'WORK',     'M2',   220.00),
(gen_random_uuid(), 'GENERAL',    'Укладка ламінату',                   'WORK',     'M2',   130.00),
(gen_random_uuid(), 'GENERAL',    'Монтаж плінтуса',                    'WORK',     'M',     45.00),
(gen_random_uuid(), 'GENERAL',    'Монтаж міжкімнатних дверей',         'WORK',     'PIECE',1500.00),
(gen_random_uuid(), 'GENERAL',    'Монтаж металопластикового вікна',    'WORK',     'PIECE',1800.00),
(gen_random_uuid(), 'GENERAL',    'Прибирання сміття після ремонту',    'WORK',     'HOUR',  250.00),
(gen_random_uuid(), 'GENERAL',    'Шпаклівка Knauf Rotband Pasta 18кг', 'MATERIAL', 'PIECE',480.00),
(gen_random_uuid(), 'GENERAL',    'Грунтовка глибокого проникнення 10л','MATERIAL', 'PIECE',320.00),
(gen_random_uuid(), 'GENERAL',    'Фарба інтер''єрна Sniezka 10л',      'MATERIAL', 'PIECE',1200.00),
(gen_random_uuid(), 'GENERAL',    'Гіпсокартон 12.5мм 1.2х2.5м',        'MATERIAL', 'PIECE',380.00);
