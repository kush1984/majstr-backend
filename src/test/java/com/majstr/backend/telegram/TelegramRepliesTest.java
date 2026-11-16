package com.majstr.backend.telegram;

import com.majstr.backend.dto.InboxEntryView;
import com.majstr.backend.dto.InboxItemView;
import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.InboxEntry;
import com.majstr.backend.entity.InboxItem;
import com.majstr.backend.entity.InboxItem.Issue;
import com.majstr.backend.entity.InboxItem.Kind;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.inbox.InboxService;
import com.majstr.backend.telegram.TelegramClient.Button;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramRepliesTest {

    private static final UUID BANDERY = UUID.randomUUID();
    private static final List<InboxService.ObjectChoice> OBJECTS = List.of(
            new InboxService.ObjectChoice(BANDERY, "Бандери 12"),
            new InboxService.ObjectChoice(UUID.randomUUID(), "Сихів"));

    @Test
    void aLongAnswerFitsTelegramsLimit_andLongLinesAreCut() {
        String longText = "клей ".repeat(100);
        List<InboxItemView> many = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(item(Kind.SHOPPING, InboxItem.Status.APPLIED, null, BANDERY, "Бандери 12", longText, null, null, null));
        }
        InboxEntryView entry = new InboxEntryView(UUID.randomUUID(), InboxEntry.Source.TELEGRAM,
                InboxEntry.Status.DONE, "текст", Instant.now(), many);

        TelegramReplies.Reply reply = TelegramReplies.outcome(new InboxService.Outcome(entry, true, false, List.of()),
                OBJECTS, "https://majstr.pro/");

        assertThat(reply.text().length()).isLessThanOrEqualTo(4096);
        assertThat(reply.text()).endsWith("…решта — у «Вхідних».");
        assertThat(TelegramReplies.line(many.get(0)).length()).isLessThanOrEqualTo(TelegramReplies.MAX_LINE);
    }

    @Test
    void undoOnMoneyAsksOnceMore_withTheSumNamed() {
        InboxItemView paid = item(Kind.EXPENSE, InboxItem.Status.APPLIED, null, BANDERY, "Бандери 12", "плитка",
                null, null, new BigDecimal("2000"));
        InboxEntryView entry = new InboxEntryView(UUID.randomUUID(), InboxEntry.Source.TELEGRAM,
                InboxEntry.Status.DONE, "текст", Instant.now(), List.of(paid));
        InboxService.Outcome outcome = new InboxService.Outcome(entry, true, false, List.of());

        assertThat(TelegramReplies.filedMoney(outcome)).isTrue();
        TelegramReplies.Reply ask = TelegramReplies.confirmUndo(outcome);
        assertThat(ask.text()).contains("2 000 ₴");
        assertThat(ask.keyboard().get(0)).extracting(Button::callback)
                .containsExactly("U:" + TelegramReplies.pack(entry.id()), "v:" + TelegramReplies.pack(entry.id()));
    }

    @Test
    void anUnconfirmedEmailIsSaidPlainly() {
        InboxEntryView entry = new InboxEntryView(UUID.randomUUID(), InboxEntry.Source.TELEGRAM,
                InboxEntry.Status.FAILED, "текст", Instant.now(), List.of());

        TelegramReplies.Reply reply = TelegramReplies.outcome(new InboxService.Outcome(entry, true, false, List.of(),
                InboxService.Notice.VERIFY_EMAIL), OBJECTS, "https://majstr.pro/");

        assertThat(reply.text()).contains("підтвердіть пошту");
    }

    @Test
    void aVoiceNoteShowsWhatWasHeardFirst_andHowManyTrialNotesAreLeft() {
        InboxItemView task = item(Kind.TASK, InboxItem.Status.APPLIED, null, BANDERY, "Бандери 12",
                "подзвонити електрику", null, null, null);
        InboxEntryView entry = new InboxEntryView(UUID.randomUUID(), InboxEntry.Source.TELEGRAM,
                InboxEntry.Status.DONE, "подзвонити електрику на бандери", Instant.now(), List.of(task), true);

        TelegramReplies.Reply reply = TelegramReplies.outcome(new InboxService.Outcome(entry, true, false, List.of()),
                OBJECTS, "https://majstr.pro/", 3);

        assertThat(reply.text()).startsWith("🎙 «подзвонити електрику на бандери»");
        assertThat(reply.text()).contains("☑️ подзвонити електрику");
        assertThat(reply.text()).endsWith("Безкоштовних голосових залишилось: 3.");
    }

    @Test
    void aTypedMessageGetsNoMicrophoneLine() {
        TelegramReplies.Reply reply = reply(item(Kind.TASK, InboxItem.Status.APPLIED, null, BANDERY, "Бандери 12",
                "подзвонити електрику", null, null, null));

        assertThat(reply.text()).doesNotContain("🎙");
    }

    @Test
    void whenTheTrialIsOverTheBotOffersPro_onAnHttpsAddressOnly() {
        TelegramReplies.Reply reply = TelegramReplies.voiceRefused(
                com.majstr.backend.service.inbox.VoiceAccess.Verdict.TRIAL_OVER, 60, "https://majstr.pro");
        assertThat(reply.text()).contains("PRO");
        assertThat(reply.keyboard()).flatExtracting(row -> row).extracting(Button::url)
                .containsExactly("https://majstr.pro/profile");

        TelegramReplies.Reply local = TelegramReplies.voiceRefused(
                com.majstr.backend.service.inbox.VoiceAccess.Verdict.TRIAL_OVER, 60, "http://localhost:5173");
        assertThat(local.keyboard()).isEmpty();
        assertThat(TelegramReplies.voiceRefused(
                com.majstr.backend.service.inbox.VoiceAccess.Verdict.TOO_LONG, 60, null).text()).contains("60 с");
    }

    @Test
    void idsPackUnderTelegramsSixtyFourBytes() {
        UUID entry = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        String data = "o:" + TelegramReplies.pack(entry) + ":" + TelegramReplies.pack(object);

        assertThat(data.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
        assertThat(TelegramReplies.unpack(TelegramReplies.pack(entry))).isEqualTo(entry);
    }

    @Test
    void whatWasFiledIsListedUnderItsObject_withUndo() {
        TelegramReplies.Reply reply = reply(item(Kind.SHOPPING, InboxItem.Status.APPLIED, null, BANDERY,
                "Бандери 12", "клей", new BigDecimal("3.000"), Unit.PIECE, null));

        assertThat(reply.text()).contains("Записав у «Бандери 12»").contains("🛒 клей — 3 шт");
        assertThat(labels(reply)).containsExactly("↩️ Скасувати", "🔄 Інший обʼєкт");
    }

    @Test
    void anotherObjectListsTheObjects_noneAndAWayBack() {
        InboxItemView filed = item(Kind.TASK, InboxItem.Status.APPLIED, null, BANDERY, "Бандери 12",
                "замовити двері", null, null, null);
        InboxEntryView entry = new InboxEntryView(UUID.randomUUID(), InboxEntry.Source.TELEGRAM,
                InboxEntry.Status.DONE, "текст", Instant.now(), List.of(filed));

        TelegramReplies.Reply pick = TelegramReplies.pickObject(
                new InboxService.Outcome(entry, true, false, List.of()), OBJECTS);

        assertThat(pick.text()).startsWith("До якого обʼєкта перенести?").contains("☑️ замовити двері");
        assertThat(labels(pick)).containsExactly("Бандери 12", "Сихів", "Без обʼєкта", "← Назад");
        assertThat(pick.keyboard().get(0).get(0).callback()).startsWith("r:");
    }

    @Test
    void aMissingObjectAsksWithOneButtonPerObject() {
        TelegramReplies.Reply reply = reply(item(Kind.TASK, InboxItem.Status.PENDING, Issue.OBJECT, null, null,
                "подзвонити електрику", null, null, null));

        assertThat(reply.text()).contains("Чекає").contains("для якого обʼєкта?");
        assertThat(labels(reply)).containsExactly("Бандери 12", "Сихів", "Без обʼєкта");
    }

    @Test
    void moneyOffersRecord_andSaysWhereItWillGo() {
        TelegramReplies.Reply reply = reply(item(Kind.EXPENSE, InboxItem.Status.PENDING, Issue.CONFIRM, BANDERY,
                "Бандери 12", "плитка", null, null, new BigDecimal("2000")));

        assertThat(reply.text()).contains("💸 Витрата 2 000 ₴ — плитка · «Бандери 12» — записати?");
        assertThat(labels(reply)).containsExactly("✅ Записати");
    }

    @Test
    void anItemOnlyTheAppCanFixLinksToTheInbox() {
        TelegramReplies.Reply reply = reply(item(Kind.ESTIMATE_LINE, InboxItem.Status.PENDING, Issue.PRICE, BANDERY,
                "Бандери 12", "щось нове", BigDecimal.ONE, Unit.PIECE, null));

        assertThat(reply.text()).contains("немає ціни в каталозі");
        assertThat(reply.keyboard()).flatExtracting(row -> row).extracting(Button::url)
                .contains("https://majstr.pro/inbox");
    }

    @Test
    void aLocalAppUrlGetsNoLinkButton_whichTelegramWouldRefuseWithTheWholeMessage() {
        InboxItemView item = item(Kind.ESTIMATE_LINE, InboxItem.Status.PENDING, Issue.ESTIMATE, BANDERY,
                "Бандери 12", "демонтаж плитки", BigDecimal.TEN, Unit.M2, null);
        InboxEntryView entry = new InboxEntryView(UUID.randomUUID(), InboxEntry.Source.TELEGRAM,
                InboxEntry.Status.NEW, "текст", Instant.now(), List.of(item));

        TelegramReplies.Reply reply = TelegramReplies.outcome(new InboxService.Outcome(entry, true, false, List.of()),
                OBJECTS, "http://localhost:5173");

        assertThat(reply.keyboard()).flatExtracting(row -> row).extracting(Button::url).allMatch(java.util.Objects::isNull);
    }

    private static TelegramReplies.Reply reply(InboxItemView item) {
        InboxEntryView entry = new InboxEntryView(UUID.randomUUID(), InboxEntry.Source.TELEGRAM,
                InboxEntry.Status.NEW, "текст", Instant.now(), List.of(item));
        return TelegramReplies.outcome(new InboxService.Outcome(entry, true, false, List.of()), OBJECTS,
                "https://majstr.pro/");
    }

    private static List<String> labels(TelegramReplies.Reply reply) {
        return reply.keyboard().stream().flatMap(List::stream).map(Button::text).toList();
    }

    private static InboxItemView item(Kind kind, InboxItem.Status status, Issue issue, UUID projectId,
                                      String projectName, String text, BigDecimal quantity, Unit unit,
                                      BigDecimal amount) {
        return new InboxItemView(UUID.randomUUID(), kind, status, projectId, projectName, null, text, quantity,
                unit, null, null, amount, amount != null ? CashCategory.MATERIALS : null, issue);
    }
}
