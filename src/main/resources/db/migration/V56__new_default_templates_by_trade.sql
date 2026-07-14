-- New default estimate templates + their catalog positions for common jobs the
-- existing defaults didn't cover, gathered from a web pass over Ukrainian
-- contractor price lists (2026). ADDITIVE ONLY: brand-new templates and brand-new
-- catalog positions — no existing template is edited or split (per the rule). All
-- positions are WORK (labour), version 7 (V54 used 6). Prices are orientative UAH
-- hints a master refines; template item prices resolve from each master's own
-- catalog by name.
--
-- Trades covered: ELECTRICAL (CCTV, generator+ATS, EV charger), PLUMBING (septic,
-- well pump station), PAINTER (microcement), FLOORING (epoxy floor), DRYWALL (arch/decor).

-- 1. New catalog positions (version 7).
INSERT INTO catalog_templates (id, trade, name, type, unit, suggested_price, category, added_in_version) VALUES
-- ---- ELECTRICAL: Відеонагляд ------------------------------------------------
  (gen_random_uuid(), 'ELECTRICAL', 'Монтаж камери відеоспостереження',           'WORK', 'PIECE',         800.00, 'Відеонагляд', 7),
  (gen_random_uuid(), 'ELECTRICAL', 'Прокладання кабелю відеонагляду',            'WORK', 'LINEAR_METER',   50.00, 'Відеонагляд', 7),
  (gen_random_uuid(), 'ELECTRICAL', 'Монтаж та налаштування відеореєстратора',    'WORK', 'PIECE',        1500.00, 'Відеонагляд', 7),
  (gen_random_uuid(), 'ELECTRICAL', 'Налаштування хмарного відеодоступу',         'WORK', 'PIECE',         500.00, 'Відеонагляд', 7),
-- ---- ELECTRICAL: Генератор та АВР -------------------------------------------
  (gen_random_uuid(), 'ELECTRICAL', 'Монтаж генератора',                          'WORK', 'PIECE',        6000.00, 'Генератор та АВР', 7),
  (gen_random_uuid(), 'ELECTRICAL', 'Підключення АВР (автоввід резерву)',         'WORK', 'PIECE',        4000.00, 'Генератор та АВР', 7),
  (gen_random_uuid(), 'ELECTRICAL', 'Прокладання кабелю для генератора',          'WORK', 'LINEAR_METER',   90.00, 'Генератор та АВР', 7),
  (gen_random_uuid(), 'ELECTRICAL', 'Заземлення генератора',                      'WORK', 'SET',          2000.00, 'Генератор та АВР', 7),
-- ---- ELECTRICAL: Зарядка електромобіля --------------------------------------
  (gen_random_uuid(), 'ELECTRICAL', 'Монтаж зарядної станції для електромобіля',  'WORK', 'PIECE',        2500.00, 'Зарядка електромобіля', 7),
  (gen_random_uuid(), 'ELECTRICAL', 'Прокладання кабелю для зарядної станції',    'WORK', 'LINEAR_METER',   90.00, 'Зарядка електромобіля', 7),
  (gen_random_uuid(), 'ELECTRICAL', 'Установка захисної автоматики для зарядки',  'WORK', 'SET',          1500.00, 'Зарядка електромобіля', 7),
-- ---- PLUMBING: Септик та автономна каналізація ------------------------------
  (gen_random_uuid(), 'PLUMBING', 'Земляні роботи під септик',                    'WORK', 'M3',            600.00, 'Септик та автономна каналізація', 7),
  (gen_random_uuid(), 'PLUMBING', 'Монтаж септика',                               'WORK', 'PIECE',       10000.00, 'Септик та автономна каналізація', 7),
  (gen_random_uuid(), 'PLUMBING', 'Прокладання зовнішньої каналізації',           'WORK', 'LINEAR_METER',  250.00, 'Септик та автономна каналізація', 7),
  (gen_random_uuid(), 'PLUMBING', 'Пусконалагодження септика',                    'WORK', 'PIECE',        1500.00, 'Септик та автономна каналізація', 7),
-- ---- PLUMBING: Свердловина та насосна станція --------------------------------
  (gen_random_uuid(), 'PLUMBING', 'Підключення свердловинного насоса',            'WORK', 'PIECE',        3000.00, 'Свердловина та насосна станція', 7),
  -- "Монтаж насосної станції" already exists in the default catalog (PLUMBING/WORK/PIECE,
  -- version 2) — not re-added here to avoid a duplicate; the template item below
  -- resolves its price from that existing position.
  (gen_random_uuid(), 'PLUMBING', 'Установка гідроакумулятора',                   'WORK', 'PIECE',        1200.00, 'Свердловина та насосна станція', 7),
  (gen_random_uuid(), 'PLUMBING', 'Монтаж автоматики керування насосом',          'WORK', 'SET',          1500.00, 'Свердловина та насосна станція', 7),
-- ---- PAINTER: Мікроцемент ----------------------------------------------------
  (gen_random_uuid(), 'PAINTER', 'Ґрунтування основи під мікроцемент',           'WORK', 'M2',            120.00, 'Мікроцемент', 7),
  (gen_random_uuid(), 'PAINTER', 'Нанесення мікроцементу',                        'WORK', 'M2',           1200.00, 'Мікроцемент', 7),
  (gen_random_uuid(), 'PAINTER', 'Захисне лакування мікроцементу',                'WORK', 'M2',            250.00, 'Мікроцемент', 7),
-- ---- FLOORING: Епоксидна підлога ---------------------------------------------
  (gen_random_uuid(), 'FLOORING', 'Шліфування бетонної основи',                   'WORK', 'M2',            150.00, 'Епоксидна підлога', 7),
  (gen_random_uuid(), 'FLOORING', 'Ґрунтування основи під епоксидну підлогу',    'WORK', 'M2',             90.00, 'Епоксидна підлога', 7),
  (gen_random_uuid(), 'FLOORING', 'Нанесення епоксидного покриття',               'WORK', 'M2',            400.00, 'Епоксидна підлога', 7),
  (gen_random_uuid(), 'FLOORING', 'Фінішне лакування підлоги',                    'WORK', 'M2',            200.00, 'Епоксидна підлога', 7),
-- ---- DRYWALL: Арка та декор ГКЛ ----------------------------------------------
  (gen_random_uuid(), 'DRYWALL', 'Монтаж арки з гіпсокартону',                    'WORK', 'PIECE',        1500.00, 'Арка та декор ГКЛ', 7),
  (gen_random_uuid(), 'DRYWALL', 'Монтаж декоративних елементів з гіпсокартону',  'WORK', 'PIECE',         800.00, 'Арка та декор ГКЛ', 7),
  (gen_random_uuid(), 'DRYWALL', 'Шпаклювання та шліфування гіпсокартону',        'WORK', 'M2',            130.00, 'Арка та декор ГКЛ', 7);

-- 2. New default estimate templates.
INSERT INTO estimate_templates (id, owner_id, name, trade, is_default) VALUES
  (gen_random_uuid(), NULL, 'Відеонагляд',                        'ELECTRICAL', TRUE),
  (gen_random_uuid(), NULL, 'Генератор та АВР',                   'ELECTRICAL', TRUE),
  (gen_random_uuid(), NULL, 'Зарядка електромобіля',              'ELECTRICAL', TRUE),
  (gen_random_uuid(), NULL, 'Септик автономна каналізація',       'PLUMBING',   TRUE),
  (gen_random_uuid(), NULL, 'Свердловина насосна станція',        'PLUMBING',   TRUE),
  (gen_random_uuid(), NULL, 'Мікроцемент',                        'PAINTER',    TRUE),
  (gen_random_uuid(), NULL, 'Епоксидна підлога',                  'FLOORING',   TRUE),
  (gen_random_uuid(), NULL, 'Арка та декор ГКЛ',                  'DRYWALL',    TRUE);

-- 3. Template items, linked to their template by name (no literal UUIDs). Every
--    item name matches a catalog position above, so prices resolve per master.
INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, v.name, v.type, v.unit, v.sort_order
FROM estimate_templates t
JOIN (VALUES
  ('Відеонагляд', 'Монтаж камери відеоспостереження',          'WORK', 'PIECE',        0),
  ('Відеонагляд', 'Прокладання кабелю відеонагляду',           'WORK', 'LINEAR_METER', 1),
  ('Відеонагляд', 'Монтаж та налаштування відеореєстратора',   'WORK', 'PIECE',        2),
  ('Відеонагляд', 'Налаштування хмарного відеодоступу',        'WORK', 'PIECE',        3),
  ('Генератор та АВР', 'Монтаж генератора',                    'WORK', 'PIECE',        0),
  ('Генератор та АВР', 'Підключення АВР (автоввід резерву)',   'WORK', 'PIECE',        1),
  ('Генератор та АВР', 'Прокладання кабелю для генератора',    'WORK', 'LINEAR_METER', 2),
  ('Генератор та АВР', 'Заземлення генератора',                'WORK', 'SET',          3),
  ('Зарядка електромобіля', 'Монтаж зарядної станції для електромобіля', 'WORK', 'PIECE',        0),
  ('Зарядка електромобіля', 'Прокладання кабелю для зарядної станції',   'WORK', 'LINEAR_METER', 1),
  ('Зарядка електромобіля', 'Установка захисної автоматики для зарядки', 'WORK', 'SET',          2),
  ('Септик автономна каналізація', 'Земляні роботи під септик',          'WORK', 'M3',           0),
  ('Септик автономна каналізація', 'Монтаж септика',                     'WORK', 'PIECE',        1),
  ('Септик автономна каналізація', 'Прокладання зовнішньої каналізації', 'WORK', 'LINEAR_METER', 2),
  ('Септик автономна каналізація', 'Пусконалагодження септика',          'WORK', 'PIECE',        3),
  ('Свердловина насосна станція', 'Підключення свердловинного насоса',   'WORK', 'PIECE',        0),
  ('Свердловина насосна станція', 'Монтаж насосної станції',             'WORK', 'PIECE',        1),
  ('Свердловина насосна станція', 'Установка гідроакумулятора',          'WORK', 'PIECE',        2),
  ('Свердловина насосна станція', 'Монтаж автоматики керування насосом', 'WORK', 'SET',          3),
  ('Мікроцемент', 'Ґрунтування основи під мікроцемент',       'WORK', 'M2', 0),
  ('Мікроцемент', 'Нанесення мікроцементу',                    'WORK', 'M2', 1),
  ('Мікроцемент', 'Захисне лакування мікроцементу',            'WORK', 'M2', 2),
  ('Епоксидна підлога', 'Шліфування бетонної основи',                 'WORK', 'M2', 0),
  ('Епоксидна підлога', 'Ґрунтування основи під епоксидну підлогу',   'WORK', 'M2', 1),
  ('Епоксидна підлога', 'Нанесення епоксидного покриття',             'WORK', 'M2', 2),
  ('Епоксидна підлога', 'Фінішне лакування підлоги',                  'WORK', 'M2', 3),
  ('Арка та декор ГКЛ', 'Монтаж арки з гіпсокартону',                   'WORK', 'PIECE', 0),
  ('Арка та декор ГКЛ', 'Монтаж декоративних елементів з гіпсокартону', 'WORK', 'PIECE', 1),
  ('Арка та декор ГКЛ', 'Шпаклювання та шліфування гіпсокартону',       'WORK', 'M2',    2)
) AS v(tpl, name, type, unit, sort_order)
  ON t.name = v.tpl AND t.is_default AND t.trade IN ('ELECTRICAL', 'PLUMBING', 'PAINTER', 'FLOORING', 'DRYWALL')
  AND NOT EXISTS (SELECT 1 FROM estimate_template_items i WHERE i.template_id = t.id);
