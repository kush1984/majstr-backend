package com.majstr.backend.service.inbox;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import com.majstr.backend.repository.ClientRepository;
import com.majstr.backend.entity.Client;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import com.majstr.backend.config.BackgroundErrors;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.service.ai.AiAccess;
import com.majstr.backend.service.ai.SpeechToText;
import com.majstr.backend.dto.CashEntryKind;
import com.majstr.backend.dto.CashEntryRequest;
import com.majstr.backend.dto.EstimateItemResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.InboxEntryView;
import com.majstr.backend.dto.InboxItemUpdateRequest;
import com.majstr.backend.dto.InboxItemView;
import com.majstr.backend.dto.NoteRequest;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.ShoppingListItemRequest;
import com.majstr.backend.dto.TaskRequest;
import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.CashDirection;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogItemSynonym;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.InboxEntry;
import com.majstr.backend.entity.InboxItem;
import com.majstr.backend.entity.InboxItem.Issue;
import com.majstr.backend.entity.InboxItem.Kind;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.exception.ObjectReadOnlyException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogItemSynonymRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.InboxEntryRepository;
import com.majstr.backend.repository.InboxItemRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.CatalogFiling;
import com.majstr.backend.service.CashFlowService;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.EstimateService.ImportEstimateData.ImportItem;
import com.majstr.backend.service.ObjectExpenseService;
import com.majstr.backend.service.PaymentService;
import com.majstr.backend.service.ProjectNoteService;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.service.ProjectTaskService;
import com.majstr.backend.service.ShoppingListService;
import com.majstr.backend.service.importer.CatalogMatcher;
import com.majstr.backend.service.importer.UnitNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The inbox: every bot message and PWA quick add lands here first, verbatim; the parse turns it into
 * {@link InboxItem}s; an item is applied through the record's OWN service — never a new write path
 * (docs/iteration-majster-bot.md, rules 1-6).
 *
 * <p>Deliberately NOT {@code @Transactional}: the model call must never pin a connection, and every
 * apply is its own transaction in its own service, so one refused item (a label collision, an
 * estimate signed in between) leaves the rest applied and itself waiting.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboxService {

    /** Objects shown to the model; an older object beyond this is still reachable from the PWA. */
    static final int MAX_OBJECTS = 40;

    private static final Set<Kind> MONEY = EnumSet.of(Kind.EXPENSE, Kind.INCOME);
    private static final Set<InboxEntry.Status> OPEN = EnumSet.of(InboxEntry.Status.NEW, InboxEntry.Status.FAILED);

    private final InboxEntryRepository entryRepository;
    private final InboxItemRepository itemRepository;
    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final CatalogItemSynonymRepository synonymRepository;
    private final InboxExtractor extractor;
    private final InboxParseRateLimiter rateLimiter;
    private final ProjectService projectService;
    private final ShoppingListService shoppingListService;
    private final ProjectTaskService taskService;
    private final ProjectNoteService noteService;
    private final EstimateService estimateService;
    private final ObjectExpenseService expenseService;
    private final PaymentService paymentService;
    private final CashFlowService cashFlowService;
    private final PlatformTransactionManager transactionManager;
    private final AiAccess aiAccess;
    private final SpeechToText speechToText;
    private final VoiceAudio voiceAudio;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;

    /** An object the master can file things under. */
    public record ObjectChoice(UUID id, String name) {}

    /** What one round of processing did — the bot answers from it. {@code failures} are item texts. */
    public record Outcome(InboxEntryView entry, boolean fresh, boolean overBudget, List<String> failures,
                          Notice notice) {
        public Outcome(InboxEntryView entry, boolean fresh, boolean overBudget, List<String> failures) {
            this(entry, fresh, overBudget, failures, null);
        }
    }

    /** Something the master must hear that is not about one item. */
    /** OBJECT_READ_ONLY: the object is not his to change on his plan (iteration-read-only-objects). */
    public enum Notice { ESTIMATE_LIMIT, VERIFY_EMAIL, OBJECT_READ_ONLY }

    /** The name of the estimate a master's tap creates for lines that found every estimate signed. */
    static final String EXTRA_WORKS = "Додаткові роботи";

    /** How long a parse may run before it counts as cut short (a model call takes under a minute). */
    static final Duration STALE_PARSE = Duration.ofMinutes(10);

    // ---- intake ---------------------------------------------------------------------------------

    /**
     * Store a message before anything else happens. A Telegram update seen before returns the first
     * entry with {@code fresh = false} — Telegram redelivers on any non-2xx, and a model call per
     * redelivery would be paid twice and file things twice.
     */
    public Outcome receive(UUID ownerId, InboxEntry.Source source, String text, Long updateId, Long chatId) {
        return receive(ownerId, source, text, updateId, chatId, null);
    }

    /** {@code requestedId}: the PWA's {@code X-Entity-Uuid}, so a replayed offline quick add is one entry. */
    public Outcome receive(UUID ownerId, InboxEntry.Source source, String text, Long updateId, Long chatId,
                           UUID requestedId) {
        return receive(ownerId, source, text, updateId, chatId, requestedId, false);
    }

    /** {@code forwarded}: someone else's words the master passed on — nothing in them is filed by itself. */
    public Outcome receive(UUID ownerId, InboxEntry.Source source, String text, Long updateId, Long chatId,
                           UUID requestedId, boolean forwarded) {
        if (requestedId != null) {
            Optional<InboxEntry> seen = entryRepository.findById(requestedId);
            if (seen.isPresent()) {
                if (!seen.get().getOwnerId().equals(ownerId)) {
                    throw new ResourceNotFoundException("Inbox entry not found: " + requestedId);
                }
                return new Outcome(view(seen.get()), false, false, List.of());
            }
        }
        if (updateId != null) {
            Optional<InboxEntry> seen = entryRepository.findByTelegramUpdateId(updateId);
            if (seen.isPresent()) {
                return new Outcome(view(seen.get()), false, false, List.of());
            }
        }
        InboxEntry entry = InboxEntry.builder()
                .id(requestedId)
                .ownerId(ownerId)
                .source(source)
                .rawText(text.trim())
                .status(InboxEntry.Status.PARSING)
                .telegramUpdateId(updateId)
                .telegramChatId(chatId)
                .forwarded(forwarded)
                .build();
        try {
            entry = entryRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException race) {
            // Two deliveries at once — of one Telegram update, or one offline quick add: the other won the key.
            InboxEntry won = (requestedId != null
                    ? entryRepository.findById(requestedId).filter(e -> e.getOwnerId().equals(ownerId))
                    : entryRepository.findByTelegramUpdateId(updateId))
                    .orElseThrow(() -> race);
            return new Outcome(view(won), false, false, List.of());
        }
        return new Outcome(view(entry), true, false, List.of());
    }

    /**
     * A voice note, stored before its text exists: the transcription is part of the parse, so a failed one
     * is a FAILED entry «Спробувати ще раз» transcribes again (Telegram keeps the file for at least an hour).
     */
    public Outcome receiveVoice(UUID ownerId, long updateId, long chatId, String fileId, int seconds,
                                boolean forwarded) {
        Optional<InboxEntry> seen = entryRepository.findByTelegramUpdateId(updateId);
        if (seen.isPresent()) {
            return new Outcome(view(seen.get()), false, false, List.of());
        }
        InboxEntry entry = InboxEntry.builder()
                .ownerId(ownerId)
                .source(InboxEntry.Source.TELEGRAM)
                .rawText("")
                .status(InboxEntry.Status.PARSING)
                .telegramUpdateId(updateId)
                .telegramChatId(chatId)
                .forwarded(forwarded)
                .voiceSeconds(seconds)
                .voiceFileId(fileId)
                .build();
        try {
            entry = entryRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException race) {
            InboxEntry won = entryRepository.findByTelegramUpdateId(updateId).orElseThrow(() -> race);
            return new Outcome(view(won), false, false, List.of());
        }
        return new Outcome(view(entry), true, false, List.of());
    }

    /**
     * Parse a stored message and file what can be filed — called once, by whoever stored it (or by
     * {@link #retry} after it won a FAILED entry). Any failure leaves the entry FAILED, never PARSING:
     * a PARSING entry is invisible in «Вхідні» (review M-3).
     */
    public Outcome process(UUID ownerId, UUID entryId) {
        try {
            return parse(ownerId, entryId);
        } catch (RuntimeException e) {
            log.warn("Inbox entry {} failed: {}", entryId, e.getMessage());
            BackgroundErrors.capture("inbox-parse", e);
            InboxEntry entry = loadEntry(ownerId, entryId);
            if (entry.getStatus() == InboxEntry.Status.PARSING) {
                entry.setStatus(InboxEntry.Status.FAILED);
                entryRepository.save(entry);
            }
            return new Outcome(view(entry), true, false, List.of());
        }
    }

    /** «Спробувати ще раз»: only a FAILED entry is parsed again, and only by one caller (review M-2). */
    public Outcome retry(UUID ownerId, UUID entryId) {
        InboxEntry entry = loadEntry(ownerId, entryId);
        Integer won = new TransactionTemplate(transactionManager)
                .execute(status -> entryRepository.claimRetry(entryId, ownerId, Instant.now()));
        if (won == null || won == 0) {
            return new Outcome(view(entry), false, false, List.of());
        }
        return process(ownerId, entryId);
    }

    /** A parse a deploy cut short stays PARSING forever otherwise — turn it FAILED after ten minutes. */
    @Scheduled(fixedDelay = 5, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void failStaleParses() {
        Instant now = Instant.now();
        Integer failed = new TransactionTemplate(transactionManager)
                .execute(status -> entryRepository.failStale(now.minus(STALE_PARSE), now));
        if (failed != null && failed > 0) {
            log.info("Inbox: {} interrupted parse(s) marked FAILED", failed);
        }
    }

    private Outcome parse(UUID ownerId, UUID entryId) {
        InboxEntry entry = loadEntry(ownerId, entryId);
        try {
            // The same door as dictation (B-125): a FREE account with an unconfirmed address gets no model call.
            aiAccess.requireAllowed(ownerId);
        } catch (EmailNotVerifiedException e) {
            entry.setStatus(InboxEntry.Status.FAILED);
            entryRepository.save(entry);
            return new Outcome(view(entry), true, false, List.of(), Notice.VERIFY_EMAIL);
        }
        if (!rateLimiter.tryConsume(ownerId)) {
            entry.setStatus(InboxEntry.Status.FAILED);
            entryRepository.save(entry);
            return new Outcome(view(entry), true, true, List.of());
        }
        List<ObjectChoice> objects = activeObjects(ownerId);
        if (entry.getVoiceFileId() != null) {
            // The audio is fetched and heard here, outside any transaction, and only the text is kept.
            List<String> objectNames = objects.stream().map(ObjectChoice::name).toList();
            String heard = speechToText.transcribe(voiceAudio.fetch(entry.getVoiceFileId()),
                    objectNames, peopleNames(ownerId, objectNames));
            entry.setRawText(heard);
            entry.setVoiceFileId(null);
            entry = entryRepository.save(entry);
            if (heard.isBlank()) {
                entry.setStatus(InboxEntry.Status.NEW);
                entryRepository.save(entry);
                return new Outcome(view(entry), true, false, List.of());
            }
        }
        List<InboxExtractor.Parsed> parsed;
        try {
            parsed = extractor.extract(entry.getRawText(), objectRefs(ownerId, objects));
        } catch (RuntimeException e) {
            log.warn("Inbox parse failed for entry {}: {}", entryId, e.getMessage());
            entry.setStatus(InboxEntry.Status.FAILED);
            entryRepository.save(entry);
            return new Outcome(view(entry), true, false, List.of());
        }

        Catalog catalog = null;
        List<InboxItem> items = new ArrayList<>();
        int order = 0;
        for (InboxExtractor.Parsed p : parsed) {
            Kind kind = kindOf(p.kind());
            if (kind == null) {
                continue;
            }
            InboxItem item = InboxItem.builder()
                    .entryId(entryId)
                    .kind(kind)
                    .status(InboxItem.Status.PENDING)
                    .projectId(p.objectMentioned() ? objects.get(p.object() - 1).id() : null)
                    .text(truncate(p.text(), 2000))
                    .quantity(p.quantity())
                    .unit(UnitNormalizer.normalize(p.unit()))
                    .unitPrice(p.unitPrice())
                    .amount(p.amount())
                    .category(categoryOf(p.category(), kind))
                    .sortOrder(order++)
                    .build();
            if (kind == Kind.ESTIMATE_LINE) {
                if (catalog == null) {
                    catalog = loadCatalog(ownerId);
                }
                price(item, catalog);
            }
            items.add(item);
        }
        // Written under the entry's row lock, and only while it is still PARSING: a second run that got
        // here too (a replayed request, a double retry) files nothing a second time (review M-2).
        Boolean written = new TransactionTemplate(transactionManager).execute(status -> {
            InboxEntry locked = entryRepository.lockById(entryId).orElse(null);
            if (locked == null || locked.getStatus() != InboxEntry.Status.PARSING) {
                return false;
            }
            // Clear the old parse when this is a retry of a FAILED entry.
            itemRepository.deleteAll(itemRepository.findByEntryIdOrderBySortOrderAsc(entryId));
            for (InboxItem item : items) {
                evaluate(ownerId, item);
            }
            itemRepository.saveAll(items);
            // Out of PARSING inside the lock; settle() below puts the real status.
            locked.setStatus(InboxEntry.Status.NEW);
            entryRepository.save(locked);
            return true;
        });
        if (!Boolean.TRUE.equals(written)) {
            return new Outcome(view(loadEntry(ownerId, entryId)), false, false, List.of());
        }
        List<String> failures = applyReady(ownerId, items);
        return new Outcome(settle(loadEntry(ownerId, entryId)), true, false, failures);
    }

    // ---- the master's answers -------------------------------------------------------------------

    /**
     * The master picked an object for what was waiting on one ({@code projectId} null = «без
     * обʼєкта»). Items that only lacked an object are filed at once; money still waits to be confirmed.
     */
    public Outcome chooseObject(UUID ownerId, UUID entryId, UUID projectId) {
        InboxEntry entry = loadEntry(ownerId, entryId);
        if (projectId != null) {
            projectService.loadOwned(projectId, ownerId);
        }
        List<InboxItem> items = itemRepository.findByEntryIdOrderBySortOrderAsc(entryId);
        for (InboxItem item : items) {
            boolean waitsForObject = item.getIssue() == Issue.OBJECT
                    || (MONEY.contains(item.getKind()) && item.getProjectId() == null);
            if (item.getStatus() == InboxItem.Status.PENDING && waitsForObject) {
                item.setProjectId(projectId);
                item.setEstimateId(null);
                evaluate(ownerId, item);
            }
        }
        itemRepository.saveAll(items);
        List<String> failures = applyReady(ownerId, items);
        return new Outcome(settle(entry), true, false, failures);
    }

    /** «Записати»: the master confirms the money in this message. */
    public Outcome confirmMoney(UUID ownerId, UUID entryId) {
        InboxEntry entry = loadEntry(ownerId, entryId);
        List<String> failures = new ArrayList<>();
        for (InboxItem item : itemRepository.findByEntryIdOrderBySortOrderAsc(entryId)) {
            if (item.getStatus() == InboxItem.Status.PENDING && item.getIssue() == Issue.CONFIRM) {
                apply(ownerId, item).ifPresent(failures::add);
            }
        }
        return new Outcome(settle(entry), true, false, failures);
    }

    /**
     * «Новий кошторис «Додаткові роботи»»: the master's tap, for lines that found every estimate on
     * their object signed. One new DRAFT per object, created through {@code EstimateService} so the
     * FREE per-object cap still holds — at the cap nothing is created and the lines keep waiting.
     */
    public Outcome createDraft(UUID ownerId, UUID entryId) {
        InboxEntry entry = loadEntry(ownerId, entryId);
        List<InboxItem> items = itemRepository.findByEntryIdOrderBySortOrderAsc(entryId);
        Notice notice = null;
        Set<UUID> objects = items.stream()
                .filter(i -> i.getStatus() == InboxItem.Status.PENDING && i.getIssue() == Issue.NO_DRAFT)
                .map(InboxItem::getProjectId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (UUID project : objects) {
            if (!openEstimates(project).isEmpty()) {
                continue; // one appeared since (another message's tap, or the app) — use it, don't add one
            }
            try {
                estimateService.createForProject(project, new EstimateCreateRequest(null, null, EXTRA_WORKS), ownerId);
            } catch (LimitExceededException e) {
                notice = Notice.ESTIMATE_LIMIT;
            } catch (ObjectReadOnlyException e) {
                notice = Notice.OBJECT_READ_ONLY;
            }
        }
        for (InboxItem item : items) {
            if (item.getStatus() == InboxItem.Status.PENDING && item.getIssue() == Issue.NO_DRAFT) {
                evaluate(ownerId, item);
            }
        }
        itemRepository.saveAll(items);
        List<String> failures = applyReady(ownerId, items);
        return new Outcome(settle(entry), true, false, failures, notice);
    }

    /**
     * «Інший обʼєкт»: the bot filed this message under the wrong object. Everything filed is taken
     * back through its own service and filed again under {@code projectId} ({@code null} = none, where
     * a task or a purchase can only wait). Money the master already confirmed stays confirmed — he is
     * moving it, not asking again. A record its service refuses to take back (an estimate line whose
     * estimate got signed since) stays where it is and is named in {@code failures}.
     */
    public Outcome moveToObject(UUID ownerId, UUID entryId, UUID projectId) {
        InboxEntry entry = loadEntry(ownerId, entryId);
        if (projectId != null) {
            projectService.loadOwned(projectId, ownerId);
        }
        List<String> failures = new ArrayList<>();
        List<InboxItem> items = itemRepository.findByEntryIdOrderBySortOrderAsc(entryId);
        List<InboxItem> confirmedMoney = new ArrayList<>();
        for (InboxItem item : items) {
            if (item.getStatus() == InboxItem.Status.DISCARDED || java.util.Objects.equals(item.getProjectId(), projectId)) {
                continue;
            }
            if (item.getStatus() == InboxItem.Status.APPLIED) {
                try {
                    if (!takeBack(ownerId, item, InboxItem.Status.PENDING)) {
                        continue; // another tap took it back first
                    }
                } catch (RuntimeException e) {
                    log.info("Inbox move of item {} refused: {}", item.getId(), e.getMessage());
                    failures.add(item.getText());
                    continue;
                }
                if (MONEY.contains(item.getKind())) {
                    confirmedMoney.add(item);
                }
            }
            item.setProjectId(projectId);
            item.setEstimateId(null);
            evaluate(ownerId, item);
        }
        itemRepository.saveAll(items);
        for (InboxItem item : confirmedMoney) {
            if (item.getIssue() == Issue.CONFIRM) {
                apply(ownerId, item).ifPresent(failures::add);
            }
        }
        failures.addAll(applyReady(ownerId, items));
        return new Outcome(settle(entry), true, false, failures);
    }

    /** The message as it stands — for the bot to redraw its answer. */
    public Outcome current(UUID ownerId, UUID entryId) {
        return new Outcome(settle(loadEntry(ownerId, entryId)), true, false, List.of());
    }

    /** «Скасувати»: delete what this message filed, through the same services, and forget the rest. */
    public Outcome undo(UUID ownerId, UUID entryId) {
        InboxEntry entry = loadEntry(ownerId, entryId);
        List<String> failures = new ArrayList<>();
        for (InboxItem item : itemRepository.findByEntryIdOrderBySortOrderAsc(entryId)) {
            if (item.getStatus() == InboxItem.Status.APPLIED) {
                try {
                    takeBack(ownerId, item, InboxItem.Status.DISCARDED);
                } catch (RuntimeException e) {
                    log.info("Inbox undo of item {} refused: {}", item.getId(), e.getMessage());
                    failures.add(item.getText());
                }
                continue;
            }
            item.setStatus(InboxItem.Status.DISCARDED);
            itemRepository.save(item);
        }
        return new Outcome(settle(entry), true, false, failures);
    }

    // ---- the PWA's «Вхідні» ---------------------------------------------------------------------

    public List<InboxEntryView> list(UUID ownerId) {
        List<InboxEntry> entries = entryRepository.findTop100ByOwnerIdAndStatusInOrderByCreatedAtDesc(ownerId, OPEN);
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<InboxItem>> byEntry = itemRepository
                .findByEntryIdInOrderBySortOrderAsc(entries.stream().map(InboxEntry::getId).toList())
                .stream().collect(Collectors.groupingBy(InboxItem::getEntryId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, String> names = projectNames(ownerId);
        return entries.stream()
                .map(e -> view(e, byEntry.getOrDefault(e.getId(), List.of()), names))
                .toList();
    }

    public long openCount(UUID ownerId) {
        return entryRepository.countByOwnerIdAndStatusIn(ownerId, OPEN);
    }

    public List<ObjectChoice> objects(UUID ownerId) {
        return activeObjects(ownerId);
    }

    public InboxEntryView updateItem(UUID ownerId, UUID itemId, InboxItemUpdateRequest req) {
        InboxItem item = loadPendingItem(ownerId, itemId);
        if (req.kind() != null) {
            item.setKind(req.kind());
        }
        if (Boolean.TRUE.equals(req.clearProject())) {
            item.setProjectId(null);
        } else if (req.projectId() != null) {
            projectService.loadOwned(req.projectId(), ownerId);
            item.setProjectId(req.projectId());
        }
        if (req.text() != null && !req.text().isBlank()) {
            item.setText(req.text().trim());
        }
        if (req.quantity() != null) {
            item.setQuantity(req.quantity());
        }
        if (req.unit() != null) {
            item.setUnit(req.unit());
        }
        if (req.unitPrice() != null) {
            item.setUnitPrice(req.unitPrice());
        }
        if (req.amount() != null) {
            item.setAmount(req.amount());
        }
        if (req.category() != null) {
            item.setCategory(req.category());
        }
        if (req.estimateId() != null) {
            item.setEstimateId(openEstimate(item, req.estimateId()));
        } else if (req.projectId() != null || Boolean.TRUE.equals(req.clearProject()) || req.kind() != null) {
            item.setEstimateId(null);
        }
        if (item.getKind() == Kind.ESTIMATE_LINE && item.getItemType() == null) {
            item.setItemType(ItemType.WORK);
        }
        evaluate(ownerId, item);
        itemRepository.save(item);
        return settle(loadEntry(ownerId, item.getEntryId()));
    }

    /** Apply one waiting item from «Вхідні» — for money this IS the confirmation. */
    public Outcome applyItem(UUID ownerId, UUID itemId) {
        InboxItem item = loadPendingItem(ownerId, itemId);
        List<String> failures = new ArrayList<>();
        if (item.getIssue() == null || item.getIssue() == Issue.CONFIRM) {
            apply(ownerId, item).ifPresent(failures::add);
        } else {
            failures.add(item.getText());
        }
        return new Outcome(settle(loadEntry(ownerId, item.getEntryId())), true, false, failures);
    }

    public InboxEntryView discardItem(UUID ownerId, UUID itemId) {
        InboxItem item = loadPendingItem(ownerId, itemId);
        item.setStatus(InboxItem.Status.DISCARDED);
        itemRepository.save(item);
        return settle(loadEntry(ownerId, item.getEntryId()));
    }

    /** Throw away what still waits in a message; what was already filed stays filed. */
    public void discardEntry(UUID ownerId, UUID entryId) {
        InboxEntry entry = loadEntry(ownerId, entryId);
        List<InboxItem> items = itemRepository.findByEntryIdOrderBySortOrderAsc(entryId);
        items.stream().filter(i -> i.getStatus() == InboxItem.Status.PENDING)
                .forEach(i -> i.setStatus(InboxItem.Status.DISCARDED));
        itemRepository.saveAll(items);
        entry.setStatus(InboxEntry.Status.DONE);
        entryRepository.save(entry);
    }

    // ---- filing ---------------------------------------------------------------------------------

    /** Apply every item nothing stops — never money, which always waits for «Записати». */
    private List<String> applyReady(UUID ownerId, List<InboxItem> items) {
        List<String> failures = new ArrayList<>();
        for (InboxItem item : items) {
            if (item.getStatus() == InboxItem.Status.PENDING && item.getIssue() == null) {
                apply(ownerId, item).ifPresent(failures::add);
            }
        }
        return failures;
    }

    /** File one item through its own service. Returns the item's text when that service refused it. */
    private Optional<String> apply(UUID ownerId, InboxItem item) {
        try {
            // One transaction around the record and the item's own APPLIED, under the item's row lock: a
            // second tap (the bot and the app, or two in the bot's pool) finds it APPLIED and writes
            // nothing (review M-1). A refused write rolls both back.
            InboxItem filed = new TransactionTemplate(transactionManager).execute(status -> {
                InboxItem locked = itemRepository.lockById(item.getId()).orElse(null);
                if (locked == null || locked.getStatus() != InboxItem.Status.PENDING) {
                    return locked;
                }
                locked.setRecordId(write(ownerId, locked));
                locked.setStatus(InboxItem.Status.APPLIED);
                locked.setIssue(null);
                return itemRepository.save(locked);
            });
            if (filed != null) {
                item.setRecordId(filed.getRecordId());
                item.setStatus(filed.getStatus());
                item.setIssue(filed.getIssue());
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            log.info("Inbox item {} ({}) refused: {}", item.getId(), item.getKind(), e.getMessage());
            return Optional.of(item.getText());
        }
    }

    private UUID write(UUID ownerId, InboxItem item) {
        UUID project = item.getProjectId();
        // A row in a list starts with a capital, as one typed by hand does: «Клей для плитки», not «клей…».
        String text = capitalized(item.getText());
        return switch (item.getKind()) {
            case SHOPPING -> {
                boolean guessed = item.getQuantity() == null || item.getUnit() == null;
                yield shoppingListService.addManual(project, ownerId, new ShoppingListItemRequest(null,
                        truncate(text, 255),
                        item.getUnit() != null ? item.getUnit() : Unit.PIECE,
                        item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE,
                        guessed ? "Кількість не названо" : null), null).id();
            }
            case TASK -> taskService.add(project, ownerId, new TaskRequest(truncate(text, 500)), null).id();
            case NOTE -> noteService.add(project, ownerId, new NoteRequest(null, null, truncate(text, 2000))).id();
            case ESTIMATE_LINE -> appendLine(ownerId, item);
            case EXPENSE -> project != null
                    ? expenseService.add(project, ownerId, new ExpenseRequest(item.getAmount(),
                            categoryOrOther(item).toExpenseCategory(), truncate(text, 500),
                            today())).id()
                    : cashFlowService.create(ownerId, new CashEntryRequest(CashDirection.EXPENSE, item.getAmount(),
                            item.getCategory(), truncate(text, 500), today(), false, null), null).id();
            case INCOME -> project != null
                    ? paymentService.addReceipt(project, ownerId, incomeRequest(item), null).get(0).id()
                    : cashFlowService.create(ownerId, new CashEntryRequest(CashDirection.INCOME, item.getAmount(),
                            item.getCategory() != null ? item.getCategory() : CashCategory.WORK,
                            truncate(text, 500), today(), false, null), null).id();
        };
    }

    private PaymentReceiptRequest incomeRequest(InboxItem item) {
        return new PaymentReceiptRequest(truncate(item.getText().trim(), 255), item.getAmount(), today(), false);
    }

    /**
     * The template's own empty row of that work takes the quantity (its price stays his, and the reply
     * shows it); otherwise one line is appended, through the same door dictation commits through.
     */
    private UUID appendLine(UUID ownerId, InboxItem item) {
        Optional<EstimateItemResponse> filled = estimateService.fillEmptyLine(item.getEstimateId(),
                item.getText(), item.getUnit(), item.getQuantity(), ownerId);
        if (filled.isPresent()) {
            item.setFilledLine(true);
            item.setUnitPrice(filled.get().unitPrice());
            return filled.get().id();
        }
        Set<UUID> before = estimateService.get(item.getEstimateId(), ownerId).items().stream()
                .map(EstimateItemResponse::id).collect(Collectors.toCollection(HashSet::new));
        EstimateResponse after = estimateService.appendItems(item.getEstimateId(), List.of(new ImportItem(
                item.getItemType() != null ? item.getItemType() : ItemType.WORK, truncate(item.getText(), 255),
                null, item.getUnit(), item.getQuantity(), item.getUnitPrice())), ownerId);
        return after.items().stream().map(EstimateItemResponse::id)
                .filter(id -> !before.contains(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Appended line not found"));
    }

    /**
     * Deletes what an item filed and moves it to {@code next}, under the item's row lock like {@link #apply}:
     * «Скасувати» and «Інший обʼєкт» tapped at once delete the record once. False when it was not APPLIED
     * any more; a refused delete rolls back and throws.
     */
    private boolean takeBack(UUID ownerId, InboxItem item, InboxItem.Status next) {
        InboxItem done = new TransactionTemplate(transactionManager).execute(status -> {
            InboxItem locked = itemRepository.lockById(item.getId()).orElse(null);
            if (locked == null || locked.getStatus() != InboxItem.Status.APPLIED) {
                return null;
            }
            unapply(ownerId, locked);
            locked.setStatus(next);
            locked.setRecordId(null);
            locked.setFilledLine(false);
            return itemRepository.save(locked);
        });
        if (done == null) {
            return false;
        }
        item.setStatus(done.getStatus());
        item.setRecordId(null);
        return true;
    }

    private void unapply(UUID ownerId, InboxItem item) {
        UUID project = item.getProjectId();
        UUID record = item.getRecordId();
        switch (item.getKind()) {
            case SHOPPING -> shoppingListService.delete(project, ownerId, record);
            case TASK -> taskService.delete(project, record, ownerId);
            case NOTE -> noteService.delete(project, record, ownerId);
            case ESTIMATE_LINE -> {
                if (item.isFilledLine()) {
                    estimateService.emptyFilledLine(item.getEstimateId(), record, ownerId);
                } else {
                    estimateService.deleteItem(item.getEstimateId(), record, ownerId);
                }
            }
            case EXPENSE -> {
                if (project != null) {
                    expenseService.delete(project, record, ownerId);
                } else {
                    cashFlowService.delete(ownerId, record, CashEntryKind.PERSONAL);
                }
            }
            case INCOME -> {
                if (project != null) {
                    paymentService.deleteReceipt(project, record, ownerId);
                } else {
                    cashFlowService.delete(ownerId, record, CashEntryKind.PERSONAL);
                }
            }
        }
    }

    // ---- rules ----------------------------------------------------------------------------------

    /** Why an item cannot be filed by itself — null when it can (rules 3-5). */
    static Issue issueOf(InboxItem item) {
        return switch (item.getKind()) {
            case SHOPPING, TASK, NOTE -> item.getProjectId() == null ? Issue.OBJECT : null;
            case ESTIMATE_LINE -> {
                if (item.getProjectId() == null) yield Issue.OBJECT;
                if (item.getEstimateId() == null) yield Issue.ESTIMATE;
                if (item.getQuantity() == null) yield Issue.QUANTITY;
                if (item.getUnit() == null) yield Issue.UNIT;
                if (item.getUnitPrice() == null) yield Issue.PRICE;
                yield null;
            }
            case EXPENSE, INCOME -> item.getAmount() == null ? Issue.AMOUNT : Issue.CONFIRM;
        };
    }

    /** Pick the estimate an estimate line goes into, then say why the item still waits, if it does. */
    private void evaluate(UUID ownerId, InboxItem item) {
        int open = resolveEstimate(item);
        priceFromEmptyRow(ownerId, item);
        Issue issue = issueOf(item);
        if (issue == null && forwarded(item.getEntryId())) {
            // Someone else's words: ready, but filed only on the master's «Записати».
            issue = Issue.CONFIRM;
        }
        // No DRAFT to land in: «Додаткові роботи» only when nothing is open at all; a SENT one is the master's pick.
        boolean noneOpen = issue == Issue.ESTIMATE && open == 0 && openEstimates(item.getProjectId()).isEmpty();
        item.setIssue(noneOpen ? Issue.NO_DRAFT : issue);
    }

    /**
     * No unit or no price from the catalog, but the chosen draft holds the template's own empty row of that
     * work with both: the row answers what the catalog could not (the row is where {@link #appendLine} puts it).
     */
    private void priceFromEmptyRow(UUID ownerId, InboxItem item) {
        if (item.getKind() != Kind.ESTIMATE_LINE || item.getEstimateId() == null
                || (item.getUnit() != null && item.getUnitPrice() != null)) {
            return;
        }
        estimateService.findEmptyLine(item.getEstimateId(), item.getText(), item.getUnit(), ownerId)
                .ifPresent(row -> {
                    if (item.getUnit() == null) {
                        item.setUnit(row.unit());
                    }
                    if (item.getUnitPrice() == null && row.unitPrice() != null && row.unitPrice().signum() > 0) {
                        item.setUnitPrice(row.unitPrice());
                    }
                });
    }

    private boolean forwarded(UUID entryId) {
        return entryRepository.findById(entryId).map(InboxEntry::isForwarded).orElse(false);
    }

    /**
     * The object's ONE open regular estimate, or nothing — two drafts is a question, not a guess.
     * Returns how many are open, or -1 when the question did not arise.
     */
    private int resolveEstimate(InboxItem item) {
        if (item.getKind() != Kind.ESTIMATE_LINE || item.getProjectId() == null || item.getEstimateId() != null) {
            return -1;
        }
        List<Estimate> open = draftEstimates(item.getProjectId());
        if (open.size() == 1) {
            item.setEstimateId(open.get(0).getId());
        }
        return open.size();
    }

    /**
     * Where a line may land BY ITSELF: a DRAFT only. A SENT estimate is already on the client's screen, so
     * a line goes there only when the master names it in «Вхідні» (review M-5).
     */
    private List<Estimate> draftEstimates(UUID projectId) {
        return estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(e -> e.getKind() == EstimateKind.REGULAR)
                .filter(e -> e.getStatus() == EstimateStatus.DRAFT)
                .toList();
    }

    private List<Estimate> openEstimates(UUID projectId) {
        return estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(e -> e.getKind() == EstimateKind.REGULAR)
                .filter(e -> e.getStatus() == EstimateStatus.DRAFT || e.getStatus() == EstimateStatus.SENT)
                .toList();
    }

    /** An estimate the master named in «Вхідні» — only one of the item's object's OPEN ones. */
    private UUID openEstimate(InboxItem item, UUID estimateId) {
        if (item.getProjectId() == null) {
            throw new ResourceNotFoundException("Estimate not found: " + estimateId);
        }
        return openEstimates(item.getProjectId()).stream().map(Estimate::getId)
                .filter(estimateId::equals).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Estimate not found: " + estimateId));
    }

    /** Spoken values win, the catalog fills the blanks — the dictation rule. */
    private static void price(InboxItem item, Catalog catalog) {
        Optional<CatalogItem> match = CatalogMatcher.match(item.getText(), catalog.items(), catalog.synonyms());
        match.ifPresent(c -> {
            item.setText(c.getName());
            if (item.getUnit() == null) {
                item.setUnit(c.getUnit());
            }
            if (item.getUnitPrice() == null) {
                item.setUnitPrice(c.getDefaultPrice() != null && c.getDefaultPrice().signum() > 0
                        ? c.getDefaultPrice() : null);
            }
            item.setItemType(c.getType());
        });
        if (item.getItemType() == null) {
            item.setItemType(ItemType.WORK);
        }
    }

    private record Catalog(List<CatalogItem> items, Map<String, UUID> synonyms) {}

    private Catalog loadCatalog(UUID ownerId) {
        Map<String, UUID> synonyms = new HashMap<>();
        for (CatalogItemSynonym s : synonymRepository.findByOwnerId(ownerId)) {
            synonyms.put(s.getSpokenNormalized(), s.getCatalogItem().getId());
        }
        // One row per name of what his catalog shows (V220). No estimate is chosen yet, so a name
        // two of his trades hold at two prices is refused as a tie rather than priced at random.
        Set<Trade> profile = userRepository.findWithTradesById(ownerId).map(u -> Set.copyOf(u.getTrades()))
                .orElse(Set.of());
        return new Catalog(CatalogFiling.forMatching(catalogItemRepository.findByOwnerIdOrderByNameAsc(ownerId),
                profile, null), synonyms);
    }

    /** A name inside a sentence: after a lowercase word, a digit or a comma, capitalised, three letters or more. */
    private static final Pattern NAME_IN_TEXT =
            Pattern.compile("(?<=[\\p{Ll}\\d,]\\s)(\\p{Lu}[\\p{Ll}ʼ']{2,})");
    private static final int MAX_PEOPLE = 20;

    /**
     * Names the master is likely to say in a voice note, for the transcription hint: his clients' names and the
     * capitalised words INSIDE his earlier messages («дав Василю», «Петро привезе»), most frequent first. There
     * is no crew list to read; a word that starts a sentence is not taken — every sentence starts capitalised.
     */
    List<String> peopleNames(UUID ownerId, List<String> objectNames) {
        // An object's own words («на Зубрівській») are in the hint already, as the object.
        Set<String> objectWords = new HashSet<>();
        for (String name : objectNames) {
            objectWords.addAll(List.of(name.split("[\\s,.«»\"]+")));
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (InboxEntry e : entryRepository.findTop100ByOwnerIdAndStatusInOrderByCreatedAtDesc(ownerId,
                EnumSet.allOf(InboxEntry.Status.class))) {
            if (e.getRawText() == null) {
                continue;
            }
            Matcher m = NAME_IN_TEXT.matcher(e.getRawText());
            while (m.find()) {
                seen.merge(m.group(1), 1, Integer::sum);
            }
        }
        for (Client c : clientRepository.findByOwnerIdOrderByFullNameAsc(ownerId)) {
            for (String part : c.getFullName().trim().split("\\s+")) {
                if (part.length() >= 3 && Character.isUpperCase(part.charAt(0))
                        && part.chars().allMatch(Character::isLetter)) {
                    seen.merge(part, 1, Integer::sum);
                }
            }
        }
        return seen.entrySet().stream()
                .filter(e -> !objectWords.contains(e.getKey()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).limit(MAX_PEOPLE).toList();
    }

    private static Kind kindOf(String raw) {
        try {
            return Kind.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private static CashCategory categoryOf(String raw, Kind kind) {
        if (!MONEY.contains(kind) || raw == null) {
            return null;
        }
        try {
            CashCategory c = CashCategory.valueOf(raw);
            boolean incomeCategory = c == CashCategory.ADVANCE || c == CashCategory.WORK;
            // A category on the wrong side of the money is dropped, not trusted.
            return (kind == Kind.INCOME) == incomeCategory || c == CashCategory.OTHER ? c : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static CashCategory categoryOrOther(InboxItem item) {
        return item.getCategory() != null ? item.getCategory() : CashCategory.OTHER;
    }

    // ---- reading --------------------------------------------------------------------------------

    private List<ObjectChoice> activeObjects(UUID ownerId) {
        return projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .filter(p -> p.getStatus() != ProjectStatus.COMPLETED && p.getStatus() != ProjectStatus.CANCELLED)
                // The training sample is read by no figure: money filed under it would vanish from every sum.
                .filter(p -> !p.isSample())
                .limit(MAX_OBJECTS)
                .map(p -> new ObjectChoice(p.getId(), p.getName()))
                .toList();
    }

    /** Name, address and client for the model — the client is lazy, hence the read transaction. */
    private List<InboxExtractor.ObjectRef> objectRefs(UUID ownerId, List<ObjectChoice> objects) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            Map<UUID, Project> byId = projectRepository.findAllById(objects.stream().map(ObjectChoice::id).toList())
                    .stream().collect(Collectors.toMap(Project::getId, Function.identity()));
            List<InboxExtractor.ObjectRef> refs = new ArrayList<>(objects.size());
            for (int i = 0; i < objects.size(); i++) {
                Project p = byId.get(objects.get(i).id());
                if (p == null) {
                    // Deleted a moment ago: keep its number so the rest still line up.
                    refs.add(new InboxExtractor.ObjectRef(i + 1, objects.get(i).name(), null, null));
                    continue;
                }
                String client = p.getClient() != null ? p.getClient().getFullName() : null;
                refs.add(new InboxExtractor.ObjectRef(i + 1, p.getName(), p.getAddress(), client));
            }
            return refs;
        });
    }

    private Map<UUID, String> projectNames(UUID ownerId) {
        return projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .collect(Collectors.toMap(Project::getId, Project::getName));
    }

    /** Recompute the entry's status from its items and return the fresh view. */
    private InboxEntryView settle(InboxEntry entry) {
        List<InboxItem> items = itemRepository.findByEntryIdOrderBySortOrderAsc(entry.getId());
        boolean waiting = items.stream().anyMatch(i -> i.getStatus() == InboxItem.Status.PENDING);
        boolean empty = items.isEmpty();
        // A message that held nothing to file stays visible once, so the master sees it was not lost.
        entry.setStatus(waiting || empty ? InboxEntry.Status.NEW : InboxEntry.Status.DONE);
        entryRepository.save(entry);
        return view(entry, items, projectNames(entry.getOwnerId()));
    }

    private InboxEntryView view(InboxEntry entry) {
        return view(entry, itemRepository.findByEntryIdOrderBySortOrderAsc(entry.getId()),
                projectNames(entry.getOwnerId()));
    }

    private static InboxEntryView view(InboxEntry e, List<InboxItem> items, Map<UUID, String> names) {
        return new InboxEntryView(e.getId(), e.getSource(), e.getStatus(), e.getRawText(), e.getCreatedAt(),
                items.stream().map(i -> InboxItemView.from(i,
                        i.getProjectId() != null ? names.get(i.getProjectId()) : null)).toList(),
                e.getVoiceSeconds() != null);
    }

    private InboxEntry loadEntry(UUID ownerId, UUID entryId) {
        return entryRepository.findByIdAndOwnerId(entryId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Inbox entry not found: " + entryId));
    }

    private InboxItem loadPendingItem(UUID ownerId, UUID itemId) {
        InboxItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Inbox item not found: " + itemId));
        loadEntry(ownerId, item.getEntryId()); // a foreign item is a 404, never a 403
        if (item.getStatus() != InboxItem.Status.PENDING) {
            throw new ResourceNotFoundException("Inbox item is not waiting: " + itemId);
        }
        return item;
    }

    public static String capitalized(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.substring(0, 1).toUpperCase(java.util.Locale.forLanguageTag("uk")) + text.substring(1);
    }

    private static LocalDate today() {
        return LocalDate.now(LocalizationConfig.ZONE);
    }

    private static String truncate(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
