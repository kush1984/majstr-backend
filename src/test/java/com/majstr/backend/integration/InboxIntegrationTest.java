package com.majstr.backend.integration;

import com.majstr.backend.config.BotProperties;
import com.majstr.backend.dto.InboxItemView;
import com.majstr.backend.dto.TaskRequest;
import com.majstr.backend.dto.TaskResponse;
import com.majstr.backend.dto.TaskUpdateRequest;
import com.majstr.backend.entity.InboxEntry;
import com.majstr.backend.entity.InboxItem;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.TelegramLinkCode;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogItemSynonymRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.InboxEntryRepository;
import com.majstr.backend.repository.InboxItemRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.TelegramLinkCodeRepository;
import com.majstr.backend.repository.TelegramLinkRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.CashFlowService;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.ObjectExpenseService;
import com.majstr.backend.service.PaymentService;
import com.majstr.backend.service.ProjectNoteService;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.service.ProjectTaskService;
import com.majstr.backend.service.ShoppingListService;
import com.majstr.backend.service.inbox.InboxExtractor;
import com.majstr.backend.service.inbox.InboxExtractor.ObjectRef;
import com.majstr.backend.service.inbox.InboxExtractor.Parsed;
import com.majstr.backend.service.inbox.InboxParseRateLimiter;
import com.majstr.backend.service.inbox.InboxService;
import com.majstr.backend.telegram.TelegramLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * majster-bot against real Postgres: what the inbox files goes through each record's OWN service
 * and lands in its real table; money waits for «Записати»; a guessed object waits for a question;
 * undo takes it all back. The model is replaced by a script — {@link InboxService} is wired by hand
 * around it with the context's real beans, so no second Spring context is started for one stub.
 */
class InboxIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired InboxEntryRepository entryRepository;
    @Autowired InboxItemRepository itemRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired CatalogItemRepository catalogItemRepository;
    @Autowired CatalogItemSynonymRepository synonymRepository;
    @Autowired ProjectService projectService;
    @Autowired ShoppingListService shoppingListService;
    @Autowired ProjectTaskService taskService;
    @Autowired ProjectNoteService noteService;
    @Autowired EstimateService estimateService;
    @Autowired ObjectExpenseService expenseService;
    @Autowired PaymentService paymentService;
    @Autowired CashFlowService cashFlowService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.majstr.backend.service.ai.AiAccess aiAccess;
    private final FakeSpeech speechToText = new FakeSpeech();
    private final com.majstr.backend.service.inbox.VoiceAudio voiceAudio = fileId -> new byte[] {1, 2, 3};
    @Autowired TelegramLinkService linkService;
    @Autowired TelegramLinkCodeRepository codeRepository;
    @Autowired TelegramLinkRepository linkRepository;

    private final Scripted model = new Scripted();
    private InboxService inbox;

    private UUID master;
    private UUID bandery;
    private UUID sykhiv;
    private UUID draft;

    @BeforeEach
    void setUp() {
        inbox = service(60);
        String unique = UUID.randomUUID().toString().replace("-", "");
        master = userRepository.save(User.builder()
                .email(unique + "@majstr.test").emailCanonical(unique + "@majstr.test").passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.FREE).emailVerified(true).referralCode(unique.substring(0, 10))
                .build()).getId();
        UUID client = insert("""
                insert into clients (id, owner_id, full_name, phone, client_type, created_at)
                values (?, ?, 'Ігор', '+380991112233', 'PERSON', now())""", master);
        sykhiv = project(client, "Сихів", "2026-01-01");
        bandery = project(client, "Бандери 12", "2026-02-01");
        draft = insert("""
                insert into estimates (id, project_id, name, status, kind, count_in_economy,
                        portal_visible, version, created_at, updated_at)
                values (?, ?, 'Кошторис', 'DRAFT', 'REGULAR', true, false, 0, now(), now())""", bandery);
        insert("""
                insert into catalog_items (id, owner_id, name, trade, type, unit, default_price,
                        source, sort_order, created_at)
                values (?, ?, 'Демонтаж плитки', 'PAINTER', 'WORK', 'M2', 250.00, 'MANUAL', 0, now())""", master);
    }

    @Test
    void aNamedObjectGetsItsPurchaseTaskAndNote_andUndoTakesThemBack() {
        model.answer = objects -> List.of(
                parsed("SHOPPING", n(objects, "Бандери 12"), true, "клей для плитки", "3", "шт", null),
                parsed("TASK", n(objects, "Бандери 12"), true, "замовити двері", null, null, null),
                parsed("NOTE", n(objects, "Бандери 12"), true, "код від підʼїзду 4512", null, null, null));

        InboxService.Outcome done = send("на бандери 3 мішки клею, замовити двері, код 4512");

        assertThat(done.entry().status()).isEqualTo(InboxEntry.Status.DONE);
        assertThat(done.entry().items()).allMatch(i -> i.status() == InboxItem.Status.APPLIED);
        assertThat(count("""
                select count(*) from shopping_list_item i join shopping_list l on l.id = i.shopping_list_id
                where l.project_id = ? and i.name = 'Клей для плитки' and i.quantity = 3""", bandery)).isEqualTo(1);
        assertThat(count("select count(*) from project_task where project_id = ? and body = 'Замовити двері'",
                bandery)).isEqualTo(1);
        assertThat(count("select count(*) from project_note where project_id = ?", bandery)).isEqualTo(1);

        InboxService.Outcome undone = inbox.undo(master, done.entry().id());

        assertThat(undone.entry().items()).allMatch(i -> i.status() == InboxItem.Status.DISCARDED);
        assertThat(count("""
                select count(*) from shopping_list_item i join shopping_list l on l.id = i.shopping_list_id
                where l.project_id = ?""", bandery)).isZero();
        assertThat(count("select count(*) from project_task where project_id = ?", bandery)).isZero();
        assertThat(count("select count(*) from project_note where project_id = ?", bandery)).isZero();
    }

    @Test
    void anotherObjectMovesWhatWasFiled_andConfirmedMoneyStaysConfirmed() {
        model.answer = objects -> List.of(
                parsed("SHOPPING", n(objects, "Бандери 12"), true, "клей", "3", "шт", null),
                parsed("TASK", n(objects, "Бандери 12"), true, "замовити двері", null, null, null),
                parsed("EXPENSE", n(objects, "Бандери 12"), true, "плитка", null, null, "2000", "MATERIALS"));
        InboxService.Outcome filed = send("бандери клей 3, двері, 2000 на плитку");
        inbox.confirmMoney(master, filed.entry().id());

        InboxService.Outcome moved = inbox.moveToObject(master, filed.entry().id(), sykhiv);

        assertThat(moved.failures()).isEmpty();
        assertThat(moved.entry().items()).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(InboxItem.Status.APPLIED);
            assertThat(i.projectId()).isEqualTo(sykhiv);
        });
        assertThat(count("select count(*) from project_task where project_id = ?", bandery)).isZero();
        assertThat(count("select count(*) from project_task where project_id = ?", sykhiv)).isEqualTo(1);
        assertThat(count("""
                select count(*) from shopping_list_item i join shopping_list l on l.id = i.shopping_list_id
                where l.project_id = ?""", sykhiv)).isEqualTo(1);
        assertThat(count("select count(*) from object_expenses where object_id = ?", bandery)).isZero();
        assertThat(count("select count(*) from object_expenses where object_id = ? and amount = 2000", sykhiv))
                .isEqualTo(1);
    }

    @Test
    void aGuessedObjectIsAQuestion_andTheAnswerFilesIt() {
        // The model pointed at an object the text never named: that is a guess, not an answer.
        model.answer = objects -> List.of(
                parsed("TASK", n(objects, "Бандери 12"), false, "подзвонити електрику", null, null, null));

        InboxService.Outcome waiting = send("подзвонити електрику");

        InboxItemView item = waiting.entry().items().get(0);
        assertThat(item.status()).isEqualTo(InboxItem.Status.PENDING);
        assertThat(item.issue()).isEqualTo(InboxItem.Issue.OBJECT);
        assertThat(item.projectId()).isNull();
        assertThat(inbox.openCount(master)).isEqualTo(1);

        InboxService.Outcome filed = inbox.chooseObject(master, waiting.entry().id(), sykhiv);

        assertThat(filed.entry().items().get(0).status()).isEqualTo(InboxItem.Status.APPLIED);
        assertThat(count("select count(*) from project_task where project_id = ?", sykhiv)).isEqualTo(1);
        assertThat(inbox.openCount(master)).isZero();
    }

    @Test
    void moneyWaitsForConfirmation_thenLandsInTheObjectOrInMyMoney() {
        model.answer = objects -> List.of(
                parsed("EXPENSE", n(objects, "Бандери 12"), true, "плитка", null, null, "2000", "MATERIALS"),
                parsed("EXPENSE", 0, false, "пальне", null, null, "1500", "FUEL"),
                parsed("INCOME", n(objects, "Сихів"), true, "аванс", null, null, "10000", "ADVANCE"));

        InboxService.Outcome waiting = send("дав 2000 на плитку на бандери, заправився на 1500, сихів аванс 10000");

        assertThat(waiting.entry().items()).allMatch(i -> i.issue() == InboxItem.Issue.CONFIRM);
        assertThat(count("select count(*) from object_expenses where object_id = ?", bandery)).isZero();
        assertThat(count("select count(*) from cash_entry where owner_id = ?", master)).isZero();
        assertThat(count("select count(*) from payment_receipt where project_id = ?", sykhiv)).isZero();

        InboxService.Outcome confirmed = inbox.confirmMoney(master, waiting.entry().id());

        assertThat(confirmed.failures()).isEmpty();
        assertThat(count("select count(*) from object_expenses where object_id = ? and amount = 2000"
                + " and category = 'MATERIALS'", bandery)).isEqualTo(1);
        assertThat(count("select count(*) from cash_entry where owner_id = ? and amount = 1500"
                + " and direction = 'EXPENSE' and category = 'FUEL'", master)).isEqualTo(1);
        assertThat(count("select count(*) from payment_receipt where project_id = ? and amount = 10000",
                sykhiv)).isEqualTo(1);

        inbox.undo(master, waiting.entry().id());

        assertThat(count("select count(*) from object_expenses where object_id = ?", bandery)).isZero();
        assertThat(count("select count(*) from cash_entry where owner_id = ?", master)).isZero();
        assertThat(count("select count(*) from payment_receipt where project_id = ?", sykhiv)).isZero();
    }

    @Test
    void incomeOnAnObject_isAReceiptUnderItsOwnText() {
        model.answer = objects -> List.of(
                parsed("INCOME", n(objects, "Сихів"), true, "завдаток", null, null, "3000", "ADVANCE"));

        InboxService.Outcome waiting = send("отримав 3000 завдатку на сихові");
        InboxService.Outcome confirmed = inbox.confirmMoney(master, waiting.entry().id());

        assertThat(confirmed.failures()).isEmpty();
        assertThat(count("select count(*) from payment_receipt where project_id = ? and amount = 3000"
                + " and label = 'завдаток'", sykhiv)).isEqualTo(1);
    }

    @Test
    void anEstimateLineTakesItsCatalogPrice_intoTheOneDraft_andAnUnknownOneWaits() {
        model.answer = objects -> List.of(
                parsed("ESTIMATE_LINE", n(objects, "Бандери 12"), true, "демонтаж плитки", "12", "м2", null),
                parsed("ESTIMATE_LINE", n(objects, "Бандери 12"), true, "щось зовсім нове", "3", "шт", null));

        InboxService.Outcome done = send("бандери додати в кошторис демонтаж плитки 12 квадратів і щось нове 3 шт");

        assertThat(count("select count(*) from estimate_items where estimate_id = ? and name = 'Демонтаж плитки'"
                + " and quantity = 12 and unit_price = 250", draft)).isEqualTo(1);
        InboxItemView unknown = done.entry().items().get(1);
        assertThat(unknown.status()).isEqualTo(InboxItem.Status.PENDING);
        assertThat(unknown.issue()).isEqualTo(InboxItem.Issue.PRICE);

        // The master prices it in «Вхідні», and it is filed.
        inbox.updateItem(master, unknown.id(), new com.majstr.backend.dto.InboxItemUpdateRequest(
                null, null, null, null, null, null, null, new BigDecimal("90"), null, null));
        InboxService.Outcome applied = inbox.applyItem(master, unknown.id());

        assertThat(applied.failures()).isEmpty();
        assertThat(count("select count(*) from estimate_items where estimate_id = ?", draft)).isEqualTo(2);
        assertThat(applied.entry().status()).isEqualTo(InboxEntry.Status.DONE);
    }

    @Test
    void withEveryEstimateSignedTheLineAsks_andTheMastersTapCreatesExtraWorks() {
        jdbc.update("update estimates set status = 'SIGNED' where id = ?", draft);
        model.answer = objects -> List.of(
                parsed("ESTIMATE_LINE", n(objects, "Бандери 12"), true, "демонтаж плитки", "5", "м2", null));

        InboxService.Outcome waiting = send("бандери додати демонтаж плитки 5 квадратів");

        assertThat(waiting.entry().items().get(0).issue()).isEqualTo(InboxItem.Issue.NO_DRAFT);
        assertThat(count("select count(*) from estimate_items where estimate_id = ?", draft)).isZero();

        InboxService.Outcome filed = inbox.createDraft(master, waiting.entry().id());

        assertThat(filed.notice()).isNull();
        assertThat(filed.entry().items().get(0).status()).isEqualTo(InboxItem.Status.APPLIED);
        assertThat(count("""
                select count(*) from estimate_items i join estimates e on e.id = i.estimate_id
                where e.project_id = ? and e.name = 'Додаткові роботи' and e.status = 'DRAFT'
                  and i.name = 'Демонтаж плитки' and i.quantity = 5""", bandery)).isEqualTo(1);
    }

    @Test
    void atTheFreeEstimateCapNothingIsCreated_andTheLineKeepsWaiting() {
        jdbc.update("update estimates set status = 'SIGNED' where id = ?", draft);
        for (int i = 0; i < 2; i++) {
            insert("""
                    insert into estimates (id, project_id, name, status, kind, count_in_economy,
                            portal_visible, version, created_at, updated_at)
                    values (?, ?, 'Ще один', 'SIGNED', 'REGULAR', true, false, 0, now(), now())""", bandery);
        }
        model.answer = objects -> List.of(
                parsed("ESTIMATE_LINE", n(objects, "Бандери 12"), true, "демонтаж плитки", "5", "м2", null));

        InboxService.Outcome filed = inbox.createDraft(master, send("бандери демонтаж плитки 5 квадратів").entry().id());

        assertThat(filed.notice()).isEqualTo(InboxService.Notice.ESTIMATE_LIMIT);
        assertThat(filed.entry().items().get(0).issue()).isEqualTo(InboxItem.Issue.NO_DRAFT);
        assertThat(count("select count(*) from estimates where project_id = ?", bandery)).isEqualTo(3);
    }

    @Test
    void withTwoOpenEstimatesTheMasterPicksOne() {
        UUID second = insert("""
                insert into estimates (id, project_id, name, status, kind, count_in_economy,
                        portal_visible, version, created_at, updated_at)
                values (?, ?, 'Другий', 'DRAFT', 'REGULAR', true, false, 0, now(), now())""", bandery);
        model.answer = objects -> List.of(
                parsed("ESTIMATE_LINE", n(objects, "Бандери 12"), true, "демонтаж плитки", "5", "м2", null));

        InboxItemView item = send("бандери демонтаж плитки 5 квадратів").entry().items().get(0);
        assertThat(item.issue()).isEqualTo(InboxItem.Issue.ESTIMATE);

        inbox.updateItem(master, item.id(), new com.majstr.backend.dto.InboxItemUpdateRequest(
                null, null, null, second, null, null, null, null, null, null));
        inbox.applyItem(master, item.id());

        assertThat(count("select count(*) from estimate_items where estimate_id = ?", second)).isEqualTo(1);
        assertThat(count("select count(*) from estimate_items where estimate_id = ?", draft)).isZero();
    }

    @Test
    void aTelegramRedeliveryIsTheSameEntry() {
        long updateId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        InboxService.Outcome first = inbox.receive(master, InboxEntry.Source.TELEGRAM, "текст", updateId, 42L);
        InboxService.Outcome again = inbox.receive(master, InboxEntry.Source.TELEGRAM, "текст", updateId, 42L);

        assertThat(first.fresh()).isTrue();
        assertThat(again.fresh()).isFalse();
        assertThat(again.entry().id()).isEqualTo(first.entry().id());
    }

    @Test
    void overTheDailyBudgetTheMessageIsKept_andNothingIsParsed() {
        InboxService limited = service(1);
        model.answer = objects -> List.of(parsed("TASK", n(objects, "Сихів"), true, "раз", null, null, null));

        InboxService.Outcome first = limited.process(master,
                limited.receive(master, InboxEntry.Source.PWA, "раз", null, null).entry().id());
        InboxService.Outcome second = limited.process(master,
                limited.receive(master, InboxEntry.Source.PWA, "два", null, null).entry().id());

        assertThat(first.overBudget()).isFalse();
        assertThat(second.overBudget()).isTrue();
        assertThat(second.entry().status()).isEqualTo(InboxEntry.Status.FAILED);
        assertThat(second.entry().rawText()).isEqualTo("два");
    }

    @Test
    void tasksTickDone_andOpenOnesComeFirst() {
        TaskResponse first = taskService.add(bandery, master, new TaskRequest("перша"), null);
        taskService.add(bandery, master, new TaskRequest("друга"), null);

        taskService.update(bandery, first.id(), master, new TaskUpdateRequest(null, true));

        List<TaskResponse> list = taskService.list(bandery, master);
        assertThat(list).extracting(TaskResponse::body).containsExactly("друга", "перша");
        assertThat(list.get(1).done()).isTrue();
    }

    @Test
    void aLinkCodeBindsOnce_andAnExpiredOneNever() {
        String code = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        codeRepository.save(TelegramLinkCode.builder().code(code).userId(master)
                .expiresAt(Instant.now().plusSeconds(600)).build());
        long chat = Math.abs(UUID.randomUUID().getLeastSignificantBits());

        assertThat(linkService.bind(code, chat, "andriy")).contains(master);
        assertThat(linkService.userForChat(chat)).contains(master);
        assertThat(linkService.bind(code, chat, "andriy")).isEmpty();

        String stale = "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        codeRepository.save(TelegramLinkCode.builder().code(stale).userId(master)
                .expiresAt(Instant.now().minusSeconds(1)).build());
        assertThat(linkService.bind(stale, chat + 1, null)).isEmpty();

        linkService.unlink(master);
        assertThat(linkRepository.findById(master)).isEmpty();
    }

    // ---- fixture ----------------------------------------------------------

    // ---- review fixes (majster-bot-fixes) ----------------------------------------------------------

    @Test
    void twoTapsOnZapysatyAtOnceWriteTheMoneyOnce() throws Exception {
        model.answer = objects -> List.of(
                parsed("EXPENSE", n(objects, "Бандери 12"), true, "плитка", null, null, "2000", "MATERIALS"));
        UUID entry = send("дав 2000 на плитку на бандери").entry().id();

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService two = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<?>> taps = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            taps.add(two.submit(() -> {
                go.await();
                return inbox.confirmMoney(master, entry);
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<?> tap : taps) {
            tap.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        two.shutdown();

        assertThat(count("select count(*) from object_expenses where object_id = ? and amount = 2000", bandery))
                .isEqualTo(1);
    }

    @Test
    void aVoiceNoteIsHeardThenParsed_andOnlyTheTextIsKept() {
        speechToText.heard = "на бандери докупити 3 мішки клею";
        model.answer = objects -> List.of(
                parsed("SHOPPING", n(objects, "Бандери 12"), true, "клей", "3", "шт", null));

        InboxService.Outcome received = inbox.receiveVoice(master, 777_001L, 5L, "file-1", 7, false);
        InboxService.Outcome done = inbox.process(master, received.entry().id());

        assertThat(done.entry().voice()).isTrue();
        assertThat(done.entry().rawText()).isEqualTo("на бандери докупити 3 мішки клею");
        assertThat(speechToText.objectNames).contains("Бандери 12");
        InboxEntry stored = entryRepository.findById(received.entry().id()).orElseThrow();
        assertThat(stored.getVoiceFileId()).isNull();
        assertThat(stored.getVoiceSeconds()).isEqualTo(7);
        assertThat(count("select count(*) from shopping_list_item i join shopping_list l on l.id = i.shopping_list_id"
                + " where l.project_id = ?", bandery)).isEqualTo(1);
    }

    @Test
    void aFailedTranscriptionIsFailed_andRetryHearsItAgain() {
        speechToText.fail = true;
        InboxService.Outcome received = inbox.receiveVoice(master, 777_002L, 5L, "file-2", 5, false);

        assertThat(inbox.process(master, received.entry().id()).entry().status()).isEqualTo(InboxEntry.Status.FAILED);
        assertThat(entryRepository.findById(received.entry().id()).orElseThrow().getVoiceFileId()).isEqualTo("file-2");

        speechToText.fail = false;
        speechToText.heard = "подзвонити електрику";
        model.answer = objects -> List.of(parsed("TASK", 0, false, "подзвонити електрику", null, null, null));
        assertThat(inbox.retry(master, received.entry().id()).entry().rawText()).isEqualTo("подзвонити електрику");
    }

    @Test
    void aVoiceNoteRedeliveredIsOneEntry() {
        UUID first = inbox.receiveVoice(master, 777_003L, 5L, "file-3", 5, false).entry().id();
        InboxService.Outcome again = inbox.receiveVoice(master, 777_003L, 5L, "file-3", 5, false);

        assertThat(again.fresh()).isFalse();
        assertThat(again.entry().id()).isEqualTo(first);
    }

    @Test
    void aForwardedMessageFilesNothingByItself_untilTheMasterTapsZapysaty() {
        model.answer = objects -> List.of(
                parsed("TASK", n(objects, "Бандери 12"), true, "переробити стелю", null, null, null));

        InboxService.Outcome received = inbox.receive(master, InboxEntry.Source.TELEGRAM, "переробити стелю на бандери",
                null, null, null, true);
        InboxItemView item = inbox.process(master, received.entry().id()).entry().items().get(0);

        assertThat(item.issue()).isEqualTo(InboxItem.Issue.CONFIRM);
        assertThat(count("select count(*) from project_task where project_id = ?", bandery)).isZero();

        inbox.confirmMoney(master, received.entry().id());
        assertThat(count("select count(*) from project_task where project_id = ?", bandery)).isEqualTo(1);
    }

    @Test
    void aTaskIdFromAnotherObjectIsNotFound_neverForbidden() {
        UUID id = UUID.randomUUID();
        taskService.add(sykhiv, master, new com.majstr.backend.dto.TaskRequest("перша"), id);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        taskService.add(bandery, master, new com.majstr.backend.dto.TaskRequest("друга"), id))
                .isInstanceOf(com.majstr.backend.exception.ResourceNotFoundException.class);
    }

    @Test
    void theTrainingSampleIsNeverOffered() {
        jdbc.update("update projects set sample = true where id = ?", sykhiv);

        assertThat(inbox.objects(master)).extracting(InboxService.ObjectChoice::name).containsExactly("Бандери 12");
    }

    @Test
    void aFreeAccountWithAnUnconfirmedEmailGetsNoParse() {
        jdbc.update("update users set email_verified = false where id = ?", master);
        boolean[] asked = {false};
        model.answer = objects -> {
            asked[0] = true;
            return List.of();
        };

        InboxService.Outcome done = send("бандери клей 3 мішки");

        assertThat(asked[0]).isFalse();
        assertThat(done.entry().status()).isEqualTo(InboxEntry.Status.FAILED);
        assertThat(done.notice()).isEqualTo(InboxService.Notice.VERIFY_EMAIL);
    }

    @Test
    void twoUndosAtOnceDeleteTheMoneyOnce_andNeitherFails() throws Exception {
        model.answer = objects -> List.of(
                parsed("EXPENSE", n(objects, "Бандери 12"), true, "плитка", null, null, "2000", "MATERIALS"));
        UUID entry = send("дав 2000 на плитку на бандери").entry().id();
        inbox.confirmMoney(master, entry);

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService two = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<InboxService.Outcome>> taps = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            taps.add(two.submit(() -> {
                go.await();
                return inbox.undo(master, entry);
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<InboxService.Outcome> tap : taps) {
            assertThat(tap.get(30, java.util.concurrent.TimeUnit.SECONDS).failures()).isEmpty();
        }
        two.shutdown();

        assertThat(count("select count(*) from object_expenses where object_id = ?", bandery)).isZero();
    }

    @Test
    void aQuickAddSentTwiceAtOnceIsOneEntry() throws Exception {
        UUID requested = UUID.randomUUID();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService two = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<InboxService.Outcome>> sends = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            sends.add(two.submit(() -> {
                go.await();
                return inbox.receive(master, InboxEntry.Source.PWA, "клей", null, null, requested);
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<InboxService.Outcome> sent : sends) {
            assertThat(sent.get(30, java.util.concurrent.TimeUnit.SECONDS).entry().id()).isEqualTo(requested);
        }
        two.shutdown();

        assertThat(count("select count(*) from inbox_entry where owner_id = ?", master)).isEqualTo(1);
    }

    @Test
    void aSentEstimateIsNotFilledByItself_onlyWhenTheMasterNamesIt() {
        jdbc.update("update estimates set status = 'SENT' where id = ?", draft);
        model.answer = objects -> List.of(
                parsed("ESTIMATE_LINE", n(objects, "Бандери 12"), true, "демонтаж плитки", "5", "м2", null));

        InboxItemView item = send("бандери демонтаж плитки 5 квадратів").entry().items().get(0);

        // Not «every estimate is signed — create one?»: one is open, the master picks it.
        assertThat(item.issue()).isEqualTo(InboxItem.Issue.ESTIMATE);
        assertThat(count("select count(*) from estimate_items where estimate_id = ?", draft)).isZero();

        inbox.updateItem(master, item.id(), new com.majstr.backend.dto.InboxItemUpdateRequest(
                null, null, null, draft, null, null, null, null, null, null));
        inbox.applyItem(master, item.id());

        assertThat(count("select count(*) from estimate_items where estimate_id = ?", draft)).isEqualTo(1);
    }

    @Test
    void anyFailureLeavesTheEntryFailed_andOnlyAFailedOneIsParsedAgain() {
        // An object number the model invented: the parse throws past the model call.
        model.answer = objects -> List.of(parsed("TASK", 99, true, "купити дюбелі", null, null, null));
        InboxService.Outcome broken = send("щось");
        assertThat(broken.entry().status()).isEqualTo(InboxEntry.Status.FAILED);

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        model.answer = objects -> {
            calls.incrementAndGet();
            return List.of(parsed("TASK", n(objects, "Сихів"), true, "купити дюбелі", null, null, null));
        };
        InboxService.Outcome parsing = inbox.receive(master, InboxEntry.Source.PWA, "сихів купити дюбелі", null, null);
        assertThat(inbox.retry(master, parsing.entry().id()).fresh()).as("PARSING is not retried").isFalse();
        assertThat(calls.get()).isZero();

        assertThat(inbox.retry(master, broken.entry().id()).entry().status()).isNotEqualTo(InboxEntry.Status.FAILED);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aParseCutShortTurnsFailedAfterTenMinutes() {
        UUID entry = inbox.receive(master, InboxEntry.Source.PWA, "текст", null, null).entry().id();
        jdbc.update("update inbox_entry set updated_at = now() - interval '11 minutes' where id = ?", entry);

        inbox.failStaleParses();

        assertThat(entryRepository.findById(entry).orElseThrow().getStatus()).isEqualTo(InboxEntry.Status.FAILED);
    }

    private InboxService.Outcome send(String text) {
        InboxService.Outcome received = inbox.receive(master, InboxEntry.Source.PWA, text, null, null);
        return inbox.process(master, received.entry().id());
    }

    private InboxService service(int parsesPerDay) {
        InboxParseRateLimiter limiter = new InboxParseRateLimiter(
                new BotProperties("", "", "", "off", parsesPerDay, ""));
        return new InboxService(entryRepository, itemRepository, projectRepository, estimateRepository,
                catalogItemRepository, synonymRepository, model, limiter, projectService, shoppingListService,
                taskService, noteService, estimateService, expenseService, paymentService, cashFlowService,
                transactionManager, aiAccess, speechToText, voiceAudio, userRepository);
    }

    private static int n(List<ObjectRef> objects, String name) {
        return objects.stream().filter(o -> o.name().equals(name)).findFirst().orElseThrow().number();
    }

    private static Parsed parsed(String kind, int object, boolean mentioned, String text,
                                 String quantity, String unit, String unitPrice) {
        return new Parsed(kind, object, mentioned, text, decimal(quantity), unit, decimal(unitPrice), null, null);
    }

    private static Parsed parsed(String kind, int object, boolean mentioned, String text,
                                 String quantity, String unit, String amount, String category) {
        return new Parsed(kind, object, mentioned, text, decimal(quantity), unit, null, decimal(amount), category);
    }

    private static BigDecimal decimal(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private UUID project(UUID client, String name, String createdOn) {
        return insert("""
                insert into projects (id, owner_id, client_id, name, address, status,
                        estimates_created, estimates_deleted, created_at, updated_at)
                values (?, ?, ?, ?, 'вул. Тестова, 1', 'IN_PROGRESS', 1, 0, ?::timestamptz, now())""",
                master, client, name, createdOn);
    }

    private long count(String sql, Object arg) {
        Long n = jdbc.queryForObject(sql, Long.class, arg);
        return n == null ? 0 : n;
    }

    private UUID insert(String sql, Object... args) {
        UUID id = UUID.randomUUID();
        Object[] bind = new Object[args.length + 1];
        bind[0] = id;
        System.arraycopy(args, 0, bind, 1, args.length);
        jdbc.update(sql, bind);
        return id;
    }

    /** The model, replaced by whatever the test scripts for the objects it is shown. */
    private static final class FakeSpeech extends com.majstr.backend.service.ai.SpeechToText {
        String heard = "";
        boolean fail;
        List<String> objectNames = List.of();

        FakeSpeech() {
            super(new com.majstr.backend.config.OpenAiProperties("key", "m", 1, ""),
                    new com.majstr.backend.config.VoiceProperties("gpt-4o-mini-transcribe", 60, 10, 900));
        }

        @Override
        public String transcribe(byte[] audio, List<String> names) {
            if (fail) {
                throw new com.majstr.backend.exception.AiExtractionException("error.ai.unavailable");
            }
            objectNames = names;
            return heard;
        }
    }

    private static final class Scripted extends InboxExtractor {
        Function<List<ObjectRef>, List<Parsed>> answer = objects -> List.of();

        Scripted() {
            super(null, null);
        }

        @Override
        public List<Parsed> extract(String message, List<ObjectRef> objects) {
            return answer.apply(objects);
        }
    }
}
