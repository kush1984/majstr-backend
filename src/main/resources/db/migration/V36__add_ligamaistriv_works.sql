-- V36 — additional WORK positions sourced from the Ліга Майстрів published price lists
-- (ligamaistriv.com.ua/price). Our default catalog already covered ~95% of that source at
-- finer granularity; these 15 are the genuine gaps: electric energy meters, monolithic
-- concrete structures, a few roofing details, a sewer tee swap, and drywall glue-mount /
-- acoustic membrane / milling / reinforced-profile framing. type='WORK', added_in_version=4
-- so existing masters get them via "Add new from library" and fresh registrations get them
-- seeded (currentVersion = MAX(added_in_version) bumps 3 -> 4). Prices are the published
-- figures (range midpoints where the source gave a range) the master adjusts per job.
INSERT INTO catalog_templates (id, trade, category, name, type, unit, suggested_price, added_in_version) VALUES
(gen_random_uuid(), 'ELECTRICAL', 'Щит', 'Установка та підключення однофазного лічильника електроенергії', 'WORK', 'PIECE', 600.00, 4),
(gen_random_uuid(), 'ELECTRICAL', 'Щит', 'Установка та підключення трифазного лічильника електроенергії', 'WORK', 'PIECE', 1200.00, 4),
(gen_random_uuid(), 'ELECTRICAL', 'Кабель', 'Штроблення під електрокабель 20х20 в газоблоці', 'WORK', 'LINEAR_METER', 50.00, 4),
(gen_random_uuid(), 'BUILDER', 'Монтажні', 'Монолітне перекриття приймання бетону армування', 'WORK', 'M3', 3400.00, 4),
(gen_random_uuid(), 'BUILDER', 'Монтажні', 'Монолітні стіни', 'WORK', 'M3', 2350.00, 4),
(gen_random_uuid(), 'BUILDER', 'Монтажні', 'Виготовлення монолітних колон', 'WORK', 'M3', 3050.00, 4),
(gen_random_uuid(), 'BUILDER', 'Фундамент', 'Буронабивні палі буріння монтаж каркасу заливка', 'WORK', 'M3', 4250.00, 4),
(gen_random_uuid(), 'BUILDER', 'Покрівля', 'Монтаж керамічної черепиці', 'WORK', 'M2', 410.00, 4),
(gen_random_uuid(), 'BUILDER', 'Покрівля', 'Монтаж і облаштування єндов', 'WORK', 'LINEAR_METER', 525.00, 4),
(gen_random_uuid(), 'BUILDER', 'Покрівля', 'Монтаж фартухів димохідних труб', 'WORK', 'PIECE', 3250.00, 4),
(gen_random_uuid(), 'PLUMBING', 'Каналізація', 'Заміна трійника в стояку каналізації', 'WORK', 'PIECE', 1100.00, 4),
(gen_random_uuid(), 'DRYWALL', 'Стіни', 'Монтаж гіпсокартону на клей', 'WORK', 'M2', 280.00, 4),
(gen_random_uuid(), 'DRYWALL', 'Стіни', 'Монтаж акустичної мембрани', 'WORK', 'M2', 100.00, 4),
(gen_random_uuid(), 'DRYWALL', 'Інше', 'Фрезерування гіпсокартону', 'WORK', 'LINEAR_METER', 100.00, 4),
(gen_random_uuid(), 'DRYWALL', 'Конструкції', 'Монтаж каркасу посиленим профілем Walraven TECE', 'WORK', 'LINEAR_METER', 400.00, 4);
