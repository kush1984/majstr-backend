package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.CashEntryKind;
import com.majstr.backend.dto.CashEntryRequest;
import com.majstr.backend.dto.CashFlowResponse;
import com.majstr.backend.dto.CashSummaryResponse;
import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.dto.ProjectReceiptRequest;
import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.CashDirection;
import com.majstr.backend.entity.CashEntry;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.PaymentReceipt;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.repository.CashEntryRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 *   <li><b>«Заробив» = «Прийшло» − «Витрати», one subtraction.</b> Which means every hryvnia
 *       that left his pocket has to be ON the expense side, including the material he bought at
 *       the till and has not been paid back for yet (review B-33): V129's «a reimbursable receipt
 *       is a receivable, not a cost» governs the OBJECT's economy, and is still true there — but
 *       on THIS screen the money is gone until the client hands it back, and the reverse reading
 *       charged him for the same material twice, once as the refund subtracted from earnings and
 *       once, silently, by never crediting the purchase at all. The two
 *       {@code findOutOfPocketByOwnerAndPeriod} queries are written to be disjoint from
 *       {@code object_expenses}, so nothing is counted twice.</li>
 *   <li><b>«Повернення за матеріал» is an INFO line</b>, subtracted from nothing. It says which
 *       part of «Прийшло» was not payment for work; the netting is done by the purchase.</li>
 *   <li><b>Period boundaries are {@code Europe/Kyiv}</b>, never the server's UTC idea of today —
 *       on the 1st at 01:00 that would open «цей місяць» on the previous one.</li>
 * </ul>
 */
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

    private final CashEntryRepository cashRepository;
    private final PaymentReceiptRepository receiptRepository;
    private final ObjectExpenseRepository expenseRepository;
    private final ProjectReceiptRepository projectReceiptRepository;
    private final WorkActReceiptRepository actReceiptRepository;
    private final ProjectRepository projectRepository;
    private final PaymentService paymentService;
    private final ObjectExpenseService expenseService;
    private final ProjectReceiptService projectReceiptService;

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
        LocalDate start = from != null ? from : startOfMonth();
        LocalDate end = to != null ? to : endOfMonth();
        if (end.isBefore(start)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        List<CashFlowResponse.Entry> all = collect(ownerId, start, end);

        BigDecimal income = sum(all, e -> e.direction() == CashDirection.INCOME);
        BigDecimal expense = sum(all, e -> e.direction() == CashDirection.EXPENSE);
        BigDecimal refunds = sum(all, CashFlowResponse.Entry::materialRefund);
        // NOT `income − refunds − expense` (B-33). The refund repays material that is itself an
        // expense row now, so subtracting it as well charged the master for the same purchase
        // twice — and in the far commoner month where he had bought but not been paid back, the
        // 8 000 he was out of pocket read as pure profit.
        BigDecimal earned = income.subtract(expense);

        List<CashFlowResponse.Entry> entries = monthly
                ? List.of()
                : all.stream().limit(MAX_ENTRIES).toList();
        List<CashFlowResponse.MonthTotal> months = monthly ? monthTotals(all) : List.of();
        return new CashFlowResponse(start, end, income, expense, earned, refunds, entries, months,
                !monthly && all.size() > MAX_ENTRIES);
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
        LocalDate from = startOfMonth();
        LocalDate to = endOfMonth();
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
                .materialRefund(req.direction() == CashDirection.INCOME && req.materialRefund())
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
        LocalDate day = req.happenedOn() != null ? req.happenedOn() : today();
        BigDecimal amount = req.amount().setScale(MONEY_SCALE, ROUNDING);
        return switch (kindOf(req)) {
            case OBJECT_PAYMENT -> {
                PaymentReceipt receipt = ownedReceipt(ownerId, id);
                UUID projectId = receipt.getProject().getId();
                paymentService.editReceipt(projectId, id, ownerId, new PaymentReceiptEditRequest(
                        amount, day, trimToNull(req.note()), req.materialRefund()));
                yield fromReceipt(ownedReceipt(ownerId, id));
            }
            case OBJECT_EXPENSE -> {
                ObjectExpense expense = ownedExpense(ownerId, id);
                CashCategory category = req.category() != null ? req.category() : CashCategory.OTHER;
                expenseService.update(expense.getObjectId(), id, ownerId, new ExpenseRequest(
                        amount, category.toExpenseCategory(), trimToNull(req.note()), day, null));
                yield fromExpense(ownedExpense(ownerId, id), projectName(expense.getObjectId()));
            }
            case OBJECT_RECEIPT -> {
                ProjectReceipt receipt = ownedTillReceipt(ownerId, id);
                // `reimbursable` rides as null on purpose — the three-valued flag means «leave it
                // alone», and «хто за це платить» is a decision that belongs to the object's own
                // receipts screen, in front of the photo. A month's feed must not flip it in
                // passing, least of all by omission.
                String note = trimToNull(req.note());
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
                entry.setCategory(req.category());
                entry.setNote(trimToNull(req.note()));
                if (req.happenedOn() != null) {
                    // The TIME stays where it was: it says «коли я це вписав», and re-stamping it on
                    // every correction would silently reshuffle a day's order under the master.
                    entry.setHappenedOn(req.happenedOn());
                }
                entry.setMaterialRefund(req.direction() == CashDirection.INCOME && req.materialRefund());
                yield toEntry(entry);
            }
        };
    }

    /** Idempotent for every kind: a row already gone is a no-op, so a replayed queue op is harmless. */
    @Transactional
    public void delete(UUID ownerId, UUID id, CashEntryKind kind) {
        switch (kind == null ? CashEntryKind.PERSONAL : kind) {
            case OBJECT_PAYMENT -> receiptRepository.findById(id).ifPresent(r ->
                    paymentService.deleteReceipt(r.getProject().getId(), id, ownerId));
            case OBJECT_EXPENSE -> expenseRepository.findById(id).ifPresent(e ->
                    expenseService.delete(e.getObjectId(), id, ownerId));
            case OBJECT_RECEIPT -> projectReceiptRepository.findById(id).ifPresent(r ->
                    projectReceiptService.delete(r.getProjectId(), id, ownerId));
            case ACT_RECEIPT -> throw new WorkActSignedException();
            case PERSONAL -> cashRepository.findByIdAndOwnerId(id, ownerId)
                    .ifPresent(cashRepository::delete);
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
                .findOutOfPocketByOwnerAndPeriod(ownerId, from, to, fromTs, toTs);

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
        for (ObjectExpense e : expenses) {
            all.add(fromExpense(e, names.get(e.getObjectId())));
        }
        for (CashEntry c : own) {
            all.add(toEntry(c));
        }
        for (ProjectReceipt r : tillReceipts) {
            all.add(fromTillReceipt(r, names.get(r.getProjectId())));
        }
        for (WorkActReceipt r : actReceipts) {
            all.add(fromActReceipt(r));
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
                project.getId(), project.getName(), r.isMaterialRefund(), planned, false);
    }

    private static CashFlowResponse.Entry fromExpense(ObjectExpense e, String projectName) {
        return new CashFlowResponse.Entry(
                e.getId(), CashEntryKind.OBJECT_EXPENSE, CashDirection.EXPENSE, e.getAmount(),
                fromExpenseCategory(e), e.getNote(), e.getSpentAt(), null,
                e.getObjectId(), projectName, false, false, false);
    }

    /**
     * Material bought at the till that the client has not paid back yet (review B-33).
     *
     * <p>It reads as MATERIALS spending, because that is what it was. The row stays editable — one
     * door to one record, as everywhere here — except once an act has billed it: from that moment
     * the paper is inside a document the client holds, and what may still be corrected about it
     * belongs on the object's receipts screen, not in a month's feed.</p>
     */
    private static CashFlowResponse.Entry fromTillReceipt(ProjectReceipt r, String projectName) {
        boolean billed = r.getBilledOnActId() != null;
        return new CashFlowResponse.Entry(
                r.getId(), CashEntryKind.OBJECT_RECEIPT, CashDirection.EXPENSE, r.getAmount(),
                CashCategory.MATERIALS, r.getLabel(),
                r.getIssuedAt() != null ? r.getIssuedAt() : dayOf(r.getCreatedAt()), null,
                r.getProjectId(), projectName, false, billed, billed);
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
                r.getIssuedAt() != null ? r.getIssuedAt() : dayOf(r.getCreatedAt()), null,
                project.getId(), project.getName(), false, true, true);
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
                false, false);
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

    private static LocalDate startOfMonth() {
        return today().withDayOfMonth(1);
    }

    private static LocalDate endOfMonth() {
        LocalDate now = today();
        return now.withDayOfMonth(now.lengthOfMonth());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
