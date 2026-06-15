-- New trade "Будівельник" (BUILDER) + construction units M3 (м³) and T (тонна),
-- which building work needs (concrete by m³, rebar/sand/gravel by tonne), plus a
-- starter catalog template. The template is a SKELETON: prices are placeholders
-- a practising builder will refine; it lives as plain data so it's trivial to edit.
-- Old migrations are immutable, so each CHECK is dropped and recreated with the
-- extended value set. Purely additive — existing rows are untouched.

-- 1. Allow BUILDER in user trades and template trades.
ALTER TABLE user_trades       DROP CONSTRAINT user_trades_trade_check;
ALTER TABLE user_trades       ADD  CONSTRAINT user_trades_trade_check
    CHECK (trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'GENERAL', 'OTHER'));

ALTER TABLE catalog_templates DROP CONSTRAINT catalog_templates_trade_check;
ALTER TABLE catalog_templates ADD  CONSTRAINT catalog_templates_trade_check
    CHECK (trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'GENERAL', 'OTHER'));

-- 2. Allow M3 / T in every unit column.
ALTER TABLE catalog_items     DROP CONSTRAINT catalog_items_unit_check;
ALTER TABLE catalog_items     ADD  CONSTRAINT catalog_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T'));

ALTER TABLE estimate_items    DROP CONSTRAINT estimate_items_unit_check;
ALTER TABLE estimate_items    ADD  CONSTRAINT estimate_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T'));

ALTER TABLE catalog_templates DROP CONSTRAINT catalog_templates_unit_check;
ALTER TABLE catalog_templates ADD  CONSTRAINT catalog_templates_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T'));

-- 3. Starter set for BUILDER (skeleton; placeholder prices, refined by a master).
INSERT INTO catalog_templates (id, trade, category, name, type, unit, suggested_price) VALUES
-- ---- WORK -----------------------------------------------------------------
(gen_random_uuid(), 'BUILDER', 'Земляні та фундамент',  'Розробка ґрунту вручну',            'WORK', 'M3',           600.00),
(gen_random_uuid(), 'BUILDER', 'Земляні та фундамент',  'Розробка ґрунту механізовано',      'WORK', 'M3',           250.00),
(gen_random_uuid(), 'BUILDER', 'Земляні та фундамент',  'Влаштування опалубки',              'WORK', 'M2',           350.00),
(gen_random_uuid(), 'BUILDER', 'Земляні та фундамент',  'В''язання арматурного каркасу',     'WORK', 'T',           6000.00),
(gen_random_uuid(), 'BUILDER', 'Земляні та фундамент',  'Заливка фундаменту бетоном',        'WORK', 'M3',           900.00),
(gen_random_uuid(), 'BUILDER', 'Земляні та фундамент',  'Гідроізоляція фундаменту',          'WORK', 'M2',           180.00),
(gen_random_uuid(), 'BUILDER', 'Мурування',             'Мурування стін з цегли',            'WORK', 'M3',          2200.00),
(gen_random_uuid(), 'BUILDER', 'Мурування',             'Мурування з газоблоку',             'WORK', 'M3',          1500.00),
(gen_random_uuid(), 'BUILDER', 'Мурування',             'Мурування перегородок',             'WORK', 'M2',           450.00),
(gen_random_uuid(), 'BUILDER', 'Мурування',             'Влаштування перемичок',             'WORK', 'LINEAR_METER', 350.00),
(gen_random_uuid(), 'BUILDER', 'Перекриття та покрівля','Монтаж монолітного перекриття',     'WORK', 'M2',          1200.00),
(gen_random_uuid(), 'BUILDER', 'Перекриття та покрівля','Монтаж збірного перекриття',        'WORK', 'M2',           600.00),
(gen_random_uuid(), 'BUILDER', 'Перекриття та покрівля','Влаштування кроквяної системи',     'WORK', 'M2',           550.00),
(gen_random_uuid(), 'BUILDER', 'Перекриття та покрівля','Монтаж покрівлі',                   'WORK', 'M2',           400.00),
(gen_random_uuid(), 'BUILDER', 'Оздоблення та демонтаж','Стяжка підлоги',                    'WORK', 'M2',           350.00),
(gen_random_uuid(), 'BUILDER', 'Оздоблення та демонтаж','Штукатурка стін (чорнова)',         'WORK', 'M2',           280.00),
(gen_random_uuid(), 'BUILDER', 'Оздоблення та демонтаж','Демонтажні роботи',                 'WORK', 'M2',           200.00),
-- ---- MATERIAL -------------------------------------------------------------
(gen_random_uuid(), 'BUILDER', 'Бетон та розчини',      'Бетон товарний',                    'MATERIAL', 'M3',      3200.00),
(gen_random_uuid(), 'BUILDER', 'Бетон та розчини',      'Цемент (мішок)',                    'MATERIAL', 'PIECE',    250.00),
(gen_random_uuid(), 'BUILDER', 'Бетон та розчини',      'Пісок',                             'MATERIAL', 'T',        600.00),
(gen_random_uuid(), 'BUILDER', 'Бетон та розчини',      'Щебінь',                            'MATERIAL', 'T',        700.00),
(gen_random_uuid(), 'BUILDER', 'Стінові матеріали',     'Цегла рядова',                      'MATERIAL', 'PIECE',     12.00),
(gen_random_uuid(), 'BUILDER', 'Стінові матеріали',     'Газоблок',                          'MATERIAL', 'M3',      3000.00),
(gen_random_uuid(), 'BUILDER', 'Метал та армування',    'Арматура',                          'MATERIAL', 'T',      32000.00),
(gen_random_uuid(), 'BUILDER', 'Метал та армування',    'Сітка армувальна',                  'MATERIAL', 'M2',        60.00),
(gen_random_uuid(), 'BUILDER', 'Пиломатеріали',         'Дошка обрізна',                     'MATERIAL', 'M3',     12000.00),
(gen_random_uuid(), 'BUILDER', 'Пиломатеріали',         'Брус',                              'MATERIAL', 'M3',     13000.00),
(gen_random_uuid(), 'BUILDER', 'Гідроізоляція',         'Гідроізоляція (рулон)',             'MATERIAL', 'M2',        90.00);
