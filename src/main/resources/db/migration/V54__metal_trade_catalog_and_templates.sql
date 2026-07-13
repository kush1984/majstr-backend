-- New trade "Металоконструкції" (METAL) + a comprehensive default catalog and
-- estimate templates for metalwork (gates, fences, canopies, stairs/railings,
-- frames/trusses, wrought/decorative, welding, coating). Same additive pattern as
-- BUILDER (V26) / the tetris import (V50): old migrations are immutable, so each
-- trade CHECK is dropped and recreated with METAL added; catalog rows land at
-- added_in_version = 6 (V50 used 5) so any master who adds the METAL trade picks
-- them up via "add new from catalog".
--
-- Prices are ORIENTATIVE market hints (UAH, 2026) a practising fabricator refines —
-- metalwork is commonly priced per kg of structure, per linear metre (railings,
-- fences), per m2 (gates, mesh), per piece, per tonne (large frames), or per welder
-- hour. Templates carry no price: it resolves from each master's own catalog by name.

-- 1. Allow METAL everywhere a trade is constrained.
ALTER TABLE user_trades        DROP CONSTRAINT user_trades_trade_check;
ALTER TABLE user_trades        ADD  CONSTRAINT user_trades_trade_check
    CHECK (trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'METAL', 'GENERAL', 'OTHER'));

ALTER TABLE catalog_templates  DROP CONSTRAINT catalog_templates_trade_check;
ALTER TABLE catalog_templates  ADD  CONSTRAINT catalog_templates_trade_check
    CHECK (trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'METAL', 'GENERAL', 'OTHER'));

ALTER TABLE catalog_items      DROP CONSTRAINT catalog_items_trade_check;
ALTER TABLE catalog_items      ADD  CONSTRAINT catalog_items_trade_check
    CHECK (trade IS NULL OR trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'METAL', 'GENERAL', 'OTHER'));

ALTER TABLE estimate_templates DROP CONSTRAINT estimate_templates_trade_check;
ALTER TABLE estimate_templates ADD  CONSTRAINT estimate_templates_trade_check
    CHECK (trade IS NULL OR trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL', 'FLOORING', 'DEMOLITION', 'METAL', 'GENERAL', 'OTHER'));

-- 2. Default catalog for METAL (orientative prices; version 6).
INSERT INTO catalog_templates (id, trade, name, type, unit, suggested_price, category, added_in_version) VALUES
-- ---- Ворота та хвіртки -------------------------------------------------------
  (gen_random_uuid(), 'METAL', 'Виготовлення відкатних воріт (каркас, зашивка)', 'WORK', 'M2',           2500.00, 'Ворота та хвіртки', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення розпашних воріт (каркас, зашивка)', 'WORK', 'M2',           2200.00, 'Ворота та хвіртки', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення хвіртки металевої',                 'WORK', 'PIECE',         3500.00, 'Ворота та хвіртки', 6),
  (gen_random_uuid(), 'METAL', 'Монтаж воріт на місці',                          'WORK', 'PIECE',         3000.00, 'Ворота та хвіртки', 6),
  (gen_random_uuid(), 'METAL', 'Бетонування стовпів під ворота',                 'WORK', 'PIECE',          800.00, 'Ворота та хвіртки', 6),
  (gen_random_uuid(), 'METAL', 'Монтаж автоматики для воріт',                    'WORK', 'SET',           4000.00, 'Ворота та хвіртки', 6),
-- ---- Паркани та огорожі ------------------------------------------------------
  (gen_random_uuid(), 'METAL', 'Виготовлення секції паркану з профтруби',        'WORK', 'M2',           1200.00, 'Паркани та огорожі', 6),
  (gen_random_uuid(), 'METAL', 'Зварювання каркасу та лаг паркану',              'WORK', 'LINEAR_METER',  300.00, 'Паркани та огорожі', 6),
  (gen_random_uuid(), 'METAL', 'Монтаж стовпів для паркану',                     'WORK', 'PIECE',          500.00, 'Паркани та огорожі', 6),
  (gen_random_uuid(), 'METAL', 'Монтаж профнастилу на паркан',                   'WORK', 'M2',            250.00, 'Паркани та огорожі', 6),
  (gen_random_uuid(), 'METAL', 'Монтаж секції 3D-сітки',                         'WORK', 'LINEAR_METER',  600.00, 'Паркани та огорожі', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення кованої секції паркану',            'WORK', 'M2',           3000.00, 'Паркани та огорожі', 6),
-- ---- Навіси та козирки -------------------------------------------------------
  (gen_random_uuid(), 'METAL', 'Виготовлення каркасу навісу',                    'WORK', 'M2',           1800.00, 'Навіси та козирки', 6),
  (gen_random_uuid(), 'METAL', 'Монтаж опорних стовпів навісу',                  'WORK', 'PIECE',          900.00, 'Навіси та козирки', 6),
  (gen_random_uuid(), 'METAL', 'Монтаж покрівлі навісу з полікарбонату',         'WORK', 'M2',            400.00, 'Навіси та козирки', 6),
  (gen_random_uuid(), 'METAL', 'Монтаж покрівлі навісу з профнастилу',           'WORK', 'M2',            350.00, 'Навіси та козирки', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення козирка над входом',                'WORK', 'PIECE',         4000.00, 'Навіси та козирки', 6),
-- ---- Сходи та огородження ----------------------------------------------------
  (gen_random_uuid(), 'METAL', 'Виготовлення металевого каркасу сходів',         'WORK', 'PIECE',          900.00, 'Сходи та огородження', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення поручнів металевих',                'WORK', 'LINEAR_METER', 1200.00, 'Сходи та огородження', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення поручнів з нержавіючої сталі',      'WORK', 'LINEAR_METER', 2500.00, 'Сходи та огородження', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення балконного огородження',            'WORK', 'LINEAR_METER', 1500.00, 'Сходи та огородження', 6),
  (gen_random_uuid(), 'METAL', 'Монтаж перил та огородження',                    'WORK', 'LINEAR_METER',  400.00, 'Сходи та огородження', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення пандуса металевого',                'WORK', 'PIECE',         6000.00, 'Сходи та огородження', 6),
-- ---- Каркаси та ферми --------------------------------------------------------
  (gen_random_uuid(), 'METAL', 'Виготовлення металоконструкцій',                 'WORK', 'KG',              45.00, 'Каркаси та ферми', 6),
  (gen_random_uuid(), 'METAL', 'Монтаж металоконструкцій',                       'WORK', 'KG',              20.00, 'Каркаси та ферми', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення ферми',                             'WORK', 'PIECE',         8000.00, 'Каркаси та ферми', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення колон та стійок',                   'WORK', 'PIECE',         3000.00, 'Каркаси та ферми', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення балок та прогонів',                 'WORK', 'LINEAR_METER',  700.00, 'Каркаси та ферми', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення закладних деталей',                 'WORK', 'PIECE',          150.00, 'Каркаси та ферми', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення антресолі та площадки',             'WORK', 'M2',           2500.00, 'Каркаси та ферми', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення каркасу ангара',                    'WORK', 'T',           35000.00, 'Каркаси та ферми', 6),
-- ---- Ковані та декоративні вироби --------------------------------------------
  (gen_random_uuid(), 'METAL', 'Виготовлення кованих ґрат на вікна',             'WORK', 'M2',           2500.00, 'Ковані та декоративні вироби', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення решітки металевої',                 'WORK', 'M2',           2000.00, 'Ковані та декоративні вироби', 6),
  (gen_random_uuid(), 'METAL', 'Виготовлення кованих декоративних елементів',    'WORK', 'PIECE',          500.00, 'Ковані та декоративні вироби', 6),
-- ---- Зварювальні та монтажні роботи ------------------------------------------
  (gen_random_uuid(), 'METAL', 'Зварювальні роботи (напівавтомат)',             'WORK', 'HOUR',           500.00, 'Зварювальні та монтажні роботи', 6),
  (gen_random_uuid(), 'METAL', 'Зварювальні роботи (аргон)',                    'WORK', 'HOUR',           800.00, 'Зварювальні та монтажні роботи', 6),
  (gen_random_uuid(), 'METAL', 'Різка металу',                                   'WORK', 'LINEAR_METER',   80.00, 'Зварювальні та монтажні роботи', 6),
  (gen_random_uuid(), 'METAL', 'Свердління отворів у металі',                    'WORK', 'PIECE',           30.00, 'Зварювальні та монтажні роботи', 6),
  (gen_random_uuid(), 'METAL', 'Гнуття профтруби та листа',                      'WORK', 'PIECE',          100.00, 'Зварювальні та монтажні роботи', 6),
  (gen_random_uuid(), 'METAL', 'Демонтаж металоконструкцій',                     'WORK', 'KG',              12.00, 'Зварювальні та монтажні роботи', 6),
-- ---- Обробка та покриття -----------------------------------------------------
  (gen_random_uuid(), 'METAL', 'Зачистка та знежирення металу',                  'WORK', 'M2',            120.00, 'Обробка та покриття', 6),
  (gen_random_uuid(), 'METAL', 'Ґрунтування металу',                            'WORK', 'M2',             90.00, 'Обробка та покриття', 6),
  (gen_random_uuid(), 'METAL', 'Фарбування металу',                              'WORK', 'M2',            150.00, 'Обробка та покриття', 6),
  (gen_random_uuid(), 'METAL', 'Порошкове фарбування (послуга)',                 'WORK', 'M2',            350.00, 'Обробка та покриття', 6),
  (gen_random_uuid(), 'METAL', 'Гаряче цинкування (послуга)',                    'WORK', 'KG',              25.00, 'Обробка та покриття', 6),
  (gen_random_uuid(), 'METAL', 'Піскоструминна обробка',                         'WORK', 'M2',            200.00, 'Обробка та покриття', 6),
-- ---- Матеріали ---------------------------------------------------------------
  (gen_random_uuid(), 'METAL', 'Профільна труба 20х20',                          'MATERIAL', 'LINEAR_METER',  45.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Профільна труба 40х20',                          'MATERIAL', 'LINEAR_METER',  70.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Профільна труба 60х40',                          'MATERIAL', 'LINEAR_METER', 150.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Профільна труба 80х80',                          'MATERIAL', 'LINEAR_METER', 280.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Труба кругла',                                   'MATERIAL', 'LINEAR_METER',  90.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Куток металевий',                                'MATERIAL', 'LINEAR_METER',  80.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Швелер',                                         'MATERIAL', 'LINEAR_METER', 250.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Двотавр (балка)',                                'MATERIAL', 'LINEAR_METER', 600.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Лист металевий',                                 'MATERIAL', 'M2',           900.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Лист оцинкований',                               'MATERIAL', 'M2',           700.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Профнастил',                                     'MATERIAL', 'M2',           350.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Труба нержавіюча',                               'MATERIAL', 'LINEAR_METER', 400.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Полікарбонат стільниковий',                      'MATERIAL', 'M2',           450.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Сітка зварна 3D',                                'MATERIAL', 'M2',           350.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Стовп металевий',                                'MATERIAL', 'PIECE',        600.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Петлі воротні',                                  'MATERIAL', 'PIECE',        150.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Замок воротний',                                 'MATERIAL', 'PIECE',        400.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Дріт зварювальний та електроди',                 'MATERIAL', 'KG',           120.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Ґрунт-емаль по металу',                         'MATERIAL', 'KG',           180.00, 'Матеріали (метал)', 6),
  (gen_random_uuid(), 'METAL', 'Метизи (болти, гайки)',                          'MATERIAL', 'SET',          200.00, 'Матеріали (метал)', 6);

-- 3. Estimate templates (ready bundles). Item names match the catalog names above
--    so each master's price resolves from their own catalog at apply-time; items
--    are linked to their template by name (no literal UUIDs needed).
INSERT INTO estimate_templates (id, owner_id, name, trade, is_default) VALUES
  (gen_random_uuid(), NULL, 'Ворота відкатні',              'METAL', TRUE),
  (gen_random_uuid(), NULL, 'Паркан металевий',             'METAL', TRUE),
  (gen_random_uuid(), NULL, 'Навіс металевий',              'METAL', TRUE),
  (gen_random_uuid(), NULL, 'Сходи та перила',              'METAL', TRUE),
  (gen_random_uuid(), NULL, 'Каркас та ферми',              'METAL', TRUE),
  (gen_random_uuid(), NULL, 'Козирок над входом',           'METAL', TRUE),
  (gen_random_uuid(), NULL, 'Ковані ґрати на вікна',        'METAL', TRUE);

INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, v.type, v.unit, v.sort_order
FROM estimate_templates t
JOIN (VALUES
  -- Ворота відкатні
  ('Ворота відкатні', 'Виготовлення відкатних воріт (каркас, зашивка)', 'WORK', 'M2',    0),
  ('Ворота відкатні', 'Бетонування стовпів під ворота',                 'WORK', 'PIECE', 1),
  ('Ворота відкатні', 'Монтаж воріт на місці',                          'WORK', 'PIECE', 2),
  ('Ворота відкатні', 'Монтаж автоматики для воріт',                    'WORK', 'SET',   3),
  ('Ворота відкатні', 'Фарбування металу',                              'WORK', 'M2',    4),
  ('Ворота відкатні', 'Профільна труба 60х40',                          'MATERIAL', 'LINEAR_METER', 5),
  ('Ворота відкатні', 'Профнастил',                                     'MATERIAL', 'M2', 6),
  -- Паркан металевий
  ('Паркан металевий', 'Монтаж стовпів для паркану',                    'WORK', 'PIECE', 0),
  ('Паркан металевий', 'Зварювання каркасу та лаг паркану',             'WORK', 'LINEAR_METER', 1),
  ('Паркан металевий', 'Монтаж профнастилу на паркан',                  'WORK', 'M2',    2),
  ('Паркан металевий', 'Фарбування металу',                             'WORK', 'M2',    3),
  ('Паркан металевий', 'Стовп металевий',                               'MATERIAL', 'PIECE', 4),
  ('Паркан металевий', 'Профільна труба 40х20',                         'MATERIAL', 'LINEAR_METER', 5),
  ('Паркан металевий', 'Профнастил',                                    'MATERIAL', 'M2', 6),
  -- Навіс металевий
  ('Навіс металевий', 'Монтаж опорних стовпів навісу',                  'WORK', 'PIECE', 0),
  ('Навіс металевий', 'Виготовлення каркасу навісу',                    'WORK', 'M2',    1),
  ('Навіс металевий', 'Монтаж покрівлі навісу з полікарбонату',         'WORK', 'M2',    2),
  ('Навіс металевий', 'Фарбування металу',                              'WORK', 'M2',    3),
  ('Навіс металевий', 'Профільна труба 60х40',                          'MATERIAL', 'LINEAR_METER', 4),
  ('Навіс металевий', 'Полікарбонат стільниковий',                      'MATERIAL', 'M2', 5),
  -- Сходи та перила
  ('Сходи та перила', 'Виготовлення металевого каркасу сходів',         'WORK', 'PIECE', 0),
  ('Сходи та перила', 'Виготовлення поручнів металевих',                'WORK', 'LINEAR_METER', 1),
  ('Сходи та перила', 'Монтаж перил та огородження',                    'WORK', 'LINEAR_METER', 2),
  ('Сходи та перила', 'Фарбування металу',                              'WORK', 'M2',    3),
  ('Сходи та перила', 'Профільна труба 40х20',                          'MATERIAL', 'LINEAR_METER', 4),
  -- Каркас та ферми
  ('Каркас та ферми', 'Виготовлення металоконструкцій',                 'WORK', 'KG',    0),
  ('Каркас та ферми', 'Виготовлення ферми',                             'WORK', 'PIECE', 1),
  ('Каркас та ферми', 'Монтаж металоконструкцій',                       'WORK', 'KG',    2),
  ('Каркас та ферми', 'Ґрунтування металу',                            'WORK', 'M2',    3),
  ('Каркас та ферми', 'Гаряче цинкування (послуга)',                    'WORK', 'KG',    4),
  ('Каркас та ферми', 'Двотавр (балка)',                                'MATERIAL', 'LINEAR_METER', 5),
  -- Козирок над входом
  ('Козирок над входом', 'Виготовлення козирка над входом',             'WORK', 'PIECE', 0),
  ('Козирок над входом', 'Монтаж покрівлі навісу з полікарбонату',      'WORK', 'M2',    1),
  ('Козирок над входом', 'Фарбування металу',                           'WORK', 'M2',    2),
  ('Козирок над входом', 'Полікарбонат стільниковий',                   'MATERIAL', 'M2', 3),
  -- Ковані ґрати на вікна
  ('Ковані ґрати на вікна', 'Виготовлення кованих ґрат на вікна',       'WORK', 'M2',    0),
  ('Ковані ґрати на вікна', 'Виготовлення кованих декоративних елементів', 'WORK', 'PIECE', 1),
  ('Ковані ґрати на вікна', 'Фарбування металу',                        'WORK', 'M2',    2),
  ('Ковані ґрати на вікна', 'Профільна труба 20х20',                    'MATERIAL', 'LINEAR_METER', 3)
) AS v(tpl, name, type, unit, sort_order)
  ON t.name = v.tpl AND t.trade = 'METAL' AND t.is_default;
