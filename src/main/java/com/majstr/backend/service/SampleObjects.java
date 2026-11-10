package com.majstr.backend.service;

import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What the training sample (V197) contains, per trade: one small job a master of that trade knows, with
 * the quantities filled. Approved by the owner on 2026-11-09 (iteration-sample-object).
 *
 * <p>Each line is a position of that trade's default bundle, NAMED character for character as the
 * library ships it: the price comes from the master's own catalog by that name, the library's when he
 * does not stock it. Lines that do not fit the job, and the two the library prices at 0 ₴, were left
 * out so the client never sees an empty or a zero row. {@code SampleObjectsIntegrationTest} pins that
 * every line still finds a priced library position.</p>
 */
final class SampleObjects {

    record Line(String name, Unit unit, BigDecimal quantity) {}

    record Sample(Trade trade, String title, List<Line> lines) {}

    private SampleObjects() {
    }

    private static Line line(String name, Unit unit, String quantity) {
        return new Line(name, unit, new BigDecimal(quantity));
    }

    static final Sample BATHROOM = new Sample(Trade.TILING, "Приклад: ванна 4 м²", List.of(
            line("Захист підлоги картоном", Unit.M2, "6"),
            line("Демонтаж плитки", Unit.M2, "22"),
            line("Очищення, обезпилювання", Unit.M2, "22"),
            line("Ґрунтування поверхні", Unit.M2, "22"),
            line("Вирівнювання поверхні шаром клею", Unit.M2, "4"),
            line("Нанесення гідроізоляційної мастики у 2 шари", Unit.M2, "7"),
            line("Армування кутів гідроізоляційної стрічкою", Unit.LINEAR_METER, "10"),
            line("Поклейка гідроізоляційних муфт на водорозетки", Unit.PIECE, "4"),
            line("Укладання плитки 300х600", Unit.M2, "18"),
            line("Укладання плитки 300х300", Unit.M2, "4"),
            line("Виріз отворів до 70 мм", Unit.PIECE, "6"),
            line("Встановлення профілю для плитки", Unit.LINEAR_METER, "6"),
            line("Заповнення швів цементною сумішшю", Unit.M2, "22"),
            line("Заповнення швів герметиком", Unit.LINEAR_METER, "10"),
            line("Миття плитки від цементного нальоту після затирання", Unit.M2, "22"),
            line("Фасування сміття в мішки", Unit.PIECE, "30"),
            line("Винесення та вивезення будівельного сміття", Unit.M3, "1"),
            line("Прибирання приміщення після робіт", Unit.M2, "4")));

    static final Map<Trade, Sample> BY_TRADE = Map.of(
            Trade.TILING, BATHROOM,
            Trade.PLUMBING, new Sample(Trade.PLUMBING, "Приклад: туалет з інсталяцією", List.of(
                    line("Демонтаж унітазу", Unit.PIECE, "1"),
                    line("Виводи по точках з кінцевим елементом", Unit.POINT, "2"),
                    line("Виводи каналізації по точках", Unit.POINT, "1"),
                    line("Монтаж інсталяції для унітазу", Unit.PIECE, "1"),
                    line("Опресування (гідравлічне випробування) водопроводу", Unit.PIECE, "1"),
                    line("Перевірка каналізації проливом", Unit.PIECE, "1"),
                    line("Установка унітазу на готову інсталяцію", Unit.PIECE, "1"),
                    line("Монтаж панелі з клавішами змиву", Unit.PIECE, "1"),
                    line("Монтаж гігієнічного душу", Unit.PIECE, "1"),
                    line("Установка люка-ревізії простого", Unit.PIECE, "1"))),
            Trade.ELECTRICAL, new Sample(Trade.ELECTRICAL, "Приклад: електрика у ванній", List.of(
                    line("Розмітка трас і точок електрики на стінах і стелі", Unit.M2, "22"),
                    line("Штроблення під електрокабель 20х20 мм (в цеглі)", Unit.LINEAR_METER, "15"),
                    line("Отвір для підрозетника в цеглі", Unit.PIECE, "4"),
                    line("Прокладка кабелю перерізом до 2,5 мм² в гофрованій трубі", Unit.LINEAR_METER, "25"),
                    line("Монтаж підрозетника", Unit.PIECE, "4"),
                    line("Монтаж системи додаткового зрівнювання потенціалів у ванній кімнаті", Unit.SET, "1"),
                    line("Вимірювання опору ізоляції кабельних ліній", Unit.PIECE, "3"),
                    line("Закладення штроб гіпсовою сумішшю", Unit.LINEAR_METER, "15"),
                    line("Установка та підключення вологозахищеної розетки IP44", Unit.PIECE, "2"),
                    line("Підключення бойлера (водонагрівача)", Unit.PIECE, "1"),
                    line("Встановлення та підключення витяжного вентилятора", Unit.PIECE, "1"),
                    line("Підключення електричного рушникосушника", Unit.PIECE, "1"),
                    line("Установка ПЗВ двополюсний", Unit.PIECE, "1"),
                    line("Установка пристрою захисту від дугового пробою (ПВДП)", Unit.PIECE, "1"),
                    line("Перевірка спрацювання ПЗВ та вимірювання опору петлі фаза-нуль", Unit.PIECE, "1"),
                    line("Прибирання приміщення після робіт", Unit.M2, "4"))),
            Trade.DRYWALL, new Sample(Trade.DRYWALL, "Приклад: стеля 15 м²", List.of(
                    line("Захист підлоги картоном", Unit.M2, "15"),
                    line("Звукоізоляція стелі мінеральною ватою", Unit.M2, "15"),
                    line("Монтаж гіпсокартону на стелю рівну", Unit.M2, "15"),
                    line("Монтаж короба (прямого) із гіпсокартону по периметру стелі", Unit.LINEAR_METER, "16"),
                    line("Монтаж ніші з гіпсокартону під прихований карниз", Unit.LINEAR_METER, "3"),
                    line("Монтаж треків прихованого карниза", Unit.LINEAR_METER, "3"),
                    line("Установка люка-ревізії простого", Unit.PIECE, "1"),
                    line("Вирізка отворів в гіпсокартоні", Unit.PIECE, "4"),
                    line("Фасування сміття в мішки", Unit.PIECE, "10"),
                    line("Винесення та вивезення будівельного сміття", Unit.M3, "0.5"),
                    line("Прибирання приміщення після робіт", Unit.M2, "15"))),
            Trade.PAINTER, new Sample(Trade.PAINTER, "Приклад: кімната 15 м²", List.of(
                    line("Захист підлоги картоном", Unit.M2, "15"),
                    line("Обклеювання вікон/дверей плівкою", Unit.M2, "4"),
                    line("Зняття шпалер", Unit.M2, "39"),
                    line("Змивання побілки зі стелі", Unit.M2, "15"),
                    line("Розшивка та заробка тріщин", Unit.LINEAR_METER, "10"),
                    line("Заробка тріщин раковин шліфування", Unit.M2, "10"),
                    line("Обезпилення поверхні", Unit.M2, "54"),
                    line("Грунтування", Unit.M2, "54"),
                    line("Шпаклювання фінішне (2–4 рази)", Unit.M2, "54"),
                    line("Шліфування стін/стель (фінішне)", Unit.M2, "54"),
                    line("Грунт-фарба (праймер під фарбу)", Unit.M2, "54"),
                    line("Фарбування стелі водоемульсійною фарбою", Unit.M2, "15"),
                    line("Фарбування стін водоемульсійною фарбою", Unit.M2, "39"),
                    line("Обезпилення та грунтування укосів перед фарбуванням", Unit.LINEAR_METER, "6"),
                    line("Фарбування укосів", Unit.LINEAR_METER, "6"),
                    line("Фасування сміття в мішки", Unit.PIECE, "15"))),
            Trade.FLOORING, new Sample(Trade.FLOORING, "Приклад: ламінат у кімнаті 15 м²", List.of(
                    line("Демонтаж плінтуса", Unit.LINEAR_METER, "16"),
                    line("Демонтаж лінолеуму ковроліну", Unit.M2, "15"),
                    line("Фасування сміття в мішки", Unit.PIECE, "10"),
                    line("Винесення та вивезення будівельного сміття", Unit.M3, "0.5"),
                    line("Підготовка поверхні (очищення і т.п.)", Unit.M2, "15"),
                    line("Ґрунтування основи під стяжку", Unit.M2, "15"),
                    line("Монтаж демпферної стрічки", Unit.M2, "15"),
                    line("Вирівнювання підлоги самовирівнюючим розчином", Unit.M2, "15"),
                    line("Підрізання дверних коробок і наличників під покриття", Unit.PIECE, "1"),
                    line("Укладання пароізоляційної плівки під ламінат", Unit.M2, "15"),
                    line("Укладання ламінату прямим способом", Unit.M2, "15"),
                    line("Монтаж плінтуса пластик", Unit.LINEAR_METER, "15"),
                    line("Монтаж міжкімнатного порога", Unit.LINEAR_METER, "1"),
                    line("Прибирання приміщення після робіт", Unit.M2, "15"))),
            Trade.DEMOLITION, new Sample(Trade.DEMOLITION, "Приклад: демонтаж санвузла", List.of(
                    line("Захист вхідних дверей картоном", Unit.PIECE, "1"),
                    line("Пилозахисна завіса з плівки на дверний проріз", Unit.PIECE, "1"),
                    line("Демонтаж змішувача", Unit.PIECE, "2"),
                    line("Демонтаж рушникосушки", Unit.PIECE, "1"),
                    line("Демонтаж умивальника раковини", Unit.PIECE, "1"),
                    line("Демонтаж унітазу", Unit.PIECE, "1"),
                    line("Демонтаж екрана ванни з плиткою", Unit.PIECE, "1"),
                    line("Демонтаж ванни", Unit.PIECE, "1"),
                    line("Демонтаж старих труб", Unit.LINEAR_METER, "6"),
                    line("Демонтаж плитки зі стін", Unit.M2, "18"),
                    line("Демонтаж керамічної плитки з підлоги", Unit.M2, "4"),
                    line("Демонтаж стяжки бетонної", Unit.M2, "4"),
                    line("Демонтаж міжкімнатних дверей з коробкою", Unit.PIECE, "1"),
                    line("Фасування сміття в мішки", Unit.PIECE, "40"),
                    line("Винесення будівельного сміття", Unit.M3, "1.5"),
                    line("Вантаження сміття в авто", Unit.M3, "1.5"),
                    line("Прибирання приміщення після робіт", Unit.M2, "4"))),
            Trade.BUILDER, new Sample(Trade.BUILDER, "Приклад: перегородка 10 м²", List.of(
                    line("Захист підлоги картоном", Unit.M2, "10"),
                    line("Кладка перегородки з газоблоку (піноблоку) до 50 м²", Unit.M2, "10"),
                    line("Армування кладки сіткою арматурою", Unit.M2, "10"),
                    line("Анкерування однієї стіни до іншої", Unit.PIECE, "2"),
                    line("Облаштування дверної пройми", Unit.PIECE, "1"),
                    line("Монтаж перемичок над прорізами", Unit.PIECE, "1"),
                    line("Фасування сміття в мішки", Unit.PIECE, "10"),
                    line("Винесення та вивезення будівельного сміття", Unit.M3, "0.3"),
                    line("Прибирання приміщення після робіт", Unit.M2, "10"))),
            Trade.METAL, new Sample(Trade.METAL, "Приклад: козирок над входом", List.of(
                    line("Виготовлення козирка над входом", Unit.PIECE, "1"),
                    line("Зачистка та знежирення металу", Unit.M2, "3"),
                    line("Ґрунтування металу", Unit.M2, "3"),
                    line("Фарбування металу", Unit.M2, "3"),
                    line("Монтаж козирка над входом (кронштейни, анкери)", Unit.PIECE, "1"),
                    line("Монтаж покрівлі навісу з полікарбонату", Unit.M2, "1.5"))));

    /**
     * The sample for the trade the master just picked; else the first of his own trades that has one
     * (in the enum's order); else the bathroom, which anyone understands.
     */
    static Sample choose(Trade requested, Collection<Trade> masterTrades) {
        if (requested != null && BY_TRADE.containsKey(requested)) {
            return BY_TRADE.get(requested);
        }
        for (Trade trade : Trade.values()) {
            if (masterTrades.contains(trade) && BY_TRADE.containsKey(trade)) {
                return BY_TRADE.get(trade);
            }
        }
        return BATHROOM;
    }
}
