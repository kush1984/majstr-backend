package com.majstr.backend.telegram;

import com.majstr.backend.dto.InboxEntryView;
import com.majstr.backend.dto.InboxItemView;
import com.majstr.backend.entity.InboxEntry;
import com.majstr.backend.entity.InboxItem;
import com.majstr.backend.entity.InboxItem.Issue;
import com.majstr.backend.entity.InboxItem.Kind;
import com.majstr.backend.service.UnitLabel;
import com.majstr.backend.service.inbox.InboxService;
import com.majstr.backend.service.inbox.VoiceAccess;
import com.majstr.backend.telegram.TelegramClient.Button;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What the bot says back, and its buttons. Plain text (no parse mode), so nothing a master typed can
 * break the markup. Callback data stays under Telegram's 64 bytes by packing ids as 22-char base64.
 */
public final class TelegramReplies {

    static final int MAX_OBJECT_BUTTONS = 8;
    /** Telegram refuses a message over 4096 characters, and the bot would hang on «Записую…». */
    static final int MAX_TEXT = 3900;
    static final int MAX_LINE = 200;

    private TelegramReplies() {}

    public record Reply(String text, List<List<Button>> keyboard) {}

    public static final String HELLO_UNLINKED = """
            Вітаю! Це бот Майстра.

            Щоб записувати сюди покупки, задачі й витрати по обʼєктах, підключіть бота у застосунку: \
            Профіль → «Telegram-бот» → «Підключити».""";

    public static final String LINKED = """
            Готово, бот підключено ✅

            Тепер я одразу скажу вам тут, коли клієнт підпише кошторис чи акт, напише повідомлення або \
            повідомить про оплату. Вимкнути можна в застосунку: Профіль → «Telegram-бот».

            А ще пишіть або надиктовуйте мікрофоном клавіатури, що треба зробити чи записати, наприклад:
            • на Бандери докупити 3 мішки клею і грунт
            • Сихів — додати в кошторис демонтаж плитки 12 квадратів
            • подзвонити електрику
            • дав Василю 2000 на плитку

            Я розкладу по обʼєктах. Що не зрозумію — залишу у «Вхідних» у застосунку.""";

    /** Right after the bind, with the reply-keyboard button that shares his own number (V226). */
    public static final String ASK_CONTACT =
            "Поділіться, будь ласка, своїм номером кнопкою нижче — щоб ми могли швидко зв'язатися, якщо щось "
                    + "піде не так. Клієнтам і в документах він не показується. 👇";

    public static final String SHARE_CONTACT_BUTTON = "📱 Поділитися номером";

    /** The second button under the share one: takes the keyboard away (it stays open until one is pressed). */
    public static final String SKIP_CONTACT_BUTTON = "Не зараз";

    /** Shown greyed in the empty input field while the keyboard is open. */
    public static final String CONTACT_PLACEHOLDER = "Поділіться номером кнопкою нижче 👇";

    public static final String CONTACT_SKIPPED =
            "Добре. Поділитися номером можна будь-коли — надішліть /phone.";

    public static final String CONTACT_SAVED = "Дякую, номер збережено ✅";

    /** A forwarded or someone else's contact card: only his own number is taken. */
    public static final String CONTACT_NOT_YOURS =
            "Це не ваш номер. Щоб поділитися своїм, натисніть кнопку «📱 Поділитися номером».";

    public static final String CONTACT_INVALID = "Не вдалося прочитати номер. Спробуйте ще раз кнопкою нижче.";

    public static final String LINK_EXPIRED =
            "Посилання застаріло або вже використане. Відкрийте застосунок → Профіль → «Telegram-бот» ще раз.";

    public static final String ONLY_TEXT =
            "Я розумію текст і голосові. Фото й файли поки ні — напишіть або скажіть, що записати.";

    public static final String LISTENING = "Слухаю…";

    /** Why a voice note was not taken, and what to do instead. */
    public static Reply voiceRefused(VoiceAccess.Verdict verdict, int maxSeconds, String appUrl) {
        String plans = appUrl == null || !appUrl.startsWith("https://") ? null : appUrl.replaceAll("/+$", "") + "/profile";
        return switch (verdict) {
            case TOO_LONG -> new Reply("Голосове довше за " + maxSeconds + " с — розбийте на кілька коротших.", List.of());
            case VERIFY_EMAIL -> new Reply("Щоб надсилати голосові, підтвердіть пошту — лист у вашій скриньці. "
                    + "Текстом можна вже зараз.", List.of());
            case TRIAL_OVER -> new Reply("Безкоштовні голосові закінчились — далі вони у PRO. Текстом можна, як і раніше.",
                    plans == null ? List.of() : List.of(List.of(Button.link("Оформити PRO", plans))));
            case DAILY_OVER -> new Reply("На сьогодні ліміт голосових вичерпано. Текстом можна, як і раніше.", List.of());
            case OFF, OK -> new Reply("Голосові поки недоступні. Напишіть текстом — або надиктуйте мікрофоном на клавіатурі.",
                    List.of());
        };
    }

    public static final String WORKING = "Записую…";

    public static Reply outcome(InboxService.Outcome outcome, List<InboxService.ObjectChoice> objects, String appUrl) {
        return outcome(outcome, objects, appUrl, -1);
    }

    /** {@code trialLeft}: voice notes a FREE account still has on trial after this one; -1 = say nothing. */
    public static Reply outcome(InboxService.Outcome outcome, List<InboxService.ObjectChoice> objects, String appUrl,
                                int trialLeft) {
        Reply reply = body(outcome, objects, appUrl);
        InboxEntryView entry = outcome.entry();
        if (!entry.voice()) {
            return reply;
        }
        // What was heard comes first: a misheard word is visible before anything filed under it.
        String heard = entry.rawText() == null || entry.rawText().isBlank()
                ? "🎙 Не розчув — спробуйте ще раз або напишіть текстом."
                : "🎙 «" + truncate(entry.rawText(), 500) + "»";
        String trial = trialLeft < 0 ? "" : trialLeft == 0
                ? "\n\nЦе було останнє безкоштовне голосове — далі вони у PRO."
                : "\n\nБезкоштовних голосових залишилось: " + trialLeft + ".";
        return new Reply(fit(heard + "\n\n" + reply.text() + trial, true), reply.keyboard());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Reply body(InboxService.Outcome outcome, List<InboxService.ObjectChoice> objects, String appUrl) {
        InboxEntryView entry = outcome.entry();
        // Telegram refuses a link button that is not a public https address (localhost included), and with
        // it the whole message — so a local APP_URL simply gets no button.
        String inboxUrl = appUrl == null || !appUrl.startsWith("https://") ? null : appUrl.replaceAll("/+$", "") + "/inbox";
        if (outcome.notice() == InboxService.Notice.VERIFY_EMAIL) {
            return new Reply("Щоб бот розбирав повідомлення, підтвердіть пошту — лист у вашій скриньці "
                    + "(або в застосунку: банер «Підтвердіть пошту»). Запис збережено у «Вхідних».", linkRow(inboxUrl));
        }
        if (outcome.overBudget()) {
            return new Reply("На сьогодні ліміт розборів вичерпано. Запис збережено у «Вхідних» — розберете в застосунку.",
                    linkRow(inboxUrl));
        }
        if (entry.status() == InboxEntry.Status.FAILED) {
            return new Reply("Не вдалося розібрати — запис збережено у «Вхідних».", linkRow(inboxUrl));
        }
        List<InboxItemView> items = entry.items();
        if (items.isEmpty()) {
            return new Reply("Не знайшов тут, що записати. Текст збережено у «Вхідних».", linkRow(inboxUrl));
        }

        StringBuilder text = new StringBuilder();
        Map<String, List<InboxItemView>> applied = new LinkedHashMap<>();
        List<InboxItemView> waiting = new ArrayList<>();
        boolean anyDiscarded = false;
        for (InboxItemView i : items) {
            switch (i.status()) {
                case APPLIED -> applied.computeIfAbsent(i.projectName() != null ? i.projectName() : "",
                        k -> new ArrayList<>()).add(i);
                case PENDING -> waiting.add(i);
                case DISCARDED -> anyDiscarded = true;
            }
        }
        applied.forEach((object, list) -> {
            text.append(object.isEmpty() ? "✅ Записав:" : "✅ Записав у «" + object + "»:").append('\n');
            list.forEach(i -> text.append(line(i)).append('\n'));
            text.append('\n');
        });
        if (!waiting.isEmpty()) {
            text.append("⏳ Чекає:\n");
            waiting.forEach(i -> text.append(line(i)).append(" — ").append(reason(i)).append('\n'));
            text.append('\n');
        }
        if (applied.isEmpty() && waiting.isEmpty() && anyDiscarded) {
            text.append("Скасовано.\n");
        }
        if (outcome.notice() == InboxService.Notice.ESTIMATE_LIMIT) {
            text.append("⚠️ Новий кошторис не створено: на безкоштовному плані до 3 кошторисів на обʼєкт.\n");
        }
        for (String failed : outcome.failures()) {
            text.append("⚠️ Не вдалося записати «").append(failed).append("» — залишив у «Вхідних».\n");
        }

        List<List<Button>> keyboard = new ArrayList<>();
        String entryKey = pack(entry.id());
        boolean asksObject = waiting.stream().anyMatch(i -> i.issue() == Issue.OBJECT
                || (isMoney(i.kind()) && i.projectId() == null));
        if (asksObject && !objects.isEmpty()) {
            List<Button> row = new ArrayList<>();
            for (InboxService.ObjectChoice o : objects.subList(0, Math.min(objects.size(), MAX_OBJECT_BUTTONS))) {
                row.add(Button.callback(o.name(), "o:" + entryKey + ":" + pack(o.id())));
                if (row.size() == 2) {
                    keyboard.add(row);
                    row = new ArrayList<>();
                }
            }
            row.add(Button.callback("Без обʼєкта", "o:" + entryKey + ":-"));
            keyboard.add(row);
        }
        List<Button> actions = new ArrayList<>();
        if (waiting.stream().anyMatch(i -> i.issue() == Issue.NO_DRAFT)) {
            keyboard.add(List.of(Button.callback("📋 Новий кошторис «Додаткові роботи»", "e:" + entryKey)));
        }
        if (waiting.stream().anyMatch(i -> i.issue() == Issue.CONFIRM)) {
            actions.add(Button.callback("✅ Записати", "c:" + entryKey));
        }
        if (!applied.isEmpty()) {
            actions.add(Button.callback("↩️ Скасувати", "u:" + entryKey));
        }
        boolean filedUnderObject = applied.values().stream().flatMap(List::stream).anyMatch(i -> i.projectId() != null);
        if (filedUnderObject && !objects.isEmpty()) {
            actions.add(Button.callback("🔄 Інший обʼєкт", "m:" + entryKey));
        }
        if (!actions.isEmpty()) {
            keyboard.add(actions);
        }
        boolean needsApp = waiting.stream().anyMatch(i -> i.issue() != Issue.CONFIRM && i.issue() != Issue.OBJECT
                        && i.issue() != Issue.NO_DRAFT)
                || !outcome.failures().isEmpty();
        if (needsApp && inboxUrl != null) {
            keyboard.add(List.of(Button.link("Відкрити «Вхідні»", inboxUrl)));
        }
        return new Reply(fit(text.toString().strip(), inboxUrl != null), keyboard);
    }

    /** What a message filed includes money: «Скасувати» then asks once more before deleting it. */
    public static boolean filedMoney(InboxService.Outcome outcome) {
        return outcome.entry().items().stream()
                .anyMatch(i -> i.status() == InboxItem.Status.APPLIED && isMoney(i.kind()));
    }

    /** «Скасувати» on money: the records named, and a second tap to delete them. */
    public static Reply confirmUndo(InboxService.Outcome outcome) {
        String entryKey = pack(outcome.entry().id());
        StringBuilder text = new StringBuilder("Видалити записане з цього повідомлення?\n\n");
        outcome.entry().items().stream().filter(i -> i.status() == InboxItem.Status.APPLIED)
                .forEach(i -> text.append(line(i)).append('\n'));
        List<List<Button>> keyboard = List.of(List.of(
                Button.callback("🗑 Так, видалити", "U:" + entryKey),
                Button.callback("← Назад", "v:" + entryKey)));
        return new Reply(fit(text.toString().strip(), false), keyboard);
    }

    /** Under Telegram's limit: what does not fit is in «Вхідні». */
    static String fit(String text, boolean hasInbox) {
        if (text.length() <= MAX_TEXT) {
            return text;
        }
        int cut = text.lastIndexOf('\n', MAX_TEXT);
        String head = text.substring(0, cut > 0 ? cut : MAX_TEXT);
        return head + (hasInbox ? "\n…решта — у «Вхідних»." : "\n…");
    }

    /** «Інший обʼєкт»: the same message with the objects to move it to, and a way back. */
    public static Reply pickObject(InboxService.Outcome outcome, List<InboxService.ObjectChoice> objects) {
        String entryKey = pack(outcome.entry().id());
        StringBuilder text = new StringBuilder("До якого обʼєкта перенести?\n\n");
        outcome.entry().items().stream().filter(i -> i.status() != InboxItem.Status.DISCARDED)
                .forEach(i -> text.append(line(i)).append('\n'));
        List<List<Button>> keyboard = new ArrayList<>();
        List<Button> row = new ArrayList<>();
        for (InboxService.ObjectChoice o : objects.subList(0, Math.min(objects.size(), MAX_OBJECT_BUTTONS))) {
            row.add(Button.callback(o.name(), "r:" + entryKey + ":" + pack(o.id())));
            if (row.size() == 2) {
                keyboard.add(row);
                row = new ArrayList<>();
            }
        }
        row.add(Button.callback("Без обʼєкта", "r:" + entryKey + ":-"));
        keyboard.add(row);
        keyboard.add(List.of(Button.callback("← Назад", "v:" + entryKey)));
        return new Reply(text.toString().strip(), keyboard);
    }

    static String line(InboxItemView i) {
        String line = rawLine(i);
        return line.length() <= MAX_LINE ? line : line.substring(0, MAX_LINE - 1) + "…";
    }

    private static String rawLine(InboxItemView i) {
        return switch (i.kind()) {
            case SHOPPING -> "🛒 " + i.text() + quantity(i);
            case TASK -> "☑️ " + i.text();
            case NOTE -> "📝 " + i.text();
            case ESTIMATE_LINE -> "📋 У кошторис: " + i.text() + quantity(i)
                    + (i.unitPrice() != null ? " × " + money(i.unitPrice()) : "");
            case EXPENSE -> "💸 Витрата " + (i.amount() != null ? money(i.amount()) : "?") + " — " + i.text()
                    + where(i);
            case INCOME -> "💰 Отримано " + (i.amount() != null ? money(i.amount()) : "?") + " — " + i.text()
                    + where(i);
        };
    }

    private static String where(InboxItemView i) {
        if (i.status() != InboxItem.Status.PENDING) {
            return "";
        }
        return i.projectName() != null ? " · «" + i.projectName() + "»" : " · без обʼєкта (у «Мої гроші»)";
    }

    static String reason(InboxItemView i) {
        return switch (i.issue()) {
            case OBJECT -> "для якого обʼєкта?";
            case ESTIMATE -> "у який кошторис? — оберіть у «Вхідних»";
            case NO_DRAFT -> "усі кошториси обʼєкта підписані — створити новий?";
            case QUANTITY -> "скільки?";
            case UNIT -> "яка одиниця?";
            case PRICE -> "немає ціни в каталозі";
            case AMOUNT -> "яка сума?";
            case CONFIRM -> "записати?";
        };
    }

    private static String quantity(InboxItemView i) {
        if (i.quantity() == null) {
            return "";
        }
        return " — " + number(i.quantity()) + (i.unit() != null ? " " + UnitLabel.ua(i.unit()) : "");
    }

    static String money(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat("#,##0.##", symbols);
        return format.format(amount.setScale(2, RoundingMode.HALF_UP)) + " ₴";
    }

    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static boolean isMoney(Kind kind) {
        return kind == Kind.EXPENSE || kind == Kind.INCOME;
    }

    private static List<List<Button>> linkRow(String url) {
        return url == null ? List.of() : List.of(List.of(Button.link("Відкрити «Вхідні»", url)));
    }

    // ---- callback ids ---------------------------------------------------------------------------

    static String pack(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    static UUID unpack(String packed) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getUrlDecoder().decode(packed));
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
