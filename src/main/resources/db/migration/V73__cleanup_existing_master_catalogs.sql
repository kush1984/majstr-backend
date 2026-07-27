-- Carry the default-catalog cleanup into the catalogs masters already hold.
--
-- V71/V72 only changed what a NEW copy receives. Anyone seeded after the tetris import already has
-- the duplicated positions and the trade-name categories in their own catalog_items, which is what
-- they actually see in the app.
--
-- CREATED ESTIMATES ARE NOT TOUCHED, and cannot be: estimate_items hold their own name, unit and
-- price (copied at the time the line was added) and no foreign key points at catalog_items. An
-- estimate written last month keeps every figure it was written with.
--
-- Within a duplicate group the row with the highest price is kept — the same rule V49 used when it
-- collapsed duplicates and added the unique index. So a price a master raised themselves survives;
-- for rows nobody touched this is the tetris figure. A price a master deliberately LOWERED below
-- its duplicate would be lost with that row — the only edit this cleanup cannot preserve.
--
-- The rows are matched on `type` + name and NOT on trade or unit, because a position's identity
-- here is exactly what ux_catalog_items_owner_name_type_unit enforces: owner, name, type, unit.
-- Trade is not part of it and genuinely differs between the two copies of one position — V30
-- back-filled trade on rows that already existed by mapping category -> trade and left the
-- ambiguous ones for V33 to set to OTHER, so the older wording often carries OTHER while its
-- tetris twin carries the real trade. Filtering on trade made the first version of this migration
-- see only one row of a pair: it deleted nothing, then renamed that row onto the name the other one
-- already held, and Postgres rejected it. Unit is left out for the same reason — «Монтаж котельної»
-- exists as SET in the old row and PIECE in the tetris one — and is set to the surviving value
-- along with the name.
--
-- Where the two copies disagree on trade, the row with a real trade wins over the OTHER catch-all.
--
-- Categories are only re-filed while they still carry the value the seed gave them. Anything a
-- master re-filed by hand stays where they put it.

-- ---------------------------------------------------------------------------------------------
-- 1. Duplicate positions: keep one row per group per owner.
-- ---------------------------------------------------------------------------------------------
-- BUILDER: Гідроізоляція покрівлі мастика і євроруберойд
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'гідроізоляція покрівлі мастика і євроруберойд') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('гідроізоляція покрівлі мастика і євроруберойд', 'гідроізоляція покрівлі мастика і єврорубероид'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Гідроізоляція покрівлі мастика і євроруберойд', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('гідроізоляція покрівлі мастика і євроруберойд', 'гідроізоляція покрівлі мастика і єврорубероид')
   AND (name <> 'Гідроізоляція покрівлі мастика і євроруберойд' OR unit <> 'M2');

-- BUILDER: Прокладка комунікацій
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'прокладка комунікацій') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('прокладка комунікацій', 'прокладка коммунікацій'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Прокладка комунікацій', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('прокладка комунікацій', 'прокладка коммунікацій')
   AND (name <> 'Прокладка комунікацій' OR unit <> 'LINEAR_METER');

-- BUILDER: Установка закладних під комунікації
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка закладних під комунікації') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка закладних під комунікації', 'установка закладних під коммунікації'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка закладних під комунікації', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка закладних під комунікації', 'установка закладних під коммунікації')
   AND (name <> 'Установка закладних під комунікації' OR unit <> 'PIECE');

-- PLUMBING: Врізка в стояк водопроводу
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'врізка в стояк водопроводу') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('врізка в стояк водопроводу', 'врізка в стояк водопровода'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Врізка в стояк водопроводу', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('врізка в стояк водопроводу', 'врізка в стояк водопровода')
   AND (name <> 'Врізка в стояк водопроводу' OR unit <> 'PIECE');

-- PLUMBING: Прокладка труб каналізації
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'прокладка труб каналізації') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('прокладка труб каналізації', 'прокладка труб (каналізація)'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Прокладка труб каналізації', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('прокладка труб каналізації', 'прокладка труб (каналізація)')
   AND (name <> 'Прокладка труб каналізації' OR unit <> 'LINEAR_METER');

-- PLUMBING: Установка ванни простої
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка ванни простої') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка ванни простої', 'установки ванни простої'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка ванни простої', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка ванни простої', 'установки ванни простої')
   AND (name <> 'Установка ванни простої' OR unit <> 'PIECE');

-- FLOORING: Монтаж алюмінієвого плінтуса
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'монтаж алюмінієвого плінтуса') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('монтаж алюмінієвого плінтуса', 'монтаж плінтуса алюмінієвого'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Монтаж алюмінієвого плінтуса', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('монтаж алюмінієвого плінтуса', 'монтаж плінтуса алюмінієвого')
   AND (name <> 'Монтаж алюмінієвого плінтуса' OR unit <> 'LINEAR_METER');

-- BUILDER: Кладка стіни з керамоблока 2НФ 25х12
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'кладка стіни з керамоблока 2нф 25х12') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('кладка стіни з керамоблока 2нф 25х12', 'кладка стіни з керамоблока 2нф 25*12'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Кладка стіни з керамоблока 2НФ 25х12', unit = 'M3'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('кладка стіни з керамоблока 2нф 25х12', 'кладка стіни з керамоблока 2нф 25*12')
   AND (name <> 'Кладка стіни з керамоблока 2НФ 25х12' OR unit <> 'M3');

-- BUILDER: Кладка стіни з керамоблока 38х60х20
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'кладка стіни з керамоблока 38х60х20') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('кладка стіни з керамоблока 38х60х20', 'кладка стіни з керамоблока 38*60*20'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Кладка стіни з керамоблока 38х60х20', unit = 'M3'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('кладка стіни з керамоблока 38х60х20', 'кладка стіни з керамоблока 38*60*20')
   AND (name <> 'Кладка стіни з керамоблока 38х60х20' OR unit <> 'M3');

-- ELECTRICAL: Штроблення під електрокабель 20х20 мм (в бетоні)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'штроблення під електрокабель 20х20 мм (в бетоні)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 20х20 мм (в бетоні)', 'штроблення під електрокабель 20*20 мм (в бетоні)', 'штроблення під електрокабель 20х20 в бетоні'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Штроблення під електрокабель 20х20 мм (в бетоні)', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 20х20 мм (в бетоні)', 'штроблення під електрокабель 20*20 мм (в бетоні)', 'штроблення під електрокабель 20х20 в бетоні')
   AND (name <> 'Штроблення під електрокабель 20х20 мм (в бетоні)' OR unit <> 'LINEAR_METER');

-- ELECTRICAL: Штроблення під електрокабель 20х20 мм (в цеглі)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'штроблення під електрокабель 20х20 мм (в цеглі)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 20х20 мм (в цеглі)', 'штроблення під електрокабель 20*20 мм (в цеглі)', 'штроблення під електрокабель 20х20 в цеглі'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Штроблення під електрокабель 20х20 мм (в цеглі)', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 20х20 мм (в цеглі)', 'штроблення під електрокабель 20*20 мм (в цеглі)', 'штроблення під електрокабель 20х20 в цеглі')
   AND (name <> 'Штроблення під електрокабель 20х20 мм (в цеглі)' OR unit <> 'LINEAR_METER');

-- ELECTRICAL: Штроблення під електрокабель 20х40 мм (в цеглі)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'штроблення під електрокабель 20х40 мм (в цеглі)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 20х40 мм (в цеглі)', 'штроблення під електрокабель 20*40 мм (в цеглі)', 'штроблення під електрокабель 20х40 в цеглі'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Штроблення під електрокабель 20х40 мм (в цеглі)', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 20х40 мм (в цеглі)', 'штроблення під електрокабель 20*40 мм (в цеглі)', 'штроблення під електрокабель 20х40 в цеглі')
   AND (name <> 'Штроблення під електрокабель 20х40 мм (в цеглі)' OR unit <> 'LINEAR_METER');

-- ELECTRICAL: Штроблення під електрокабель 20х60 мм (в цеглі)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'штроблення під електрокабель 20х60 мм (в цеглі)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 20х60 мм (в цеглі)', 'штроблення під електрокабель 20*60 мм (в цеглі)', 'штроблення під електрокабель 20х60 в цеглі'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Штроблення під електрокабель 20х60 мм (в цеглі)', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 20х60 мм (в цеглі)', 'штроблення під електрокабель 20*60 мм (в цеглі)', 'штроблення під електрокабель 20х60 в цеглі')
   AND (name <> 'Штроблення під електрокабель 20х60 мм (в цеглі)' OR unit <> 'LINEAR_METER');

-- ELECTRICAL: Штроблення під електрокабель 40х20 мм (в бетоні)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'штроблення під електрокабель 40х20 мм (в бетоні)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 40х20 мм (в бетоні)', 'штроблення під електрокабель 40*20 мм (в бетоні)', 'штроблення під електрокабель 40х20 в бетоні'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Штроблення під електрокабель 40х20 мм (в бетоні)', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 40х20 мм (в бетоні)', 'штроблення під електрокабель 40*20 мм (в бетоні)', 'штроблення під електрокабель 40х20 в бетоні')
   AND (name <> 'Штроблення під електрокабель 40х20 мм (в бетоні)' OR unit <> 'LINEAR_METER');

-- ELECTRICAL: Штроблення під електрокабель 60х20 мм (в бетоні)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'штроблення під електрокабель 60х20 мм (в бетоні)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 60х20 мм (в бетоні)', 'штроблення під електрокабель 60*20 мм (в бетоні)', 'штроблення під електрокабель 60х20 в бетоні'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Штроблення під електрокабель 60х20 мм (в бетоні)', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення під електрокабель 60х20 мм (в бетоні)', 'штроблення під електрокабель 60*20 мм (в бетоні)', 'штроблення під електрокабель 60х20 в бетоні')
   AND (name <> 'Штроблення під електрокабель 60х20 мм (в бетоні)' OR unit <> 'LINEAR_METER');

-- BUILDER: Установка дерев'яного або металевого стовпа паркану
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка дерев''яного або металевого стовпа паркану') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка дерев''яного або металевого стовпа паркану', 'установка дерев''яного металевого стовпа паркану'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка дерев''яного або металевого стовпа паркану', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка дерев''яного або металевого стовпа паркану', 'установка дерев''яного металевого стовпа паркану')
   AND (name <> 'Установка дерев''яного або металевого стовпа паркану' OR unit <> 'PIECE');

-- BUILDER: Копка траншей глибиною 0,5 м і шириною 35см
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'копка траншей глибиною 0,5 м і шириною 35см') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('копка траншей глибиною 0,5 м і шириною 35см', 'копка траншей глибиною 0.5м шириною 35см'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Копка траншей глибиною 0,5 м і шириною 35см', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('копка траншей глибиною 0,5 м і шириною 35см', 'копка траншей глибиною 0.5м шириною 35см')
   AND (name <> 'Копка траншей глибиною 0,5 м і шириною 35см' OR unit <> 'LINEAR_METER');

-- BUILDER: Монтаж шлакоблоків/євроблоків для стовбчика
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'монтаж шлакоблоків/євроблоків для стовбчика') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('монтаж шлакоблоків/євроблоків для стовбчика', 'монтаж шлакоблоків для стовбчика'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Монтаж шлакоблоків/євроблоків для стовбчика', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('монтаж шлакоблоків/євроблоків для стовбчика', 'монтаж шлакоблоків для стовбчика')
   AND (name <> 'Монтаж шлакоблоків/євроблоків для стовбчика' OR unit <> 'PIECE');

-- BUILDER: Планування ділянки і нівелювання - від
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'планування ділянки і нівелювання - від') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('планування ділянки і нівелювання - від', 'планування ділянки і нівелювання'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Планування ділянки і нівелювання - від', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('планування ділянки і нівелювання - від', 'планування ділянки і нівелювання')
   AND (name <> 'Планування ділянки і нівелювання - від' OR unit <> 'PIECE');

-- BUILDER: Укладання бруківки типу "Старе місто" на гарцовку
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання бруківки типу "старе місто" на гарцовку') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання бруківки типу "старе місто" на гарцовку', 'укладання бруківки старе місто на гарцовку'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання бруківки типу "Старе місто" на гарцовку', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання бруківки типу "старе місто" на гарцовку', 'укладання бруківки старе місто на гарцовку')
   AND (name <> 'Укладання бруківки типу "Старе місто" на гарцовку' OR unit <> 'M2');

-- BUILDER: Кладка стін з газоблоку або піноблоку 200 мм
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'кладка стін з газоблоку або піноблоку 200 мм') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('кладка стін з газоблоку або піноблоку 200 мм', 'кладка стін з газоблоку піноблоку 200 мм'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Кладка стін з газоблоку або піноблоку 200 мм', unit = 'M3'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('кладка стін з газоблоку або піноблоку 200 мм', 'кладка стін з газоблоку піноблоку 200 мм')
   AND (name <> 'Кладка стін з газоблоку або піноблоку 200 мм' OR unit <> 'M3');

-- BUILDER: Кладка стін з газоблоку або піноблоку 300 мм
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'кладка стін з газоблоку або піноблоку 300 мм') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('кладка стін з газоблоку або піноблоку 300 мм', 'кладка стін з газоблоку піноблоку 300 мм'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Кладка стін з газоблоку або піноблоку 300 мм', unit = 'M3'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('кладка стін з газоблоку або піноблоку 300 мм', 'кладка стін з газоблоку піноблоку 300 мм')
   AND (name <> 'Кладка стін з газоблоку або піноблоку 300 мм' OR unit <> 'M3');

-- BUILDER: Підшива даху сайдінгом, профнастилом
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'підшива даху сайдінгом, профнастилом') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('підшива даху сайдінгом, профнастилом', 'підшива даху сайдінгом профнастилом', 'підшива даху сайдінгом, профнасілом'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Підшива даху сайдінгом, профнастилом', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('підшива даху сайдінгом, профнастилом', 'підшива даху сайдінгом профнастилом', 'підшива даху сайдінгом, профнасілом')
   AND (name <> 'Підшива даху сайдінгом, профнастилом' OR unit <> 'LINEAR_METER');

-- ELECTRICAL: Установка тимчасового освітлення та розеток (на час ремонту)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка тимчасового освітлення та розеток (на час ремонту)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка тимчасового освітлення та розеток (на час ремонту)', 'установка тимчасового освітлення розеток на час ремонту'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка тимчасового освітлення та розеток (на час ремонту)', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка тимчасового освітлення та розеток (на час ремонту)', 'установка тимчасового освітлення розеток на час ремонту')
   AND (name <> 'Установка тимчасового освітлення та розеток (на час ремонту)' OR unit <> 'PIECE');

-- ELECTRICAL: Установка автомата перекидного двополюсного
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка автомата перекидного двополюсного') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка автомата перекидного двополюсного', 'установка автомата перекидного двухполюсного'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка автомата перекидного двополюсного', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка автомата перекидного двополюсного', 'установка автомата перекидного двухполюсного')
   AND (name <> 'Установка автомата перекидного двополюсного' OR unit <> 'PIECE');

-- ELECTRICAL: Монтаж люка-ревізії під ТВ, інтернет
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'монтаж люка-ревізії під тв, інтернет') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('монтаж люка-ревізії під тв, інтернет', 'монтаж люка-ревізії під тв інтернет', 'монтаж люка-ревізія під тв, інтернет'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Монтаж люка-ревізії під ТВ, інтернет', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('монтаж люка-ревізії під тв, інтернет', 'монтаж люка-ревізії під тв інтернет', 'монтаж люка-ревізія під тв, інтернет')
   AND (name <> 'Монтаж люка-ревізії під ТВ, інтернет' OR unit <> 'PIECE');

-- ELECTRICAL: Установка та підключення електроплити, варочної поверхні
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка та підключення електроплити, варочної поверхні') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка та підключення електроплити, варочної поверхні', 'установка та підключення електроплити варочної'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка та підключення електроплити, варочної поверхні', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка та підключення електроплити, варочної поверхні', 'установка та підключення електроплити варочної')
   AND (name <> 'Установка та підключення електроплити, варочної поверхні' OR unit <> 'PIECE');

-- FLOORING: Монтаж та виготовлення ніші під плінтус прихованого монтажу
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'монтаж та виготовлення ніші під плінтус прихованого монтажу') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('монтаж та виготовлення ніші під плінтус прихованого монтажу', 'монтаж та виготовлення ніші під плінтус прихованого'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Монтаж та виготовлення ніші під плінтус прихованого монтажу', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('монтаж та виготовлення ніші під плінтус прихованого монтажу', 'монтаж та виготовлення ніші під плінтус прихованого')
   AND (name <> 'Монтаж та виготовлення ніші під плінтус прихованого монтажу' OR unit <> 'LINEAR_METER');

-- FLOORING: Підготовка поверхні (очищення і т.п.)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'підготовка поверхні (очищення і т.п.)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('підготовка поверхні (очищення і т.п.)', 'підготовка поверхні очищення'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Підготовка поверхні (очищення і т.п.)', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('підготовка поверхні (очищення і т.п.)', 'підготовка поверхні очищення')
   AND (name <> 'Підготовка поверхні (очищення і т.п.)' OR unit <> 'M2');

-- PAINTER: Установка перфорованих кутів на укоси, кути
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка перфорованих кутів на укоси, кути') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка перфорованих кутів на укоси, кути', 'установка перфорованих кутів на укоси'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка перфорованих кутів на укоси, кути', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка перфорованих кутів на укоси, кути', 'установка перфорованих кутів на укоси')
   AND (name <> 'Установка перфорованих кутів на укоси, кути' OR unit <> 'LINEAR_METER');

-- PAINTER: Фарбування труб (газової, опалення і ін.)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'фарбування труб (газової, опалення і ін.)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('фарбування труб (газової, опалення і ін.)', 'фарбування труб газової опалення'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Фарбування труб (газової, опалення і ін.)', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('фарбування труб (газової, опалення і ін.)', 'фарбування труб газової опалення')
   AND (name <> 'Фарбування труб (газової, опалення і ін.)' OR unit <> 'LINEAR_METER');

-- PLUMBING: Установка ніжок під радіатор із нижнім підключенням
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка ніжок під радіатор із нижнім підключенням') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка ніжок під радіатор із нижнім підключенням', 'установка ніжок під радіатор з нижнім підключенням'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка ніжок під радіатор із нижнім підключенням', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка ніжок під радіатор із нижнім підключенням', 'установка ніжок під радіатор з нижнім підключенням')
   AND (name <> 'Установка ніжок під радіатор із нижнім підключенням' OR unit <> 'PIECE');

-- PLUMBING: Установка змішувача прихованого типу для душа (біде)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка змішувача прихованого типу для душа (біде)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка змішувача прихованого типу для душа (біде)', 'установка змішувача прихованого типу для душа'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка змішувача прихованого типу для душа (біде)', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка змішувача прихованого типу для душа (біде)', 'установка змішувача прихованого типу для душа')
   AND (name <> 'Установка змішувача прихованого типу для душа (біде)' OR unit <> 'PIECE');

-- PLUMBING: Обв'язка котла (один котел - 4 точки) - від
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'обв''язка котла (один котел - 4 точки) - від') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('обв''язка котла (один котел - 4 точки) - від', 'обв''язка котла один котел 4 точки'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Обв''язка котла (один котел - 4 точки) - від', unit = 'POINT'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('обв''язка котла (один котел - 4 точки) - від', 'обв''язка котла один котел 4 точки')
   AND (name <> 'Обв''язка котла (один котел - 4 точки) - від' OR unit <> 'POINT');

-- PLUMBING: Установка ванни із гідромасажем
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка ванни із гідромасажем') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка ванни із гідромасажем', 'установка ванни з гідромасажем'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка ванни із гідромасажем', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка ванни із гідромасажем', 'установка ванни з гідромасажем')
   AND (name <> 'Установка ванни із гідромасажем' OR unit <> 'PIECE');

-- PLUMBING: Зняття і встановлення радіатора (під час ремонту - один раз)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'зняття і встановлення радіатора (під час ремонту - один раз)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('зняття і встановлення радіатора (під час ремонту - один раз)', 'зняття і встановлення радіатора під час ремонту'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Зняття і встановлення радіатора (під час ремонту - один раз)', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('зняття і встановлення радіатора (під час ремонту - один раз)', 'зняття і встановлення радіатора під час ремонту')
   AND (name <> 'Зняття і встановлення радіатора (під час ремонту - один раз)' OR unit <> 'PIECE');

-- PLUMBING: Прокладання труб з ізоляцією
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'прокладання труб з ізоляцією') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('прокладання труб з ізоляцією', 'прокладання труб з ізоляцією (вода)'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Прокладання труб з ізоляцією', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('прокладання труб з ізоляцією', 'прокладання труб з ізоляцією (вода)')
   AND (name <> 'Прокладання труб з ізоляцією' OR unit <> 'LINEAR_METER');

-- PLUMBING: Установка люка-ревізії простого
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'установка люка-ревізії простого') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('установка люка-ревізії простого', 'установка люка-ревізія (простого)'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Установка люка-ревізії простого', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('установка люка-ревізії простого', 'установка люка-ревізія (простого)')
   AND (name <> 'Установка люка-ревізії простого' OR unit <> 'PIECE');

-- PLUMBING: Штроблення в цеглі, газобетоні
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'штроблення в цеглі, газобетоні') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення в цеглі, газобетоні', 'штроблення в цеглі газобетоні (вода)'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Штроблення в цеглі, газобетоні', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('штроблення в цеглі, газобетоні', 'штроблення в цеглі газобетоні (вода)')
   AND (name <> 'Штроблення в цеглі, газобетоні' OR unit <> 'LINEAR_METER');

-- PLUMBING: Прокладання труб на хомутах
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'прокладання труб на хомутах') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('прокладання труб на хомутах', 'прокладання труб на хомутах (вода)'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Прокладання труб на хомутах', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('прокладання труб на хомутах', 'прокладання труб на хомутах (вода)')
   AND (name <> 'Прокладання труб на хомутах' OR unit <> 'LINEAR_METER');

-- TILING: Виготовлення та монтаж кнопки інсталяції з плитки
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'виготовлення та монтаж кнопки інсталяції з плитки') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('виготовлення та монтаж кнопки інсталяції з плитки', 'виготовлення та монтаж кнопка інсталяції з плитки'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Виготовлення та монтаж кнопки інсталяції з плитки', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('виготовлення та монтаж кнопки інсталяції з плитки', 'виготовлення та монтаж кнопка інсталяції з плитки')
   AND (name <> 'Виготовлення та монтаж кнопки інсталяції з плитки' OR unit <> 'PIECE');

-- TILING: Порізка плитки під кутом в 45 градусів
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'порізка плитки під кутом в 45 градусів') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('порізка плитки під кутом в 45 градусів', 'порізка плитки під кутом 45 градусів'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Порізка плитки під кутом в 45 градусів', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('порізка плитки під кутом в 45 градусів', 'порізка плитки під кутом 45 градусів')
   AND (name <> 'Порізка плитки під кутом в 45 градусів' OR unit <> 'LINEAR_METER');

-- TILING: Піддон з плитки в рівень з підлогою (комплекс робіт)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'піддон з плитки в рівень з підлогою (комплекс робіт)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('піддон з плитки в рівень з підлогою (комплекс робіт)', 'піддон з плитки в рівень з підлогою комплекс'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Піддон з плитки в рівень з підлогою (комплекс робіт)', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('піддон з плитки в рівень з підлогою (комплекс робіт)', 'піддон з плитки в рівень з підлогою комплекс')
   AND (name <> 'Піддон з плитки в рівень з підлогою (комплекс робіт)' OR unit <> 'PIECE');

-- TILING: Піддон з плитки з бортиком (комплекс робіт)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'піддон з плитки з бортиком (комплекс робіт)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('піддон з плитки з бортиком (комплекс робіт)', 'піддон з плитки з бортиком комплекс'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Піддон з плитки з бортиком (комплекс робіт)', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('піддон з плитки з бортиком (комплекс робіт)', 'піддон з плитки з бортиком комплекс')
   AND (name <> 'Піддон з плитки з бортиком (комплекс робіт)' OR unit <> 'PIECE');

-- TILING: Укладка та порізка плитки на підступок прямі (без кута 45)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладка та порізка плитки на підступок прямі (без кута 45)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладка та порізка плитки на підступок прямі (без кута 45)', 'укладка плитки на підступок прямі без кута 45'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладка та порізка плитки на підступок прямі (без кута 45)', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладка та порізка плитки на підступок прямі (без кута 45)', 'укладка плитки на підступок прямі без кута 45')
   AND (name <> 'Укладка та порізка плитки на підступок прямі (без кута 45)' OR unit <> 'LINEAR_METER');

-- TILING: Укладка та порізка плитки на сходи прямі (без кута 45)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладка та порізка плитки на сходи прямі (без кута 45)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладка та порізка плитки на сходи прямі (без кута 45)', 'укладка плитки на сходи прямі без кута 45'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладка та порізка плитки на сходи прямі (без кута 45)', unit = 'LINEAR_METER'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладка та порізка плитки на сходи прямі (без кута 45)', 'укладка плитки на сходи прямі без кута 45')
   AND (name <> 'Укладка та порізка плитки на сходи прямі (без кута 45)' OR unit <> 'LINEAR_METER');

-- TILING: Укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)', 'укладання декоративної плитки під цеглу камінь'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)', 'укладання декоративної плитки під цеглу камінь')
   AND (name <> 'Укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)' OR unit <> 'M2');

-- TILING: Укладання плитки по діагоналі (+ до ціни укладання)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки по діагоналі (+ до ціни укладання)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки по діагоналі (+ до ціни укладання)', 'укладання плитки по діагоналі надбавка'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки по діагоналі (+ до ціни укладання)', unit = 'PERCENT'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки по діагоналі (+ до ціни укладання)', 'укладання плитки по діагоналі надбавка')
   AND (name <> 'Укладання плитки по діагоналі (+ до ціни укладання)' OR unit <> 'PERCENT');

-- TILING: Виготовлення поличок з плитки в три сторони (шириною від 30см)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'виготовлення поличок з плитки в три сторони (шириною від 30см)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('виготовлення поличок з плитки в три сторони (шириною від 30см)', 'виготовлення поличок з плитки три сторони від 30см'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Виготовлення поличок з плитки в три сторони (шириною від 30см)', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('виготовлення поличок з плитки в три сторони (шириною від 30см)', 'виготовлення поличок з плитки три сторони від 30см')
   AND (name <> 'Виготовлення поличок з плитки в три сторони (шириною від 30см)' OR unit <> 'PIECE');

-- TILING: Виготовлення поличок з плитки в три сторони (шириною до 30см)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'виготовлення поличок з плитки в три сторони (шириною до 30см)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('виготовлення поличок з плитки в три сторони (шириною до 30см)', 'виготовлення поличок з плитки три сторони до 30см'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Виготовлення поличок з плитки в три сторони (шириною до 30см)', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('виготовлення поличок з плитки в три сторони (шириною до 30см)', 'виготовлення поличок з плитки три сторони до 30см')
   AND (name <> 'Виготовлення поличок з плитки в три сторони (шириною до 30см)' OR unit <> 'PIECE');

-- TILING: Виготовлення поличок з плитки в чотири сторони (шириною від 30см)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'виготовлення поличок з плитки в чотири сторони (шириною від 30см)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('виготовлення поличок з плитки в чотири сторони (шириною від 30см)', 'виготовлення поличок з плитки чотири сторони від 30см'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Виготовлення поличок з плитки в чотири сторони (шириною від 30см)', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('виготовлення поличок з плитки в чотири сторони (шириною від 30см)', 'виготовлення поличок з плитки чотири сторони від 30см')
   AND (name <> 'Виготовлення поличок з плитки в чотири сторони (шириною від 30см)' OR unit <> 'PIECE');

-- TILING: Виготовлення поличок з плитки в чотири сторони (шириною до 30см)
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'виготовлення поличок з плитки в чотири сторони (шириною до 30см)') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('виготовлення поличок з плитки в чотири сторони (шириною до 30см)', 'виготовлення поличок з плитки чотири сторони до 30см'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Виготовлення поличок з плитки в чотири сторони (шириною до 30см)', unit = 'PIECE'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('виготовлення поличок з плитки в чотири сторони (шириною до 30см)', 'виготовлення поличок з плитки чотири сторони до 30см')
   AND (name <> 'Виготовлення поличок з плитки в чотири сторони (шириною до 30см)' OR unit <> 'PIECE');

-- TILING: Укладання плитки 1000х1000
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 1000х1000') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1000х1000', 'укладання плитки 1000*1000'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 1000х1000', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1000х1000', 'укладання плитки 1000*1000')
   AND (name <> 'Укладання плитки 1000х1000' OR unit <> 'M2');

-- TILING: Укладання плитки 1200х1200
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 1200х1200') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х1200', 'укладання плитки 1200*1200'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 1200х1200', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х1200', 'укладання плитки 1200*1200')
   AND (name <> 'Укладання плитки 1200х1200' OR unit <> 'M2');

-- TILING: Укладання плитки 1200х200
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 1200х200') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х200', 'укладання плитки 1200*200'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 1200х200', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х200', 'укладання плитки 1200*200')
   AND (name <> 'Укладання плитки 1200х200' OR unit <> 'M2');

-- TILING: Укладання плитки 1200х200 ялинкою
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 1200х200 ялинкою') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х200 ялинкою', 'укладання плитки 1200*200 ялинкою'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 1200х200 ялинкою', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х200 ялинкою', 'укладання плитки 1200*200 ялинкою')
   AND (name <> 'Укладання плитки 1200х200 ялинкою' OR unit <> 'M2');

-- TILING: Укладання плитки 1200х2500
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 1200х2500') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х2500', 'укладання плитки 1200*2500'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 1200х2500', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х2500', 'укладання плитки 1200*2500')
   AND (name <> 'Укладання плитки 1200х2500' OR unit <> 'M2');

-- TILING: Укладання плитки 1200х3000
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 1200х3000') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х3000', 'укладання плитки 1200*3000'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 1200х3000', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х3000', 'укладання плитки 1200*3000')
   AND (name <> 'Укладання плитки 1200х3000' OR unit <> 'M2');

-- TILING: Укладання плитки 1200х600
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 1200х600') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х600', 'укладання плитки 1200*600'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 1200х600', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1200х600', 'укладання плитки 1200*600')
   AND (name <> 'Укладання плитки 1200х600' OR unit <> 'M2');

-- TILING: Укладання плитки 1500х1500
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 1500х1500') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1500х1500', 'укладання плитки 1500*1500'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 1500х1500', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1500х1500', 'укладання плитки 1500*1500')
   AND (name <> 'Укладання плитки 1500х1500' OR unit <> 'M2');

-- TILING: Укладання плитки 1500х750
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 1500х750') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1500х750', 'укладання плитки 1500*750'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 1500х750', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 1500х750', 'укладання плитки 1500*750')
   AND (name <> 'Укладання плитки 1500х750' OR unit <> 'M2');

-- TILING: Укладання плитки 600х600
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 600х600') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 600х600', 'укладання плитки 600*600'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 600х600', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 600х600', 'укладання плитки 600*600')
   AND (name <> 'Укладання плитки 600х600' OR unit <> 'M2');

-- TILING: Укладання плитки 800х800
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 800х800') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 800х800', 'укладання плитки 800*800'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 800х800', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 800х800', 'укладання плитки 800*800')
   AND (name <> 'Укладання плитки 800х800' OR unit <> 'M2');

-- TILING: Укладання плитки 900х900
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY owner_id
           ORDER BY default_price DESC,
                    (trade <> 'OTHER') DESC,
                    (lower(trim(name)) = 'укладання плитки 900х900') DESC,
                    id) AS rn
  FROM catalog_items
  WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 900х900', 'укладання плитки 900*900'))
DELETE FROM catalog_items c USING ranked r WHERE c.id = r.id AND r.rn > 1;
UPDATE catalog_items SET name = 'Укладання плитки 900х900', unit = 'M2'
 WHERE type = 'WORK' AND lower(trim(name)) IN ('укладання плитки 900х900', 'укладання плитки 900*900')
   AND (name <> 'Укладання плитки 900х900' OR unit <> 'M2');

-- ---------------------------------------------------------------------------------------------
-- 2. Dimension separator, so search behaves the same everywhere.
-- ---------------------------------------------------------------------------------------------
-- Guarded against the collision that broke the dedupe above: rename only where the «х» spelling
-- is not already taken for that owner/type/unit. A row that cannot be renamed keeps its old name
-- rather than failing the deploy — the warning at the end reports it.
UPDATE catalog_items ci SET name = replace(ci.name, '*', 'х')
WHERE ci.name LIKE '%*%'
  AND NOT EXISTS (
    SELECT 1 FROM catalog_items o
    WHERE o.owner_id = ci.owner_id AND o.type = ci.type AND o.unit = ci.unit
      AND lower(trim(o.name)) = lower(trim(replace(ci.name, '*', 'х'))));

-- ---------------------------------------------------------------------------------------------
-- 3. Categories: take whatever the cleaned default catalog now says.
--
-- Listing the moves by position name looked simpler but was wrong: in the default catalog the
-- surviving tile row was the older one filed under «Укладання», while in a master's catalog the
-- surviving row is the higher-priced tetris one still filed under «ПЛИТОЧНІ РОБОТИ» — so ten
-- positions stayed behind in a bucket that no longer exists anywhere else. Syncing from
-- catalog_templates instead cannot drift from it, whichever row won the dedupe.
--
-- The IN list is exactly the set of category values V70/V72 moved away from, which is what keeps
-- this off a master's own filing: a position they re-filed by hand no longer carries any of these
-- values, so the statement does not see it.
-- Joined on name + type, NOT on trade, for the same reason as the dedupe above: the master's row
-- and the default row disagree on trade often enough (V30/V33 left old rows on OTHER), and a
-- trade-matched join would simply skip those — leaving the position in a bucket nothing else uses.
-- A handful of names exist under two trades since V70 added them there; they carry the same
-- category, and DISTINCT ON keeps the choice deterministic regardless.
UPDATE catalog_items ci SET category = t.category
FROM (
  SELECT DISTINCT ON (lower(trim(name)), type)
         lower(trim(name)) AS k, type, category
  FROM catalog_templates
  ORDER BY lower(trim(name)), type, category
) t
WHERE lower(trim(ci.name)) = t.k
  AND ci.type = t.type
  AND ci.category <> t.category
  AND ci.category IN ('САНТЕХНІКА', 'ЕЛЕКТРИКА', 'ПЛИТОЧНІ РОБОТИ', 'ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ', 'КЛАДКА', 'ПАРКАН', 'ФУНДАМЕНТ', 'ПІДЛОГА', 'СТЯЖКА', 'ДЕКОРАТИВНА ШТУКАТУРКА', 'ШТУКАТУРКА', 'ЗЕМЛЯНІ РОБОТИ', 'БЛАГОУСТРІЙ ТЕРИТОРІЇ', 'КЛАДОЧНІ РОБОТИ', 'ПОКРІВЕЛЬНІ РОБОТИ', 'ЗВАРЮВАЛЬНІ РОБОТИ', 'ЗВУКОІЗОЛЯЦІЯ', 'ФАСАДНІ РОБОТИ');

-- Nothing may be left holding two rows for one position, and no seeded row may be left in a
-- category the app no longer groups anything else under.
DO $$
DECLARE dup int; left_over int; starred int;
BEGIN
  SELECT count(*) INTO dup FROM (
    SELECT owner_id, lower(trim(name)), type, unit FROM catalog_items
    GROUP BY 1,2,3,4 HAVING count(*) > 1) s;
  IF dup > 0 THEN
    RAISE EXCEPTION 'у майстрів лишилось % дублів', dup;
  END IF;
  SELECT count(*) INTO left_over FROM catalog_items WHERE category IN ('САНТЕХНІКА', 'ЕЛЕКТРИКА', 'ПЛИТОЧНІ РОБОТИ', 'ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ');
  IF left_over > 0 THEN
    RAISE WARNING '% позицій майстрів лишились у старих категоріях (перейменовані вручну)',
                  left_over;
  END IF;
  SELECT count(*) INTO starred FROM catalog_items WHERE name LIKE '%*%';
  IF starred > 0 THEN
    RAISE WARNING '% позицій майстрів лишились з роздільником * (назва з «х» уже зайнята)',
                  starred;
  END IF;
END $$;
