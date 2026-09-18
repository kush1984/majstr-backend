package com.majstr.backend.service;

import com.majstr.backend.dto.CashEntryKind;
import com.majstr.backend.dto.CashEntryRequest;
import com.majstr.backend.dto.CashFlowResponse;
import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.ExpenseResponse;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.CashDirection;
import com.majstr.backend.entity.CashEntry;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.PaymentReceipt;
import com.majstr.backend.entity.Project;
import com.majstr.backend.repository.CashEntryRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * «Мої гроші» (V135) — what the screen is, said as tests.
 *
 * <p>Two things carry the whole feature and are pinned here: money that belongs to an object is
 * written to that OBJECT (one record, never two), and «Заробив» is not «Прийшло» — material the
 * client merely paid back is not earnings.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashFlowServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);

    @Mock private CashEntryRepository cashRepository;
    @Mock private PaymentReceiptRepository receiptRepository;
    @Mock private ObjectExpenseRepository expenseRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private PaymentService paymentService;
    @Mock private ObjectExpenseService expenseService;

    @InjectMocks private CashFlowService service;

    // ---- adding: always his own -------------------------------------------

    /**
     * Adding here asks nothing about an object (master's ruling, round 3: «я хочу, щоб гроші/доходи
     * зразу брались з обʼєктів, а не щось там вибиралось»). Object money is already in the object's
     * journal and arrives on the READ path; what he types here is what no object knows about.
     */
    @Test
    void whatHeTypesIsAlwaysHisOwnRow_andNoObjectServiceIsTouched() {
        savesWithAnId();

        CashFlowResponse.Entry saved = service.create(OWNER, new CashEntryRequest(
                CashDirection.EXPENSE, new BigDecimal("1200.00"), CashCategory.FUEL,
                "Дизель", DAY, false, null), null);

        assertThat(saved.kind()).isEqualTo(CashEntryKind.PERSONAL);
        assertThat(saved.projectId()).isNull();
        verifyNoInteractions(paymentService);
        verifyNoInteractions(expenseService);
    }

    /** A refund is income, never spending — marking an expense so would skew «Заробив» upward. */
    @Test
    void theRefundFlagIsRefusedOnSpending() {
        savesWithAnId();

        CashFlowResponse.Entry saved = service.create(OWNER, new CashEntryRequest(
                CashDirection.EXPENSE, new BigDecimal("500.00"), CashCategory.TOOLS,
                null, DAY, true, null), null);

        assertThat(saved.materialRefund()).isFalse();
    }

    // ---- editing and deleting ANY row, in place ---------------------------

    /**
     * An object's payment is edited from this screen — a second DOOR to one record, never a second
     * copy. It goes through the object's own service, so every rule that service has still holds.
     */
    @Test
    void editingAnObjectPaymentWritesThroughTheObjectsOwnService() {
        PaymentReceipt receipt = receipt("6000.00", false);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        service.update(OWNER, receipt.getId(), new CashEntryRequest(
                CashDirection.INCOME, new BigDecimal("6500.00"), null, "Друга частина", DAY,
                true, CashEntryKind.OBJECT_PAYMENT));

        ArgumentCaptor<PaymentReceiptEditRequest> req =
                ArgumentCaptor.forClass(PaymentReceiptEditRequest.class);
        verify(paymentService).editReceipt(eq(PROJECT), eq(receipt.getId()), eq(OWNER), req.capture());
        assertThat(req.getValue().amount()).isEqualByComparingTo("6500.00");
        assertThat(req.getValue().label()).isEqualTo("Друга частина");
        // The refund flag rides the SAME door as on the create — a second write path just for one
        // boolean is the thing that drifts.
        assertThat(req.getValue().materialRefund()).isTrue();
        verify(cashRepository, never()).save(any());
    }

    @Test
    void editingAnObjectExpenseMapsTheCategoryBackToTheObjectsOwnBucket() {
        ObjectExpense expense = expense("800.00");
        when(expenseRepository.findById(expense.getId())).thenReturn(Optional.of(expense));
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project()));

        service.update(OWNER, expense.getId(), new CashEntryRequest(
                CashDirection.EXPENSE, new BigDecimal("950.00"), CashCategory.CREW, "Хлопцям", DAY,
                false, CashEntryKind.OBJECT_EXPENSE));

        ArgumentCaptor<ExpenseRequest> req = ArgumentCaptor.forClass(ExpenseRequest.class);
        verify(expenseService).update(eq(PROJECT), eq(expense.getId()), eq(OWNER), req.capture());
        // CREW is the personal word for what the object calls LABOR — never a finer bucket than the
        // object actually has.
        assertThat(req.getValue().category()).isEqualTo(ExpenseCategory.LABOR);
    }

    /** Someone else's row is «not found», never «forbidden»: a money id is not worth confirming. */
    @Test
    void anotherMastersRowCannotBeReachedByItsId() {
        PaymentReceipt his = receipt("6000.00", false);
        when(receiptRepository.findById(his.getId())).thenReturn(Optional.of(his));

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), his.getId(), new CashEntryRequest(
                CashDirection.INCOME, BigDecimal.TEN, null, null, DAY, false,
                CashEntryKind.OBJECT_PAYMENT)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(paymentService);
    }

    @Test
    void deleteRoutesByKind() {
        PaymentReceipt receipt = receipt("6000.00", false);
        ObjectExpense expense = expense("800.00");
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(expenseRepository.findById(expense.getId())).thenReturn(Optional.of(expense));

        service.delete(OWNER, receipt.getId(), CashEntryKind.OBJECT_PAYMENT);
        service.delete(OWNER, expense.getId(), CashEntryKind.OBJECT_EXPENSE);

        verify(paymentService).deleteReceipt(PROJECT, receipt.getId(), OWNER);
        verify(expenseService).delete(PROJECT, expense.getId(), OWNER);
    }

    /** Idempotent everywhere: a replayed offline delete of a row already gone must not throw. */
    @Test
    void deletingARowThatIsAlreadyGoneIsANoOp() {
        when(receiptRepository.findById(any())).thenReturn(Optional.empty());

        service.delete(OWNER, UUID.randomUUID(), CashEntryKind.OBJECT_PAYMENT);

        verifyNoInteractions(paymentService);
    }

    // ---- the three numbers ------------------------------------------------

    /**
     * «Прийшло» counts a reimbursement — the money really arrived. «Заробив» does not, or a month is
     * inflated by exactly the material the client paid back.
     */
    @Test
    void earnedExcludesWhatTheClientMerelyPaidBack() {
        seedFeed(
                List.of(receipt("20000.00", false), receipt("8000.00", true)),
                List.of(expense("3000.00")),
                List.of());

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.income()).isEqualByComparingTo("28000.00");
        assertThat(flow.refunds()).isEqualByComparingTo("8000.00");
        assertThat(flow.expense()).isEqualByComparingTo("3000.00");
        assertThat(flow.earned()).isEqualByComparingTo("17000.00"); // 28 000 − 8 000 − 3 000
    }

    @Test
    void allThreeSourcesLandInOneFeed() {
        seedFeed(
                List.of(receipt("5000.00", false)),
                List.of(expense("800.00")),
                List.of(personal(CashDirection.EXPENSE, "1200.00")));

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.entries()).extracting(CashFlowResponse.Entry::kind)
                .containsExactlyInAnyOrder(CashEntryKind.OBJECT_PAYMENT, CashEntryKind.OBJECT_EXPENSE,
                        CashEntryKind.PERSONAL);
        assertThat(flow.expense()).isEqualByComparingTo("2000.00");
        assertThat(flow.truncated()).isFalse();
    }

    /**
     * An object row carries a bare date, a personal one carries a time. Sorting on the day FIRST is
     * what keeps the two comparable — sorting on a timestamp would drop every object row to midnight
     * and scatter the day.
     */
    @Test
    void aPersonalRowWithATimeStillSortsInsideItsOwnDay() {
        ObjectExpense sameDay = expense("800.00");
        sameDay.setSpentAt(DAY);
        CashEntry earlierDay = personal(CashDirection.EXPENSE, "100.00");
        earlierDay.setHappenedOn(DAY.minusDays(1));
        seedFeed(List.of(), List.of(sameDay), List.of(earlierDay));

        CashFlowResponse flow = service.flow(OWNER, DAY.minusDays(5), DAY, false);

        assertThat(flow.entries()).extracting(CashFlowResponse.Entry::happenedOn)
                .containsExactly(DAY, DAY.minusDays(1));
    }

    /** The year view: a phone does not render two thousand rows, so it answers months instead. */
    @Test
    void theYearViewAnswersMonthsAndNoFlatList() {
        CashEntry august = personal(CashDirection.INCOME, "1000.00");
        august.setHappenedOn(LocalDate.of(2026, 8, 3));
        CashEntry september = personal(CashDirection.INCOME, "2000.00");
        september.setHappenedOn(LocalDate.of(2026, 9, 3));
        seedFeed(List.of(), List.of(), List.of(august, september));

        CashFlowResponse flow = service.flow(OWNER, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31), true);

        assertThat(flow.entries()).isEmpty();
        assertThat(flow.months()).extracting(CashFlowResponse.MonthTotal::month)
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 1));
        assertThat(flow.months().getFirst().income()).isEqualByComparingTo("2000.00");
        // Totals are still over the WHOLE period, not over what the list shows.
        assertThat(flow.income()).isEqualByComparingTo("3000.00");
    }

    @Test
    void anEmptyMonthTellsTheHomeStripNotToRender() {
        seedFeed(List.of(), List.of(), List.of());

        assertThat(service.summary(OWNER).hasEntries()).isFalse();
    }

    // ---- fixtures ---------------------------------------------------------

    /** The id is normally assigned by {@code @PrePersist}, which a mocked repository never runs. */
    private void savesWithAnId() {
        when(cashRepository.save(any())).thenAnswer(i -> {
            CashEntry e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
    }

    private void seedFeed(List<PaymentReceipt> receipts, List<ObjectExpense> expenses,
                          List<CashEntry> own) {
        when(receiptRepository.findByOwnerAndPeriod(eq(OWNER), any(), any())).thenReturn(receipts);
        when(expenseRepository.findByOwnerAndPeriod(eq(OWNER), any(), any())).thenReturn(expenses);
        when(cashRepository.findByOwnerAndPeriod(eq(OWNER), any(), any())).thenReturn(own);
        when(projectRepository.findAllById(any())).thenReturn(List.of(project()));
    }

    private static com.majstr.backend.entity.User owner() {
        return com.majstr.backend.entity.User.builder().id(OWNER).build();
    }

    private static Project project() {
        return Project.builder().id(PROJECT).name("Квартира на Лесі").owner(owner()).build();
    }

    private static PaymentReceipt receipt(String amount, boolean refund) {
        return PaymentReceipt.builder()
                .id(UUID.randomUUID()).project(project()).amount(new BigDecimal(amount))
                .receivedAt(DAY).label("Оплата").materialRefund(refund)
                .build();
    }

    private static ObjectExpense expense(String amount) {
        return ObjectExpense.builder()
                .id(UUID.randomUUID()).objectId(PROJECT).amount(new BigDecimal(amount))
                .category(ExpenseCategory.MATERIALS).source(ExpenseSource.MANUAL).spentAt(DAY)
                .build();
    }

    private static CashEntry personal(CashDirection direction, String amount) {
        return CashEntry.builder()
                .id(UUID.randomUUID()).ownerId(OWNER).direction(direction)
                .amount(new BigDecimal(amount)).happenedOn(DAY).happenedAt(Instant.now())
                .build();
    }
}
