-- Break up the four tetris categories that only repeat the trade name.
--
-- The catalog screen groups by category, so «ЕЛЕКТРИКА» inside the electrical trade is not a
-- grouping at all — it is 34 positions with nowhere to be, sitting next to the real buckets
-- (Розетки, Щит, Освітлення, Свердління) that already existed. Same for САНТЕХНІКА (35),
-- ПЛИТОЧНІ РОБОТИ (19) and ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ (23): 107 positions in total.
--
-- Every one moves into an existing bucket of its own trade, except «Штроблення», added because
-- channel-cutting is a distinct job (7 electrical + 2 plumbing positions) with no bucket that fit.
--
-- Category is display-only grouping — nothing matches on it, which is what makes this safe to
-- redo. The statements are per position rather than per keyword so the mapping is reviewable.

UPDATE catalog_templates SET category = 'Підготовка'
 WHERE trade = 'ELECTRICAL' AND name = 'Перевірка цілісності електричного кола (продзвонка), будинки - індивідуальний підхід';
UPDATE catalog_templates SET category = 'Підготовка'
 WHERE trade = 'ELECTRICAL' AND name = 'Перевірка цілісності електричного кола (продзвонка), квартири';
UPDATE catalog_templates SET category = 'Кабель'
 WHERE trade = 'ELECTRICAL' AND name = 'Прокладка закладної гофри для можливого резервного живлення';
UPDATE catalog_templates SET category = 'Кабель'
 WHERE trade = 'ELECTRICAL' AND name = 'Прокладка ретро проводу в стилі "лофт" (відкрита)';
UPDATE catalog_templates SET category = 'Свердління'
 WHERE trade = 'ELECTRICAL' AND name = 'Свердління наскрізних отворів (у бетоні), в залежності від діаметра отвору - від';
UPDATE catalog_templates SET category = 'Свердління'
 WHERE trade = 'ELECTRICAL' AND name = 'Свердління наскрізних отворів (у гіпсокартоні), в залежності від діаметра отвору - від';
UPDATE catalog_templates SET category = 'Свердління'
 WHERE trade = 'ELECTRICAL' AND name = 'Свердління наскрізних отворів (у цеглі), в залежності від діаметра отвору - від';
UPDATE catalog_templates SET category = 'Свердління'
 WHERE trade = 'ELECTRICAL' AND name = 'Свердління отвору під прокладку електрокабелю в капітальній стіні - від';
UPDATE catalog_templates SET category = 'Свердління'
 WHERE trade = 'ELECTRICAL' AND name = 'Свердління під встановлення точкового світильника в гіпсокартоні';
UPDATE catalog_templates SET category = 'Свердління'
 WHERE trade = 'ELECTRICAL' AND name = 'Свердління під встановлення точкового світильника в рейковій стелі';
UPDATE catalog_templates SET category = 'Розетки'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка накладних розетки, вимикача, розподільчої коробки';
UPDATE catalog_templates SET category = 'Щит'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка пристрою захисного відключення (ПЗВ) - двухполюсний';
UPDATE catalog_templates SET category = 'Щит'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка пристрою захисного відключення (ПЗВ) - чотирьохполюсний';
UPDATE catalog_templates SET category = 'Слаботочка'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка розетки для ТВ, розгалуджувача або підсилювача ТВ';
UPDATE catalog_templates SET category = 'Слаботочка'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка та підключення дверного дзвінка бездротового';
UPDATE catalog_templates SET category = 'Слаботочка'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка та підключення дверного дзвінка дротового';
UPDATE catalog_templates SET category = 'Розетки'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка та підключення електроплити, варочної поверхні';
UPDATE catalog_templates SET category = 'Освітлення'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка та підключення настінного світильника, бра';
UPDATE catalog_templates SET category = 'Розетки'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка та підключення промислової розетки на фасаді';
UPDATE catalog_templates SET category = 'Розетки'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка та підключення прохідних, перехресних вимикачів';
UPDATE catalog_templates SET category = 'Освітлення'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка та підключення підвісного світильника, люстри простої';
UPDATE catalog_templates SET category = 'Освітлення'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка та підключення світлодіодного світильника';
UPDATE catalog_templates SET category = 'Тепла підлога'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка та підключення терморегулятора теплої підлоги';
UPDATE catalog_templates SET category = 'Освітлення'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка та підключення трекового світильника';
UPDATE catalog_templates SET category = 'Освітлення'
 WHERE trade = 'ELECTRICAL' AND name = 'Установка тимчасового освітлення та розеток (на час ремонту)';
UPDATE catalog_templates SET category = 'Штроблення'
 WHERE trade = 'ELECTRICAL' AND name = 'Штроблення під електрокабель 20х20 мм (в бетоні)';
UPDATE catalog_templates SET category = 'Штроблення'
 WHERE trade = 'ELECTRICAL' AND name = 'Штроблення під електрокабель 20х20 мм (в цеглі)';
UPDATE catalog_templates SET category = 'Штроблення'
 WHERE trade = 'ELECTRICAL' AND name = 'Штроблення під електрокабель 20х40 мм (в цеглі)';
UPDATE catalog_templates SET category = 'Штроблення'
 WHERE trade = 'ELECTRICAL' AND name = 'Штроблення під електрокабель 20х60 мм (в цеглі)';
UPDATE catalog_templates SET category = 'Штроблення'
 WHERE trade = 'ELECTRICAL' AND name = 'Штроблення під електрокабель 40х20 мм (в бетоні)';
UPDATE catalog_templates SET category = 'Штроблення'
 WHERE trade = 'ELECTRICAL' AND name = 'Штроблення під електрокабель 50х250 мм (в цеглі)';
UPDATE catalog_templates SET category = 'Штроблення'
 WHERE trade = 'ELECTRICAL' AND name = 'Штроблення під електрокабель 60х20 мм (в бетоні)';
UPDATE catalog_templates SET category = 'Штукатурка'
 WHERE trade = 'PAINTER' AND name = 'Вирівнювання стін (зроблених не нами, за погодженням)';
UPDATE catalog_templates SET category = 'Підготовка'
 WHERE trade = 'PAINTER' AND name = 'Герметизація швів, стиків акрилом, спеціальною мастикою';
UPDATE catalog_templates SET category = 'Підготовка'
 WHERE trade = 'PAINTER' AND name = 'Грунтовка поверхонь перед шпаклівкою, фарбуванням, поклейкою тощо';
UPDATE catalog_templates SET category = 'Підготовка'
 WHERE trade = 'PAINTER' AND name = 'Дефектовка стін (зроблених не нами, за погодженням)';
UPDATE catalog_templates SET category = 'Підготовка'
 WHERE trade = 'PAINTER' AND name = 'Захист вхідних дверей картоном';
UPDATE catalog_templates SET category = 'Оздоблення'
 WHERE trade = 'PAINTER' AND name = 'Монтаж 3 Д панелей з підготовкою під фарбування';
UPDATE catalog_templates SET category = 'Багети'
 WHERE trade = 'PAINTER' AND name = 'Монтаж стельових багетів (простих - з пінопласту і т.п.) до 8 см';
UPDATE catalog_templates SET category = 'Оздоблення'
 WHERE trade = 'PAINTER' AND name = 'Обшивка орієнтовано-стружковою плитою (ОСП), фанерою';
UPDATE catalog_templates SET category = 'Вагонка'
 WHERE trade = 'PAINTER' AND name = 'Обшивка стелі шалівкою (кроком від 40-60 см)';
UPDATE catalog_templates SET category = 'Шпаклівка'
 WHERE trade = 'PAINTER' AND name = 'Ошкурення стін після шпаклівки (зробленої не нами, за погодженням)';
UPDATE catalog_templates SET category = 'Шпалери'
 WHERE trade = 'PAINTER' AND name = 'Поклейка шпалер шириною 100 см на стіну (без підбору)';
UPDATE catalog_templates SET category = 'Шпалери'
 WHERE trade = 'PAINTER' AND name = 'Поклейка шпалер шириною 100 см на стіну (з підбором)';
UPDATE catalog_templates SET category = 'Шпалери'
 WHERE trade = 'PAINTER' AND name = 'Поклейка шпалер шириною 50 см на стіну (без підбору)';
UPDATE catalog_templates SET category = 'Шпалери'
 WHERE trade = 'PAINTER' AND name = 'Поклейка шпалер шириною 50 см на стіну (з підбором)';
UPDATE catalog_templates SET category = 'Підготовка'
 WHERE trade = 'PAINTER' AND name = 'Установка перфорованих кутів на укоси, кути';
UPDATE catalog_templates SET category = 'Підвіконня'
 WHERE trade = 'PAINTER' AND name = 'Установка підвіконня з підготовкою';
UPDATE catalog_templates SET category = 'Фарбування'
 WHERE trade = 'PAINTER' AND name = 'Фарбування 3 Д панелей з підготовкою';
UPDATE catalog_templates SET category = 'Двері'
 WHERE trade = 'PAINTER' AND name = 'Фарбування дверей прихованого монтажу (підготовка і фарбування з двох сторін)';
UPDATE catalog_templates SET category = 'Багети'
 WHERE trade = 'PAINTER' AND name = 'Фарбування стельових багетів (простих - з пінопласту) до 8 см';
UPDATE catalog_templates SET category = 'Багети'
 WHERE trade = 'PAINTER' AND name = 'Фарбування стельових багетів, молдінгів (поліуретанових) до 6 см';
UPDATE catalog_templates SET category = 'Фарбування'
 WHERE trade = 'PAINTER' AND name = 'Фарбування труб (газової, опалення і ін.)';
UPDATE catalog_templates SET category = 'Підготовка'
 WHERE trade = 'PAINTER' AND name = 'Чищення бетонних плит /підготовчі роботи/';
UPDATE catalog_templates SET category = 'Шпаклівка'
 WHERE trade = 'PAINTER' AND name = 'Шпаклівка коробів, укосів, ніш та виступів під фарбування';
UPDATE catalog_templates SET category = 'Водопостачання'
 WHERE trade = 'PLUMBING' AND name = 'Виводи по точках (з встановленням кінцевого елементу)';
UPDATE catalog_templates SET category = 'Опалення'
 WHERE trade = 'PLUMBING' AND name = 'Виготовлення ніші під радіатор опалення в цегляній стіні';
UPDATE catalog_templates SET category = 'Опалення'
 WHERE trade = 'PLUMBING' AND name = 'Виготовлення ніші під шафу колектора/гребінки (в бетоні)';
UPDATE catalog_templates SET category = 'Опалення'
 WHERE trade = 'PLUMBING' AND name = 'Виготовлення ніші під шафу колектора/гребінки (в цеглі)';
UPDATE catalog_templates SET category = 'Каналізація'
 WHERE trade = 'PLUMBING' AND name = 'Заробка штроб (каналізація)';
UPDATE catalog_templates SET category = 'Водопостачання'
 WHERE trade = 'PLUMBING' AND name = 'Заробка штроб (сантехніка)';
UPDATE catalog_templates SET category = 'Опалення'
 WHERE trade = 'PLUMBING' AND name = 'Зняття і встановлення радіатора (під час ремонту - один раз)';
UPDATE catalog_templates SET category = 'Каналізація'
 WHERE trade = 'PLUMBING' AND name = 'Монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через дві панелі';
UPDATE catalog_templates SET category = 'Каналізація'
 WHERE trade = 'PLUMBING' AND name = 'Монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через одну панель';
UPDATE catalog_templates SET category = 'Каналізація'
 WHERE trade = 'PLUMBING' AND name = 'Монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через дві панелі';
UPDATE catalog_templates SET category = 'Каналізація'
 WHERE trade = 'PLUMBING' AND name = 'Монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через одну панель';
UPDATE catalog_templates SET category = 'Сантехприлади'
 WHERE trade = 'PLUMBING' AND name = 'Монтаж витяжки (без відведення або до існуючого вентканала)';
UPDATE catalog_templates SET category = 'Котел'
 WHERE trade = 'PLUMBING' AND name = 'Монтаж котельної (котел, бойлер, насоси, крани, фільтра) - від';
UPDATE catalog_templates SET category = 'Свердловина та насосна станція'
 WHERE trade = 'PLUMBING' AND name = 'Монтаж обладнання свердловини /запірної арматури (кранів, клапанів), гідроакумулятора та автоматики';
UPDATE catalog_templates SET category = 'Водопостачання'
 WHERE trade = 'PLUMBING' AND name = 'Монтаж фільтра очищення води (легка система) - 2 точки';
UPDATE catalog_templates SET category = 'Котел'
 WHERE trade = 'PLUMBING' AND name = 'Обв''язка котла (один котел - 4 точки) - від';
UPDATE catalog_templates SET category = 'Водопостачання'
 WHERE trade = 'PLUMBING' AND name = 'Прокладання труб з ізоляцією';
UPDATE catalog_templates SET category = 'Водопостачання'
 WHERE trade = 'PLUMBING' AND name = 'Прокладання труб на хомутах';
UPDATE catalog_templates SET category = 'Сантехприлади'
 WHERE trade = 'PLUMBING' AND name = 'Установка ванни із гідромасажем';
UPDATE catalog_templates SET category = 'Сантехприлади'
 WHERE trade = 'PLUMBING' AND name = 'Установка змішувача прихованого типу для душа (біде)';
UPDATE catalog_templates SET category = 'Опалення'
 WHERE trade = 'PLUMBING' AND name = 'Установка ніжок під радіатор із нижнім підключенням';
UPDATE catalog_templates SET category = 'Опалення'
 WHERE trade = 'PLUMBING' AND name = 'Установка радіатора опалення вертикального, чавунного';
UPDATE catalog_templates SET category = 'Котел'
 WHERE trade = 'PLUMBING' AND name = 'Установка та підключення бойлера непрямого нагріву';
UPDATE catalog_templates SET category = 'Сантехприлади'
 WHERE trade = 'PLUMBING' AND name = 'Установка та підключення душового піддону (готового)';
UPDATE catalog_templates SET category = 'Лічильники'
 WHERE trade = 'PLUMBING' AND name = 'Установка та підключення лічильника води простої складності';
UPDATE catalog_templates SET category = 'Лічильники'
 WHERE trade = 'PLUMBING' AND name = 'Установка та підключення лічильника води підвищеної складності';
UPDATE catalog_templates SET category = 'Опалення'
 WHERE trade = 'PLUMBING' AND name = 'Установка та підключення манометра';
UPDATE catalog_templates SET category = 'Сантехприлади'
 WHERE trade = 'PLUMBING' AND name = 'Установка та підключення пральної, сушильної машини';
UPDATE catalog_templates SET category = 'Опалення'
 WHERE trade = 'PLUMBING' AND name = 'Установка та підключення рушникоосушки';
UPDATE catalog_templates SET category = 'Сантехприлади'
 WHERE trade = 'PLUMBING' AND name = 'Установка фурнітури на змішувач прихованого типу (Smart Box)';
UPDATE catalog_templates SET category = 'Сантехприлади'
 WHERE trade = 'PLUMBING' AND name = 'Установка фурнітури на змішувач прихованого типу (біде)';
UPDATE catalog_templates SET category = 'Сантехприлади'
 WHERE trade = 'PLUMBING' AND name = 'Установка фурнітури на змішувач прихованого типу (умивальник)';
UPDATE catalog_templates SET category = 'Штроблення'
 WHERE trade = 'PLUMBING' AND name = 'Штроблення в бетоні';
UPDATE catalog_templates SET category = 'Штроблення'
 WHERE trade = 'PLUMBING' AND name = 'Штроблення в цеглі, газобетоні';
UPDATE catalog_templates SET category = 'Спецроботи'
 WHERE trade = 'TILING' AND name = 'Виготовлення поличок з плитки в три сторони (шириною від 30см)';
UPDATE catalog_templates SET category = 'Спецроботи'
 WHERE trade = 'TILING' AND name = 'Виготовлення поличок з плитки в три сторони (шириною до 30см)';
UPDATE catalog_templates SET category = 'Спецроботи'
 WHERE trade = 'TILING' AND name = 'Виготовлення поличок з плитки в чотири сторони (шириною від 30см)';
UPDATE catalog_templates SET category = 'Спецроботи'
 WHERE trade = 'TILING' AND name = 'Виготовлення поличок з плитки в чотири сторони (шириною до 30см)';
UPDATE catalog_templates SET category = 'Спецроботи'
 WHERE trade = 'TILING' AND name = 'Виготовлення та монтаж кріплення для раковини (зварювання, фарбування)';
UPDATE catalog_templates SET category = 'Затирка'
 WHERE trade = 'TILING' AND name = 'Затирка швів двокомпонентна (якщо сформовані внутрішній і зовнішній кут)';
UPDATE catalog_templates SET category = 'Затирка'
 WHERE trade = 'TILING' AND name = 'Затирка швів проста (якщо сформовані внутрішній і зовнішній кут)';
UPDATE catalog_templates SET category = 'Різка'
 WHERE trade = 'TILING' AND name = 'Порізка великоформатного керамограніту під кутом в 45 градусів';
UPDATE catalog_templates SET category = 'Різка'
 WHERE trade = 'TILING' AND name = 'Порізка плитки під кутом в 45 градусів';
UPDATE catalog_templates SET category = 'Спецроботи'
 WHERE trade = 'TILING' AND name = 'Піддон з плитки в рівень з підлогою (комплекс робіт)';
UPDATE catalog_templates SET category = 'Спецроботи'
 WHERE trade = 'TILING' AND name = 'Піддон з плитки з бортиком (комплекс робіт)';
UPDATE catalog_templates SET category = 'Укладання'
 WHERE trade = 'TILING' AND name = 'Укладання декоративної плитки під "цеглу" або "камінь" (гіпсова, бетонна)';
UPDATE catalog_templates SET category = 'Укладання'
 WHERE trade = 'TILING' AND name = 'Укладання плитки на слой (більше 1см)';
UPDATE catalog_templates SET category = 'Укладання'
 WHERE trade = 'TILING' AND name = 'Укладання плитки по діагоналі (+ до ціни укладання)';
UPDATE catalog_templates SET category = 'Укладання'
 WHERE trade = 'TILING' AND name = 'Укладка плінтуса із плитки, з урахуванням порізки плитки (без затирання)';
UPDATE catalog_templates SET category = 'Сходи'
 WHERE trade = 'TILING' AND name = 'Укладка та порізка плитки на підступок прямі (без кута 45)';
UPDATE catalog_templates SET category = 'Сходи'
 WHERE trade = 'TILING' AND name = 'Укладка та порізка плитки на сходи прямі (без кута 45)';
UPDATE catalog_templates SET category = 'Спецроботи'
 WHERE trade = 'TILING' AND name = 'Установка люка-ревізія на магнітах - від';

-- Two more caps buckets from the same import. «ЗІЗ» (DEMOLITION) stays — it is an acronym.
--
-- PAINTER already had a Фасад bucket holding 15 positions; ФАСАДНІ РОБОТИ was a second one for it.
UPDATE catalog_templates SET category = 'Фасад'
 WHERE trade = 'PAINTER' AND category = 'ФАСАДНІ РОБОТИ';

-- ГІПСОКАРТОН inside the DRYWALL trade is the trade name again. Its 8 positions go to buckets that
-- already exist, and they fill out Перегородки and Короби, which held only 4 and 1.
UPDATE catalog_templates SET category = 'Перегородки'
 WHERE trade = 'DRYWALL' AND category = 'ГІПСОКАРТОН' AND name LIKE 'Монтаж конструкцій%';
UPDATE catalog_templates SET category = 'Арка та декор ГКЛ'
 WHERE trade = 'DRYWALL' AND category = 'ГІПСОКАРТОН' AND name LIKE 'Монтаж радіусних%';
UPDATE catalog_templates SET category = 'Короби'
 WHERE trade = 'DRYWALL' AND category = 'ГІПСОКАРТОН' AND name LIKE 'Монтаж короба%';
UPDATE catalog_templates SET category = 'Шви і суміші'
 WHERE trade = 'DRYWALL' AND category = 'ГІПСОКАРТОН' AND name LIKE 'Заробка стиків%';
UPDATE catalog_templates SET category = 'Інше'
 WHERE trade = 'DRYWALL' AND category = 'ГІПСОКАРТОН';   -- «Монтаж на висоті», a surcharge

-- «Укладання» (25 positions) and «Укладка» (4) are the same word spelt two ways, so the master saw
-- two sections for one thing. Both hold works only — nothing is being kept apart.
UPDATE catalog_templates SET category = 'Укладання'
 WHERE trade = 'TILING' AND category = 'Укладка';

DO $$
DECLARE bad int;
BEGIN
  SELECT count(*) INTO bad FROM catalog_templates
   WHERE category = upper(category) AND category ~ '[Ѐ-ӿ]' AND category <> 'ЗІЗ';
  IF bad > 0 THEN
    RAISE EXCEPTION 'лишилось % позицій у CAPS-категоріях', bad;
  END IF;
END $$;

-- Nothing may be left behind in a bucket that no longer means anything.
DO $$
DECLARE bad int;
BEGIN
  SELECT count(*) INTO bad FROM catalog_templates WHERE category IN ('САНТЕХНІКА', 'ЕЛЕКТРИКА', 'ПЛИТОЧНІ РОБОТИ', 'ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ');
  IF bad > 0 THEN
    RAISE EXCEPTION 'у мішках лишилось % позицій', bad;
  END IF;
END $$;
