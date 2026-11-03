package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.CashEntryKind;
import com.majstr.backend.dto.CashEntryRequest;
import com.majstr.backend.dto.CashFlowResponse;
import com.majstr.backend.dto.CashSummaryResponse;
import com.majstr.backend.dto.ObjectMoneyResponse;
import com.majstr.backend.dto.ReceiptRecognizeResponse;
import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.dto.ProjectReceiptRequest;
import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.CashDirection;
import com.majstr.backend.entity.CashEntry;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.PaymentReceipt;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.exception.PaymentValidationException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.repository.CashEntryRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.service.ImageContentTypeDetector.ImageKind;
import com.majstr.backend.service.fiscal.FiscalQrReceiptReader;
import com.majstr.backend.storage.StorageService;
import com.majstr.backend.storage.StoredObject;
import com.majstr.backend.storage.UnsupportedMediaTypeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * «Мої гроші» (V135) — the master's OWN money, across every object and beside them.
 *
 * <p><b>The screen is a LENS, not a second book.</b> The app already records most of a master's cash
 * with dates — client payments in {@code payment_receipt}, spending in {@code object_expenses} — but
 * every query over them reads {@code WHERE object_id = ?}, so he could see each job and never his
 * own month. This unions those two with {@code cash_entry}, which holds only what no object knows:
 * fuel, tools, taxes, and income for work that closed without an act («не все переводиться через
 * акти, багато хто так не працює»).</p>
 *
 * <p>The alternative — a standalone personal ledger — was rejected on purpose. The master already
 * logs object money; a book that ignored it would either be wrong or make him type everything twice,
 * and two books that disagree is how he stops trusting both.</p>
 *
 * <h4>Invariants</h4>
 * <ul>
 *   <li><b>One record, never two.</b> Adding here writes only {@code cash_entry}; money that belongs
 *       to an object is already in that object's journal and arrives on the read path. Editing or
 *       deleting an object's row from this screen goes through that object's OWN service — a second
 *       door to one record, never a second copy — so its rules still hold, including the refusal to
 *       touch an expense a V129 till receipt owns.</li>
 *   <li><b>«Заробив» = «Прийшло» − «Витрати», one subtraction, over the MASTER's money only</b>
 *       (object-money iteration). The client's material money — a till receipt «клієнт поверне»
 *       and a payment «на матеріал» — is a feed row marked {@code clientMaterial} and counted in
 *       neither total; what is still owed is {@link CashFlowResponse#materials()}. B-33 had put the
 *       receipt on the expense side and the reimbursement on the income side so the two netted;
 *       they did, but «Прийшло» and «Витрачено» carried money that was never his, and a purchase
 *       and its repayment in different months swung «Заробив» by the full amount. Leaving BOTH
 *       halves out nets them just as exactly. The two {@code findOutOfPocketByOwnerAndPeriod}
 *       queries stay disjoint from {@code object_expenses}, so nothing is counted twice.</li>
 *   <li><b>Material billed through an act is the master's expense</b> — the client repays it inside
 *       the act, as payment for work, so it nets against income like any other cost.</li>
 *   <li><b>Period boundaries are {@code Europe/Kyiv}</b>, never the server's UTC idea of today —
 *       on the 1st at 01:00 that would open «цей місяць» on the previous one.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashFlowService {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * How many rows one answer will carry. The TOTALS are always computed over everything — only the
     * list is cut — and the response says so, because a screen quietly showing part of a month is
     * worse than one that admits it. Unreachable in practice on a week or a month; a year asks for
     * the monthly grouping instead.
     */
    private static final int MAX_ENTRIES = 500;

    /** The widest period any screen asks for is a year, and the year view is served month by month.
     *  Thirteen leaves room for «1 Jan – 31 Dec» however the client rounds the bounds. */
    private static final int MAX_PERIOD_MONTHS = 13;

    /** The whole life of an object, for «Гроші обʼєкта» — bounds no row can fall outside. */
    private static final LocalDate EVER_FROM = LocalDate.of(2000, 1, 1);
    private static final LocalDate EVER_TO = LocalDate.of(2999, 12, 31);

    private static final String PHOTO_PREFIX = "cash-receipts";

    private final CashEntryRepository cashRepository;
    private final PaymentReceiptRepository receiptRepository;
    private final ObjectExpenseRepository expenseRepository;
    private final ProjectReceiptRepository projectReceiptRepository;
    private final WorkActReceiptRepository actReceiptRepository;
    private final ProjectRepository projectRepository;
    private final PaymentService paymentService;
    private final ObjectExpenseService expenseService;
    private final ProjectReceiptService projectReceiptService;
    private final ProjectService projectService;
    private final StorageService storage;
    private final StorageCleanup cleanup;
    private final FiscalQrReceiptReader qrReader;

    // ---- reads ------------------------------------------------------------

    /**
     * One period, three sources, one movement.
     *
     * @param monthly true for the YEAR view: it answers per-month totals and an EMPTY entry list.
     *                Two thousand rows is not a screen anyone reads on a phone, and the master
     *                drills into a month by re-querying it
     */
    @Transactional(readOnly = true)
    public CashFlowResponse flow(UUID ownerId, LocalDate from, LocalDate to, boolean monthly) {
        return flow(ownerId, from, to, monthly, CashScope.ALL);
    }

    /**
     * The same, narrowed to one object or to the rows that belong to none.
     *
     * <p>A filter on the READ, never a step in front of adding (the master's round-3 ruling stands:
     * nothing here asks which object money belongs to). An object that is not his is a 404 — the
     * same answer as an id that does not exist.</p>
     */
    @Transactional(readOnly = true)
    public CashFlowResponse flow(UUID ownerId, LocalDate from, LocalDate to, boolean monthly,
                                 CashScope scope) {
        // ONE clock read for both defaults (review B-52): two reads could straddle midnight and
        // answer «1 November – 31 October».
        LocalDate today = today();
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today.withDayOfMonth(today.lengthOfMonth());
        // Reversed bounds are a REFUSAL, not a silent swap: the caller believes something about this
        // period, and quietly answering about a different one is how a screen and a strip disagree.
        if (end.isBefore(start)) {
            throw new PaymentValidationException("error.cash.period-reversed");
        }
        // A cap on the range itself, not only on the row count: the year view already asks for
        // thirteen months at most, and anything beyond that loads every row the master has ever had
        // in order to cut it to 500.
        if (start.plusMonths(MAX_PERIOD_MONTHS).isBefore(end)) {
            throw new PaymentValidationException("error.cash.period-too-long");
        }
        requireScopeOwned(ownerId, scope);
        List<CashFlowResponse.Entry> all = collect(ownerId, start, end).stream()
                .filter(scope::includes)
                .toList();

        BigDecimal income = sum(all, CashFlowService::isOwnIncome);
        BigDecimal expense = sum(all, CashFlowService::isOwnExpense);
        BigDecimal refunds = sum(all, e -> e.clientMaterial() && e.direction() == CashDirection.INCOME);
        BigDecimal earned = income.subtract(expense);

        List<CashFlowResponse.Entry> entries = monthly
                ? List.of()
                : all.stream().limit(MAX_ENTRIES).toList();
        List<CashFlowResponse.MonthTotal> months = monthly ? monthTotals(all) : List.of();
        List<CashFlowResponse.ScopeTotal> objects = new ArrayList<>();
        CashFlowResponse.ScopeTotal personal = null;
        for (List<CashFlowResponse.Entry> slice : slicesByObject(all)) {
            CashFlowResponse.Entry first = slice.get(0);
            BigDecimal in = sum(slice, CashFlowService::isOwnIncome);
            BigDecimal out = sum(slice, CashFlowService::isOwnExpense);
            var total = new CashFlowResponse.ScopeTotal(first.projectId(), first.projectName(), in, out,
                    scale(in.subtract(out)));
            if (first.projectId() == null) {
                personal = total;
            } else {
                objects.add(total);
            }
        }
        return new CashFlowResponse(start, end, income, expense, earned, refunds, entries, months,
                !monthly && all.size() > MAX_ENTRIES, materialBalances(ownerId, scope), objects, personal);
    }

    /** The period's rows split by object (null = none), most recently active first. */
    private static Collection<List<CashFlowResponse.Entry>> slicesByObject(List<CashFlowResponse.Entry> all) {
        Map<Optional<UUID>, List<CashFlowResponse.Entry>> slices = new LinkedHashMap<>();
        for (CashFlowResponse.Entry e : all) {
            slices.computeIfAbsent(Optional.ofNullable(e.projectId()), k -> new ArrayList<>()).add(e);
        }
        return slices.values();
    }

    /**
     * «Гроші обʼєкта»: the cash feed of ONE object over its whole life.
     *
     * <p>Read through the very same {@link #collect} as «Мої гроші» and filtered afterwards, so there
     * is no second definition of what the object earned to drift from the first. The owner-wide read
     * is bounded by the master's own rows, which is small; if it ever is not, the filter moves into
     * the queries, not into a new formula.</p>
     */
    @Transactional(readOnly = true)
    public ObjectMoneyResponse objectMoney(UUID ownerId, UUID projectId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        CashScope scope = CashScope.object(projectId);
        List<CashFlowResponse.Entry> all = collect(ownerId, EVER_FROM, EVER_TO).stream()
                .filter(scope::includes)
                .toList();
        BigDecimal income = sum(all, CashFlowService::isOwnIncome);
        BigDecimal expense = sum(all, CashFlowService::isOwnExpense);
        boolean crew = all.stream().anyMatch(e -> e.category() == CashCategory.CREW);
        MaterialRefundSplit split = MaterialRefundSplit.of(
                receiptRepository.sumMaterialRefunds(projectId),
                projectReceiptRepository.sumReimbursable(projectId),
                actReceiptRepository.sumSignedActReceipts(projectId));
        CashFlowResponse.MaterialBalance materials = new CashFlowResponse.MaterialBalance(
                projectId, project.getName(), scale(split.reimbursable()), scale(split.refunds()),
                scale(split.toActs()), scale(split.materialsOutstanding()));
        return new ObjectMoneyResponse(income, expense, scale(income.subtract(expense)), materials,
                crew, all.stream().limit(MAX_ENTRIES).toList(), all.size() > MAX_ENTRIES);
    }

    /** The master's own income — what «Прийшло» sums. */
    private static boolean isOwnIncome(CashFlowResponse.Entry e) {
        return e.direction() == CashDirection.INCOME && !e.clientMaterial();
    }

    /** The master's own spending — what «Витрачено» sums. */
    private static boolean isOwnExpense(CashFlowResponse.Entry e) {
        return e.direction() == CashDirection.EXPENSE && !e.clientMaterial();
    }

    /**
     * What each client owes for material, or still has unspent with the master — all time, one
     * line per object whose balance is not zero, largest debt first.
     *
     * <p>Built from the SAME two sums the object's own card reads ({@link MaterialRefundSplit}), so
     * «Мої гроші» and the object never name two different debts.</p>
     */
    private List<CashFlowResponse.MaterialBalance> materialBalances(UUID ownerId, CashScope scope) {
        if (scope.offObject()) {
            return List.of();
        }
        OwnerMaterial material = ownerMaterial(ownerId);
        Map<UUID, BigDecimal> bought = material.bought();
        Map<UUID, BigDecimal> given = material.given();
        Set<UUID> ids = new HashSet<>(bought.keySet());
        ids.addAll(given.keySet());
        if (scope.projectId() != null) {
            ids.retainAll(Set.of(scope.projectId()));
        }
        Map<UUID, String> names = projectNames(ids);
        List<CashFlowResponse.MaterialBalance> out = new ArrayList<>();
        for (UUID id : ids) {
            MaterialRefundSplit split = material.split(id);
            if (split.materialsOutstanding().signum() == 0) {
                continue;
            }
            out.add(new CashFlowResponse.MaterialBalance(id, names.get(id), scale(split.reimbursable()),
                    scale(split.refunds()), scale(split.toActs()), scale(split.materialsOutstanding())));
        }
        out.sort(Comparator.comparing(CashFlowResponse.MaterialBalance::outstanding).reversed()
                .thenComparing(b -> b.projectId().toString()));
        return out;
    }

    /**
     * Every object's material money for one master, all time, in three owner-wide queries: what
     * clients gave «на матеріал», the till receipts they owe for, and the receipts each SIGNED act
     * bills (oldest signature first). One snapshot both the balances and the derived feed rows read,
     * so they can never disagree about a single object.
     */
    private record OwnerMaterial(Map<UUID, BigDecimal> given, Map<UUID, BigDecimal> bought,
                                 List<Object[]> actRows) {

        MaterialRefundSplit split(UUID projectId) {
            BigDecimal acts = BigDecimal.ZERO;
            for (Object[] row : actRows) {
                if (projectId.equals(row[0])) {
                    acts = acts.add((BigDecimal) row[4]);
                }
            }
            return MaterialRefundSplit.of(given.get(projectId), bought.get(projectId), acts);
        }
    }

    private OwnerMaterial ownerMaterial(UUID ownerId) {
        Map<UUID, BigDecimal> bought = new HashMap<>();
        for (Object[] row : projectReceiptRepository.sumReimbursableByOwner(ownerId)) {
            bought.put((UUID) row[0], (BigDecimal) row[1]);
        }
        Map<UUID, BigDecimal> given = new HashMap<>();
        for (Object[] row : receiptRepository.sumMaterialRefundsByOwner(ownerId)) {
            given.put((UUID) row[0], (BigDecimal) row[1]);
        }
        List<Object[]> acts = given.isEmpty()
                ? List.of()
                : actReceiptRepository.sumSignedActReceiptsByActForOwner(ownerId);
        return new OwnerMaterial(given, bought, acts);
    }

    /**
     * Which feed rows the client's material money paid, and how much of each: the share every
     * SIGNED act takes ({@link MaterialRefundSplit#toActs}, oldest signature first) spread over that
     * act's rows oldest first — the expenses it posted (V184), or its receipts when it posts none.
     * Owner's ruling (2026-11-03): this is NOT income — the money arrived as the client's «на
     * матеріал» payment — it is the part of the purchase that did not leave his own pocket.
     * Computed over every row of the act, not the window, so a row's share never depends on which
     * week is on screen.
     */
    private Map<UUID, BigDecimal> clientShares(UUID ownerId) {
        OwnerMaterial material = ownerMaterial(ownerId);
        Map<UUID, BigDecimal> actShare = new java.util.LinkedHashMap<>();
        Map<UUID, BigDecimal> left = new HashMap<>();
        for (Object[] row : material.actRows()) {
            UUID projectId = (UUID) row[0];
            BigDecimal remaining = left.computeIfAbsent(projectId, id -> material.split(id).toActs());
            BigDecimal take = remaining.min((BigDecimal) row[4]);
            if (take.signum() > 0) {
                left.put(projectId, remaining.subtract(take));
                actShare.put((UUID) row[1], take);
            }
        }
        if (actShare.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Map.Entry<UUID, BigDecimal>>> rowsByAct = new HashMap<>();
        for (ObjectExpense e : expenseRepository.findByWorkActIds(actShare.keySet())) {
            rowsByAct.computeIfAbsent(e.getWorkActId(), k -> new ArrayList<>()).add(Map.entry(e.getId(), e.getAmount()));
        }
        Set<UUID> unposted = new HashSet<>(actShare.keySet());
        unposted.removeAll(rowsByAct.keySet());
        if (!unposted.isEmpty()) {
            for (WorkActReceipt r : actReceiptRepository.findBilledByWorkActIds(unposted)) {
                rowsByAct.computeIfAbsent(r.getWorkAct().getId(), k -> new ArrayList<>())
                        .add(Map.entry(r.getId(), r.billedAmount()));
            }
        }
        Map<UUID, BigDecimal> out = new HashMap<>();
        actShare.forEach((actId, share) -> {
            BigDecimal remaining = share;
            for (Map.Entry<UUID, BigDecimal> rowAmount : rowsByAct.getOrDefault(actId, List.of())) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal take = remaining.min(rowAmount.getValue().max(BigDecimal.ZERO));
                if (take.signum() > 0) {
                    out.put(rowAmount.getKey(), scale(take));
                    remaining = remaining.subtract(take);
                }
            }
        });
        return out;
    }

    private void requireScopeOwned(UUID ownerId, CashScope scope) {
        if (scope.projectId() != null) {
            projectRepository.findById(scope.projectId())
                    .filter(p -> p.getOwner().getId().equals(ownerId))
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Object not found: " + scope.projectId()));
        }
    }

    /**
     * The home strip: this MONTH in Kyiv, three numbers, and whether to render at all (master's
     * call — a week on the home screen was too small a window to be worth a glance).
     *
     * <p>Tapping the strip must land on THIS window, not on some other one: the client opens the
     * screen already switched to the month. Every other door — the Профіль row, a reload — keeps the
     * week, which is what the screen is for. {@code from}/{@code to} ride along so the client labels
     * the window it was actually given rather than re-deriving one.</p>
     */
    @Transactional(readOnly = true)
    public CashSummaryResponse summary(UUID ownerId) {
        // One clock read (review B-106), as `flow` does: two could straddle midnight on the 1st.
        LocalDate today = today();
        LocalDate from = today.withDayOfMonth(1);
        LocalDate to = today.withDayOfMonth(today.lengthOfMonth());
        CashFlowResponse month = flow(ownerId, from, to, true);
        boolean any = month.income().signum() != 0 || month.expense().signum() != 0;
        return new CashSummaryResponse(from, to, month.income(), month.expense(), month.earned(), any);
    }

    // ---- writes -----------------------------------------------------------

    /**
     * Write one line — always HIS OWN.
     *
     * <p>Adding here never asks about an object (master's ruling): object money is already in the
     * object's journal and arrives on the read path by itself. An earlier round routed an entry into
     * a chosen object; the picker read as a required step in front of a screen that already pulls
     * everything, so both the picker and the route are gone.</p>
     */
    @Transactional
    public CashFlowResponse.Entry create(UUID ownerId, CashEntryRequest req, UUID requestedId) {
        LocalDate day = req.happenedOn() != null ? req.happenedOn() : today();
        BigDecimal amount = req.amount().setScale(MONEY_SCALE, ROUNDING);
        if (requestedId != null) {
            var existing = cashRepository.findById(requestedId);
            if (existing.isPresent()) {
                // A replayed create from the outbox. Owner-checked, never re-homed: money must not
                // double-count, and an id belonging to someone else is simply not found.
                if (!existing.get().getOwnerId().equals(ownerId)) {
                    throw new ResourceNotFoundException("Cash entry not found: " + requestedId);
                }
                return toEntry(existing.get());
            }
        }
        CashEntry entry = CashEntry.builder()
                .id(requestedId)
                .ownerId(ownerId)
                .direction(req.direction())
                .amount(amount)
                .category(req.category())
                .note(trimToNull(req.note()))
                .happenedOn(day)
                .happenedAt(Instant.now())
                .materialRefund(req.direction() == CashDirection.INCOME && Boolean.TRUE.equals(req.materialRefund()))
                .build();
        return toEntry(cashRepository.save(entry));
    }

    /**
     * Edit ANY row of the feed, in place — including an object's own payment or expense.
     *
     * <p>An earlier round bounced a tap on an object row to that object's screen. The master asked
     * for the opposite («з можливістю видаляти рядки чи едітати»), and it costs no invariant: this
     * is a second DOOR to one record, not a second copy of it. Every write goes through the object's
     * own service, so its rules still hold — the owner check, and the refusal to touch an expense a
     * V129 till receipt owns (that pair is one fact, and the receipt mirrors onto the row).</p>
     *
     * <p>What a table has no place for is IGNORED rather than refused: a payment has no category,
     * and its direction is decided by it being a payment at all. A PLANNED receipt's label belongs
     * to its stage, so {@code editReceipt} leaves it alone — which is why the feed marks such a row
     * {@code noteLocked} and the screen does not offer to type over it.</p>
     */
    @Transactional
    public CashFlowResponse.Entry update(UUID ownerId, UUID id, CashEntryRequest req) {
        // An omitted date LEAVES THE STORED ONE on every kind (review B-104) — it used to become
        // today, moving a payment or a till receipt into a month the master was not looking at.
        BigDecimal amount = req.amount().setScale(MONEY_SCALE, ROUNDING);
        return switch (kindOf(req)) {
            case OBJECT_PAYMENT -> {
                PaymentReceipt receipt = ownedReceipt(ownerId, id);
                UUID projectId = receipt.getProject().getId();
                LocalDate day = req.happenedOn() != null ? req.happenedOn() : receipt.getReceivedAt();
                paymentService.editReceipt(projectId, id, ownerId, new PaymentReceiptEditRequest(
                        amount, day, trimToNull(req.note()), req.materialRefund()));
                yield fromReceipt(ownedReceipt(ownerId, id));
            }
            case OBJECT_EXPENSE -> {
                ObjectExpense expense = ownedExpense(ownerId, id);
                // An omitted field LEAVES THE STORED ONE (review B-44). This request is built by
                // hand, so a missing category used to become OTHER — turning a MATERIALS or LABOR
                // cost into «Інше» — and a missing date became today, moving the row into a month
                // the master was not even looking at. Latent today (the PWA always sends both) and
                // exactly the trap a replayed offline op walks into.
                ExpenseCategory category = req.category() != null
                        ? req.category().toExpenseCategory()
                        : expense.getCategory();
                expenseService.update(expense.getObjectId(), id, ownerId, new ExpenseRequest(
                        amount, category, trimToNull(req.note()),
                        req.happenedOn() != null ? req.happenedOn() : expense.getSpentAt()));
                // Answered as the feed shows it: a share the client's material money paid stays off it.
                yield paidByClient(fromExpense(ownedExpense(ownerId, id), projectName(expense.getObjectId())),
                        clientShares(ownerId));
            }
            case OBJECT_RECEIPT -> {
                ProjectReceipt receipt = ownedTillReceipt(ownerId, id);
                // `reimbursable` rides as null on purpose — the three-valued flag means «leave it
                // alone», and «хто за це платить» is a decision that belongs to the object's own
                // receipts screen, in front of the photo. A month's feed must not flip it in
                // passing, least of all by omission.
                String note = trimToNull(req.note());
                LocalDate day = req.happenedOn() != null ? req.happenedOn() : receipt.getIssuedAt();
                projectReceiptService.update(receipt.getProjectId(), id, ownerId,
                        new ProjectReceiptRequest(note != null ? note : receipt.getLabel(),
                                amount, day, null, null, null));
                yield fromTillReceipt(ownedTillReceipt(ownerId, id),
                        projectName(receipt.getProjectId()));
            }
            // Frozen inside a signed act's `doc_hash`: correcting the figure here would either
            // contradict the paper the client holds or invalidate its hash. 409 rather than a
            // silent no-op, so an old client that offers the edit says why it failed.
            case ACT_RECEIPT -> throw new WorkActSignedException();
            case PERSONAL -> {
                CashEntry entry = load(ownerId, id);
                entry.setDirection(req.direction());
                entry.setAmount(amount);
                if (req.category() != null) {
                    entry.setCategory(req.category()); // omitted = keep (review B-104), never null
                }
                entry.setNote(trimToNull(req.note()));
                if (req.happenedOn() != null) {
                    // The TIME stays where it was: it says «коли я це вписав», and re-stamping it on
                    // every correction would silently reshuffle a day's order under the master.
                    entry.setHappenedOn(req.happenedOn());
                }
                entry.setMaterialRefund(req.direction() == CashDirection.INCOME && Boolean.TRUE.equals(req.materialRefund()));
                yield toEntry(entry);
            }
        };
    }

    /** Idempotent for every kind: a row already gone is a no-op, so a replayed queue op is harmless. */
    @Transactional
    public void delete(UUID ownerId, UUID id, CashEntryKind kind) {
        // A row that is not his is a **404**, not a 403 (review B-45): letting the object's own
        // service refuse it answered «forbidden», which confirms the id belongs to someone. An
        // UNKNOWN id stays a no-op, because that is what makes a replayed offline delete harmless —
        // the two answers still differ, and that is the deliberate trade: idempotency for a queue
        // the master cannot see outranks hiding the existence of a row he could only guess at.
        switch (kind == null ? CashEntryKind.PERSONAL : kind) {
            case OBJECT_PAYMENT -> receiptRepository.findById(id).ifPresent(r -> {
                requireOwner(ownerId, r.getProject().getOwner().getId(), id);
                paymentService.deleteReceipt(r.getProject().getId(), id, ownerId);
            });
            case OBJECT_EXPENSE -> expenseRepository.findById(id).ifPresent(e -> {
                requireOwnedObject(ownerId, e.getObjectId(), id);
                expenseService.delete(e.getObjectId(), id, ownerId);
            });
            case OBJECT_RECEIPT -> projectReceiptRepository.findById(id).ifPresent(r -> {
                requireOwnedObject(ownerId, r.getProjectId(), id);
                projectReceiptService.delete(r.getProjectId(), id, ownerId);
            });
            case ACT_RECEIPT -> throw new WorkActSignedException();
            case PERSONAL -> cashRepository.findByIdAndOwnerId(id, ownerId).ifPresent(entry -> {
                cashRepository.delete(entry);
                // The paper goes AFTER the row, never before it (B-25).
                cleanup.afterCommit(entry.getStorageKey());
            });
        }
    }

    // ---- a photo of the paper on his OWN row --------------------------------

    /**
     * Attach (or replace) the photo of a receipt on one of his own rows — робочий одяг, a drill.
     *
     * <p>Object money already has its own papers (object and act receipts); this is only for what no
     * object knows about, so only a PERSONAL row takes one. Not {@code @Transactional}: the blob is
     * stored first and the row saved after it in its own short transaction, so a failed save can
     * drop the blob it would otherwise orphan (B-48), and the replaced photo is deleted only once
     * the new key is committed.</p>
     */
    public CashFlowResponse.Entry attachPhoto(UUID ownerId, UUID id, MultipartFile file) throws IOException {
        CashEntry entry = load(ownerId, id);
        if (file == null || file.isEmpty()) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }
        byte[] content = file.getBytes();
        ImageKind kind = ProjectReceiptService.requireImage(content);
        StoredObject stored = storage.store(new ByteArrayInputStream(content), content.length,
                PHOTO_PREFIX, kind.extension, kind.contentType);
        String previous = entry.getStorageKey();
        entry.setStorageKey(stored.key());
        try {
            entry = cashRepository.save(entry);
        } catch (RuntimeException e) {
            tryDelete(stored.key());
            throw e;
        }
        if (previous != null) {
            tryDelete(previous);
        }
        return toEntry(entry);
    }

    /** Drop the photo from his own row; the row itself stays. */
    @Transactional
    public CashFlowResponse.Entry removePhoto(UUID ownerId, UUID id) {
        CashEntry entry = load(ownerId, id);
        String key = entry.getStorageKey();
        entry.setStorageKey(null);
        cleanup.afterCommit(key);
        return toEntry(entry);
    }

    /** Owner download of his own row's photo — authenticated, never through {@code /api/files}. */
    @Transactional(readOnly = true)
    public ProjectPhotoService.PhotoFile readPhoto(UUID ownerId, UUID id) throws IOException {
        CashEntry entry = load(ownerId, id);
        if (entry.getStorageKey() == null) {
            throw new ResourceNotFoundException("Cash entry has no photo");
        }
        return ProjectPhotoService.open(storage, entry.getStorageKey(), "Cash entry photo not found");
    }

    /**
     * Read a receipt's printed fiscal QR to pre-fill his own row — the same free, local read the
     * object and act receipts use, through the same reader. Persists nothing.
     */
    public ReceiptRecognizeResponse readQr(String payload) {
        return qrReader.read(payload);
    }

    private void tryDelete(String key) {
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete stored cash receipt photo {}: {}", key, e.getMessage());
        }
    }

    // ---- the feed ---------------------------------------------------------

    private List<CashFlowResponse.Entry> collect(UUID ownerId, LocalDate from, LocalDate to) {
        // A day is a day in Kyiv, so the instant bounds for the rows dated by `created_at` are
        // resolved there too — a receipt photographed at 00:30 on the 1st belongs to the 1st.
        Instant fromTs = from.atStartOfDay(LocalizationConfig.ZONE).toInstant();
        Instant toTs = to.plusDays(1).atStartOfDay(LocalizationConfig.ZONE).toInstant();

        List<PaymentReceipt> receipts = receiptRepository.findByOwnerAndPeriod(ownerId, from, to);
        List<ObjectExpense> expenses = expenseRepository.findByOwnerAndPeriod(ownerId, from, to);
        List<CashEntry> own = cashRepository.findByOwnerAndPeriod(ownerId, from, to);
        List<ProjectReceipt> tillReceipts = projectReceiptRepository
                .findOutOfPocketByOwnerAndPeriod(ownerId, from, to, fromTs, toTs);
        List<WorkActReceipt> actReceipts = actReceiptRepository
                .findOutOfPocketByOwnerAndPeriod(ownerId, fromTs, toTs);

        // `ObjectExpense` and `ProjectReceipt` carry a bare objectId and no association, so the
        // names come in one lookup over exactly the objects this period touched — not over every
        // object he owns. Act receipts fetch their project along the way and need none.
        Set<UUID> objectIds = new HashSet<>();
        expenses.forEach(e -> objectIds.add(e.getObjectId()));
        tillReceipts.forEach(r -> objectIds.add(r.getProjectId()));
        Map<UUID, String> names = projectNames(objectIds);

        List<CashFlowResponse.Entry> all = new ArrayList<>(receipts.size() + expenses.size()
                + own.size() + tillReceipts.size() + actReceipts.size());
        for (PaymentReceipt r : receipts) {
            all.add(fromReceipt(r));
        }
        Map<UUID, BigDecimal> shares = clientShares(ownerId);
        for (ObjectExpense e : expenses) {
            all.add(paidByClient(fromExpense(e, names.get(e.getObjectId())), shares));
        }
        for (CashEntry c : own) {
            all.add(toEntry(c));
        }
        for (ProjectReceipt r : tillReceipts) {
            all.add(fromTillReceipt(r, names.get(r.getProjectId())));
        }
        for (WorkActReceipt r : actReceipts) {
            all.add(paidByClient(fromActReceipt(r), shares));
        }
        // The day first, then the time INSIDE it — and object rows carry no time at all, so they
        // fall to the end of their own day rather than to midnight of it. `createdAt` is not
        // available on every source, so the id is the final, stable tie-break.
        all.sort(Comparator
                .comparing(CashFlowResponse.Entry::happenedOn, Comparator.reverseOrder())
                .thenComparing(CashFlowResponse.Entry::happenedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(e -> e.id().toString()));
        return all;
    }

    private static CashFlowResponse.Entry paidByClient(CashFlowResponse.Entry e, Map<UUID, BigDecimal> shares) {
        BigDecimal share = shares.get(e.id());
        return share == null ? e : e.paidByClient(share);
    }

    private Map<UUID, String> projectNames(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (Project p : projectRepository.findAllById(ids)) {
            names.put(p.getId(), p.getName());
        }
        return names;
    }

    /**
     * One object payment as a feed row.
     *
     * <p>A PLANNED receipt has no label of its own — its purpose IS the stage's — so that is what
     * the feed shows and why the row comes back {@code noteLocked}: {@code editReceipt} deliberately
     * leaves a planned receipt's label alone, and a screen that let him type over it would be a
     * silent no-op.</p>
     */
    private static CashFlowResponse.Entry fromReceipt(PaymentReceipt r) {
        Project project = r.getProject();
        boolean planned = r.getPlanPayment() != null;
        return new CashFlowResponse.Entry(
                r.getId(), CashEntryKind.OBJECT_PAYMENT, CashDirection.INCOME, r.getAmount(),
                null, // an object payment has no category of its own — never invent one
                planned ? r.getPlanPayment().getPurpose() : r.getLabel(),
                r.getReceivedAt(), null,
                project.getId(), project.getName(), r.isMaterialRefund(), r.isMaterialRefund(),
                planned, false, false, null);
    }

    private static CashFlowResponse.Entry fromExpense(ObjectExpense e, String projectName) {
        return new CashFlowResponse.Entry(
                e.getId(), CashEntryKind.OBJECT_EXPENSE, CashDirection.EXPENSE, e.getAmount(),
                fromExpenseCategory(e), e.getNote(), e.getSpentAt(), null,
                e.getObjectId(), projectName, false, false, false, false, false, null);
    }

    /**
     * Material bought at the till «клієнт поверне» (review B-33, object-money iteration).
     *
     * <p>Not yet billed on an act, it is the CLIENT's material: shown, marked {@code clientMaterial},
     * counted in no total — what is still owed is the material balance. Once an act has billed it,
     * the client repays it inside that act as payment for work, so it becomes the master's expense
     * like any other cost, and read-only: the paper is inside a document the client holds.</p>
     */
    private static CashFlowResponse.Entry fromTillReceipt(ProjectReceipt r, String projectName) {
        boolean billed = r.getBilledOnActId() != null;
        return new CashFlowResponse.Entry(
                r.getId(), CashEntryKind.OBJECT_RECEIPT, CashDirection.EXPENSE, r.getAmount(),
                CashCategory.MATERIALS, r.getLabel(),
                r.getIssuedAt() != null ? r.getIssuedAt() : dayOf(r.getCreatedAt()), null,
                r.getProjectId(), projectName, false, !billed, billed, billed,
                r.getStorageKey() != null, null);
    }

    /**
     * A receipt re-billed on a SIGNED act that does not post its receipts to expenses — the master
     * paid for that material and this row is the only record of it (review B-33).
     *
     * <p>Read-only on purpose: the figure is frozen inside the act's {@code doc_hash}, so an edit
     * here could only either lie about the client's document or invalidate it. The amount is
     * {@link WorkActReceipt#billedAmount()} — material handed back to the shop cost nothing.</p>
     */
    private static CashFlowResponse.Entry fromActReceipt(WorkActReceipt r) {
        Project project = r.getWorkAct().getProject();
        return new CashFlowResponse.Entry(
                r.getId(), CashEntryKind.ACT_RECEIPT, CashDirection.EXPENSE, r.billedAmount(),
                CashCategory.MATERIALS,
                "Чек до акта № " + r.getWorkAct().getNumber() + ": " + r.getLabel(),
                // The day it was ADDED, as the expense the act posts with the flag ON — see
                // ActAddendumCreator.addedOn. The paper's date stays on the document.
                ActAddendumCreator.addedOn(r), null,
                project.getId(), project.getName(), false, false, true, true,
                r.getStorageKey() != null, null);
    }

    private static LocalDate dayOf(Instant moment) {
        return moment == null ? today() : LocalDate.ofInstant(moment, LocalizationConfig.ZONE);
    }

    /** The object's three buckets, read back into the wider personal set. */
    private static CashCategory fromExpenseCategory(ObjectExpense e) {
        return switch (e.getCategory()) {
            case MATERIALS -> CashCategory.MATERIALS;
            case LABOR -> CashCategory.CREW;
            case OTHER -> CashCategory.OTHER;
        };
    }

    private List<CashFlowResponse.MonthTotal> monthTotals(List<CashFlowResponse.Entry> all) {
        Map<LocalDate, BigDecimal[]> byMonth = new HashMap<>();
        for (CashFlowResponse.Entry e : all) {
            if (e.clientMaterial()) {
                continue; // the client's money: in no month's total, as in no period's
            }
            LocalDate key = e.happenedOn().withDayOfMonth(1);
            BigDecimal[] acc = byMonth.computeIfAbsent(key,
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (e.direction() == CashDirection.INCOME) {
                acc[0] = acc[0].add(e.amount());
            } else {
                acc[1] = acc[1].add(e.amount());
            }
        }
        return byMonth.entrySet().stream()
                .sorted(Map.Entry.<LocalDate, BigDecimal[]>comparingByKey().reversed())
                .map(en -> new CashFlowResponse.MonthTotal(
                        en.getKey(),
                        scale(en.getValue()[0]),
                        scale(en.getValue()[1]),
                        // The same one subtraction as the period total (B-33) — a month whose
                        // «Заробив» disagreed with the week's arithmetic is a screen nobody trusts.
                        scale(en.getValue()[0].subtract(en.getValue()[1]))))
                .toList();
    }

    // ---- reaching an object's own row -------------------------------------

    /**
     * The row, with the owner proved.
     *
     * <p>Ownership is re-checked by the object's own service on the write — this lookup only gets us
     * the project id to hand it. It still refuses an id that is not his, so a probe for someone
     * else's row answers «not found» rather than «forbidden»: a money id is not worth confirming.</p>
     */
    /** Not found, never forbidden — see {@link #delete} (review B-45). */
    private static void requireOwner(UUID ownerId, UUID rowOwnerId, UUID id) {
        if (!ownerId.equals(rowOwnerId)) {
            throw new ResourceNotFoundException("Cash entry not found: " + id);
        }
    }

    private void requireOwnedObject(UUID ownerId, UUID objectId, UUID id) {
        projectRepository.findById(objectId)
                .filter(p -> p.getOwner().getId().equals(ownerId))
                .orElseThrow(() -> new ResourceNotFoundException("Cash entry not found: " + id));
    }

    private PaymentReceipt ownedReceipt(UUID ownerId, UUID id) {
        return receiptRepository.findById(id)
                .filter(r -> r.getProject().getOwner().getId().equals(ownerId))
                .orElseThrow(() -> new ResourceNotFoundException("Cash entry not found: " + id));
    }

    private ObjectExpense ownedExpense(UUID ownerId, UUID id) {
        ObjectExpense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cash entry not found: " + id));
        projectRepository.findById(expense.getObjectId())
                .filter(p -> p.getOwner().getId().equals(ownerId))
                .orElseThrow(() -> new ResourceNotFoundException("Cash entry not found: " + id));
        return expense;
    }

    private ProjectReceipt ownedTillReceipt(UUID ownerId, UUID id) {
        ProjectReceipt receipt = projectReceiptRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cash entry not found: " + id));
        projectRepository.findById(receipt.getProjectId())
                .filter(p -> p.getOwner().getId().equals(ownerId))
                .orElseThrow(() -> new ResourceNotFoundException("Cash entry not found: " + id));
        return receipt;
    }

    /** An edit body with no kind is a personal row — the only thing a create can ever be. */
    private static CashEntryKind kindOf(CashEntryRequest req) {
        return req.kind() == null ? CashEntryKind.PERSONAL : req.kind();
    }

    private String projectName(UUID projectId) {
        return projectRepository.findById(projectId).map(Project::getName).orElse(null);
    }

    // ---- helpers ----------------------------------------------------------

    private CashEntry load(UUID ownerId, UUID id) {
        return cashRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Cash entry not found: " + id));
    }

    private static CashFlowResponse.Entry toEntry(CashEntry c) {
        return new CashFlowResponse.Entry(
                c.getId(), CashEntryKind.PERSONAL, c.getDirection(), c.getAmount(), c.getCategory(),
                c.getNote(), c.getHappenedOn(), c.getHappenedAt(), null, null, c.isMaterialRefund(),
                false, false, false, c.getStorageKey() != null, null);
    }

    private static BigDecimal sum(List<CashFlowResponse.Entry> all,
                                  java.util.function.Predicate<CashFlowResponse.Entry> match) {
        BigDecimal total = BigDecimal.ZERO;
        for (CashFlowResponse.Entry e : all) {
            if (match.test(e)) {
                total = total.add(e.amount());
            }
        }
        return scale(total);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, ROUNDING);
    }

    /** Today, and every period bound below it, in the master's own timezone — never the server's. */
    private static LocalDate today() {
        return LocalDate.now(LocalizationConfig.ZONE);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
