package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.CashEntryKind;
import com.majstr.backend.dto.CashEntryRequest;
import com.majstr.backend.dto.CashFlowResponse;
import com.majstr.backend.dto.CashSummaryResponse;
import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.ExpenseResponse;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.dto.ProjectReceiptRequest;
import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.CashDirection;
import com.majstr.backend.entity.CashEntry;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.PaymentReceipt;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.repository.CashEntryRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;
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
    @Mock private ProjectReceiptRepository projectReceiptRepository;
    @Mock private WorkActReceiptRepository actReceiptRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private PaymentService paymentService;
    @Mock private ObjectExpenseService expenseService;
    @Mock private ProjectReceiptService projectReceiptService;
    @Mock private ProjectService projectService;
    @Mock private com.majstr.backend.storage.StorageService storage;
    @Mock private StorageCleanup cleanup;
    @Mock private com.majstr.backend.service.fiscal.FiscalQrReceiptReader qrReader;

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
        // An expense row carries a bare objectId, so proving the owner needs the object (B-45:
        // someone else's row is a 404 here, never the object service's 403).
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project()));

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
     * The ways a master can record «work 10 000, material 2 000» (review B-33, object-money
     * iteration).
     *
     * <p>«Заробив» is one subtraction over the MASTER's money. The client's material money — a till
     * receipt «клієнт поверне», a payment «на матеріал» — is in neither total, so the purchase and
     * its repayment cannot swing the figure, whichever month each lands in. Money the client gave
     * «на матеріал» against material the master booked as his OWN cost is still the client's money:
     * it shows as an unspent material balance, never as earnings.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("theSixWaysOfRecordingOneJob")
    void earnedIsIncomeMinusOutlays(String name, List<PaymentReceipt> receipts,
                                    List<ObjectExpense> expenses, List<CashEntry> own,
                                    List<ProjectReceipt> till, List<WorkActReceipt> actReceipts,
                                    String earned) {
        seedFeed(receipts, expenses, own, till, actReceipts);

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.earned()).isEqualByComparingTo(earned);
    }

    private static Stream<Arguments> theSixWaysOfRecordingOneJob() {
        List<PaymentReceipt> workOnly = List.of(receipt("10000.00", false));
        List<PaymentReceipt> workAndRefund =
                List.of(receipt("10000.00", false), receipt("2000.00", true));
        return Stream.of(
                // 1. He bought at the till, the client paid it back. The purchase and the refund
                //    cancel; only the work is earnings.
                Arguments.of("reimbursable till receipt, refund arrived",
                        workAndRefund, List.of(), List.of(), List.of(tillReceipt("2000.00")),
                        List.of(), "10000.00"),
                // 2. He bought and is waiting to be paid back. The debt is the material balance,
                //    not a loss: «Заробив» does not dip for a month and jump back the next.
                Arguments.of("reimbursable till receipt, refund not yet arrived",
                        workOnly, List.of(), List.of(), List.of(tillReceipt("2000.00")),
                        List.of(), "10000.00"),
                // 3. «Це моя витрата» writes an `object_expenses` row: his cost. The 2 000 the
                //    client handed over «на матеріал» stays the client's — unspent, not earned.
                Arguments.of("till receipt marked «моя витрата», payment ticked as material money",
                        workAndRefund, List.of(expense("2000.00")), List.of(), List.of(),
                        List.of(), "8000.00"),
                // 4. `receipts_to_expenses` ON: signing posted the act receipt as an expense, so the
                //    query that feeds ACT_RECEIPT rows deliberately skips it.
                Arguments.of("act receipt posted as an expense, material money ticked",
                        workAndRefund, List.of(expense("2000.00")), List.of(), List.of(),
                        List.of(), "8000.00"),
                // 5. He typed the purchase here himself instead of photographing it.
                Arguments.of("personal MATERIALS row, material money ticked",
                        workAndRefund, List.of(), List.of(personal(CashDirection.EXPENSE, "2000.00")),
                        List.of(), List.of(), "8000.00"),
                // 6. `receipts_to_expenses` OFF: the client paid 12 000 on the act and nothing else
                //    records the 2 000, which is exactly what the ACT_RECEIPT row is for.
                Arguments.of("act with receipts_to_expenses off",
                        List.of(receipt("12000.00", false)), List.of(), List.of(), List.of(),
                        List.of(actReceipt("2000.00", "0.00")), "10000.00"));
    }

    /**
     * The client's material money is shown and counted nowhere: neither «Прийшло» nor «Витрачено»
     * carries it, and both rows come back marked so the screen can draw them without a sign.
     */
    @Test
    void theClientsMaterialMoneyIsInNeitherTotal() {
        seedFeed(List.of(receipt("10000.00", false), receipt("2000.00", true)),
                List.of(), List.of(), List.of(tillReceipt("2000.00")), List.of());

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.income()).isEqualByComparingTo("10000.00");
        assertThat(flow.expense()).isEqualByComparingTo("0.00");
        assertThat(flow.refunds()).isEqualByComparingTo("2000.00");
        assertThat(flow.entries()).filteredOn(CashFlowResponse.Entry::clientMaterial).hasSize(2);
    }

    /** A till receipt an act has billed is repaid inside the act, as work money: his expense. */
    @Test
    void aTillReceiptBilledOnAnActIsTheMastersExpense() {
        ProjectReceipt billed = tillReceipt("2000.00");
        billed.setBilledOnActId(UUID.randomUUID());
        seedFeed(List.of(receipt("12000.00", false)), List.of(), List.of(), List.of(billed), List.of());

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.expense()).isEqualByComparingTo("2000.00");
        assertThat(flow.earned()).isEqualByComparingTo("10000.00");
    }

    /**
     * What each client still owes for material is ALL TIME and per object, from the same two sums
     * the object's own card reads — and an object whose balance is settled is not listed.
     */
    @Test
    void theMaterialDebtIsListedPerObjectAndASettledOneIsNot() {
        UUID settled = UUID.randomUUID();
        UUID credit = UUID.randomUUID();
        seedFeed(List.of(), List.of(), List.of());
        when(projectReceiptRepository.sumReimbursableByOwner(OWNER)).thenReturn(List.of(
                new Object[]{PROJECT, new BigDecimal("5000.00")},
                new Object[]{settled, new BigDecimal("700.00")}));
        when(receiptRepository.sumMaterialRefundsByOwner(OWNER)).thenReturn(List.of(
                new Object[]{PROJECT, new BigDecimal("1200.00")},
                new Object[]{settled, new BigDecimal("700.00")},
                new Object[]{credit, new BigDecimal("3000.00")}));

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.materials()).extracting(CashFlowResponse.MaterialBalance::projectId)
                .containsExactly(PROJECT, credit);
        assertThat(flow.materials().get(0).outstanding()).isEqualByComparingTo("3800.00");
        assertThat(flow.materials().get(1).outstanding()).isEqualByComparingTo("-3000.00");
    }

    /**
     * The period split into objects and «особисте» — the master's ask, so he can tell his own
     * spending from the jobs'. The slices are cut by the same rule as the totals and add up to them,
     * and an object that only moved the client's material money is listed with zeros.
     */
    @Test
    void thePeriodSplitsIntoObjectsAndPersonal_andTheSlicesAddUpToTheTotals() {
        UUID other = UUID.randomUUID();
        PaymentReceipt materialOnly = receipt("5000.00", true);
        materialOnly.setProject(Project.builder().id(other).name("Тест Б").owner(owner()).build());
        seedFeed(List.of(receipt("10000.00", false), materialOnly), List.of(expense("500.00")),
                List.of(personal(CashDirection.EXPENSE, "800.00")));

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.objects()).extracting(CashFlowResponse.ScopeTotal::projectId)
                .containsExactlyInAnyOrder(PROJECT, other);
        CashFlowResponse.ScopeTotal job = flow.objects().stream()
                .filter(o -> PROJECT.equals(o.projectId())).findFirst().orElseThrow();
        assertThat(job.earned()).isEqualByComparingTo("9500.00");
        CashFlowResponse.ScopeTotal clientOnly = flow.objects().stream()
                .filter(o -> other.equals(o.projectId())).findFirst().orElseThrow();
        assertThat(clientOnly.income()).isEqualByComparingTo("0.00");
        assertThat(clientOnly.earned()).isEqualByComparingTo("0.00");
        assertThat(flow.personal()).isNotNull();
        assertThat(flow.personal().projectId()).isNull();
        assertThat(flow.personal().earned()).isEqualByComparingTo("-800.00");

        BigDecimal slices = flow.objects().stream().map(CashFlowResponse.ScopeTotal::earned)
                .reduce(flow.personal().earned(), BigDecimal::add);
        assertThat(slices).isEqualByComparingTo(flow.earned());
    }

    @Test
    void noPersonalRowsMeansNoPersonalSlice() {
        seedFeed(List.of(receipt("10000.00", false)), List.of(), List.of());

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.personal()).isNull();
        assertThat(flow.objects()).singleElement()
                .satisfies(o -> assertThat(o.projectName()).isEqualTo("Квартира на Лесі"));
    }

    /**
     * The owner's «Тест Б»: 5 000 ₴ given for material, 763,72 ₴ of till receipts «клієнт поверне»,
     * then act № 9 billing 16 388 ₴ of receipts. What is left of the material money paid part of
     * that purchase — not income (owner's ruling), the part that did not leave his own pocket: the
     * act's expense row shows 12 151,72 ₴ of his own and 4 236,28 ₴ of the client's.
     */
    @Test
    void whatIsLeftOfTheMaterialMoneyPaysPartOfTheActsPurchase_notHisIncome() {
        UUID act = UUID.randomUUID();
        ObjectExpense posted = expense("16388.00");
        posted.setWorkActId(act);
        posted.setNote("Чек до акта № 9: Електромаркет");
        seedFeed(List.of(receipt("5000.00", true)), List.of(posted), List.of(), List.of(tillReceipt("763.72")), List.of());
        when(receiptRepository.sumMaterialRefundsByOwner(OWNER))
                .thenReturn(List.<Object[]>of(new Object[]{PROJECT, new BigDecimal("5000.00")}));
        when(projectReceiptRepository.sumReimbursableByOwner(OWNER))
                .thenReturn(List.<Object[]>of(new Object[]{PROJECT, new BigDecimal("763.72")}));
        Instant signed = DAY.atTime(15, 0).atZone(LocalizationConfig.ZONE).toInstant();
        when(actReceiptRepository.sumSignedActReceiptsByActForOwner(OWNER))
                .thenReturn(List.<Object[]>of(new Object[]{PROJECT, act, "9", signed, new BigDecimal("16388.00")}));
        when(expenseRepository.findByWorkActIds(any())).thenReturn(List.of(posted));

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.entries()).filteredOn(e -> e.id().equals(posted.getId())).singleElement()
                .satisfies(e -> {
                    assertThat(e.amount()).isEqualByComparingTo("12151.72");
                    assertThat(e.clientShare()).isEqualByComparingTo("4236.28");
                });
        assertThat(flow.income()).isEqualByComparingTo("0.00");
        assertThat(flow.expense()).isEqualByComparingTo("12151.72");
        // Settled: 763,72 bought + 4 236,28 into the act = the 5 000 given.
        assertThat(flow.materials()).isEmpty();
    }

    /** The object filter narrows the rows, the totals AND the material lines alike. */
    @Test
    void anObjectFilterNarrowsEverything() {
        UUID other = UUID.randomUUID();
        PaymentReceipt elsewhere = receipt("4000.00", false);
        elsewhere.setProject(Project.builder().id(other).name("Інший").owner(owner()).build());
        seedFeed(List.of(receipt("10000.00", false), elsewhere), List.of(),
                List.of(personal(CashDirection.EXPENSE, "300.00")));
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project()));
        when(projectReceiptRepository.sumReimbursableByOwner(OWNER)).thenReturn(List.of(
                new Object[]{PROJECT, new BigDecimal("500.00")},
                new Object[]{other, new BigDecimal("900.00")}));

        CashFlowResponse one = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false,
                CashScope.object(PROJECT));
        CashFlowResponse none = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false,
                CashScope.OFF_OBJECT);

        assertThat(one.income()).isEqualByComparingTo("10000.00");
        assertThat(one.expense()).isEqualByComparingTo("0.00");
        assertThat(one.materials()).singleElement()
                .satisfies(m -> assertThat(m.projectId()).isEqualTo(PROJECT));
        assertThat(none.income()).isEqualByComparingTo("0.00");
        assertThat(none.expense()).isEqualByComparingTo("300.00");
        assertThat(none.materials()).isEmpty();
    }

    /** Filtering by an object that is not his answers «not found», like any foreign money id. */
    @Test
    void filteringByAnotherMastersObjectIsNotFound() {
        Project foreign = Project.builder().id(PROJECT).name("Чужий")
                .owner(com.majstr.backend.entity.User.builder().id(UUID.randomUUID()).build()).build();
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30),
                false, CashScope.object(PROJECT)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * «Гроші обʼєкта» is the same feed narrowed to one object: the same «Заробив», the material
     * balance on its own line, and an honest word when no crew pay was ever recorded.
     */
    @Test
    void anObjectsMoneyIsTheFeedNarrowedToIt() {
        UUID other = UUID.randomUUID();
        PaymentReceipt elsewhere = receipt("4000.00", false);
        elsewhere.setProject(Project.builder().id(other).name("Інший").owner(owner()).build());
        ObjectExpense crew = expense("3000.00");
        crew.setCategory(ExpenseCategory.LABOR);
        seedFeed(List.of(receipt("10000.00", false), receipt("1000.00", true), elsewhere),
                List.of(crew), List.of(personal(CashDirection.EXPENSE, "300.00")),
                List.of(tillReceipt("2500.00")), List.of());
        when(projectService.loadOwned(PROJECT, OWNER)).thenReturn(project());
        when(receiptRepository.sumMaterialRefunds(PROJECT)).thenReturn(new BigDecimal("1000.00"));
        when(projectReceiptRepository.sumReimbursable(PROJECT)).thenReturn(new BigDecimal("2500.00"));

        com.majstr.backend.dto.ObjectMoneyResponse money = service.objectMoney(OWNER, PROJECT);

        assertThat(money.income()).isEqualByComparingTo("10000.00");
        assertThat(money.expense()).isEqualByComparingTo("3000.00");
        assertThat(money.earned()).isEqualByComparingTo("7000.00");
        assertThat(money.crewRecorded()).isTrue();
        assertThat(money.materials().bought()).isEqualByComparingTo("2500.00");
        assertThat(money.materials().given()).isEqualByComparingTo("1000.00");
        assertThat(money.materials().outstanding()).isEqualByComparingTo("1500.00");
        assertThat(money.entries()).allSatisfy(e -> assertThat(e.projectId()).isEqualTo(PROJECT));
    }

    /** Deleting his own row takes its photo with it — after the commit, never before (B-25). */
    @Test
    void deletingAPersonalRowDropsItsPhotoAfterTheCommit() {
        CashEntry own = personal(CashDirection.EXPENSE, "1450.00");
        own.setStorageKey("cash-receipts/abc.jpg");
        when(cashRepository.findByIdAndOwnerId(own.getId(), OWNER)).thenReturn(Optional.of(own));

        service.delete(OWNER, own.getId(), CashEntryKind.PERSONAL);

        verify(cashRepository).delete(own);
        verify(cleanup).afterCommit("cash-receipts/abc.jpg");
    }

    /** A month's figures and the year view's must be the same arithmetic, or one of them is a lie. */
    @Test
    void aMonthTotalSubtractsExactlyWhatThePeriodTotalDoes() {
        seedFeed(List.of(receipt("10000.00", false), receipt("2000.00", true)),
                List.of(), List.of(), List.of(tillReceipt("2000.00")), List.of());

        CashFlowResponse flow = service.flow(OWNER, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31), true);

        assertThat(flow.months()).singleElement()
                .satisfies(m -> assertThat(m.earned()).isEqualByComparingTo("10000.00"));
        assertThat(flow.earned()).isEqualByComparingTo("10000.00");
    }

    /** A part of the material handed back to the shop cost him nothing (V115). */
    @Test
    void anActReceiptCountsWhatWasActuallyKept() {
        seedFeed(List.of(), List.of(), List.of(), List.of(),
                List.of(actReceipt("2000.00", "500.00")));

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.expense()).isEqualByComparingTo("1500.00");
    }

    // ---- the two rows a signed document owns ------------------------------

    /** One door to one record here too: the till receipt is written by the OBJECT's own service. */
    @Test
    void editingATillReceiptWritesThroughTheObjectsOwnService() {
        ProjectReceipt till = tillReceipt("2000.00");
        when(projectReceiptRepository.findById(till.getId())).thenReturn(Optional.of(till));
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project()));

        service.update(OWNER, till.getId(), new CashEntryRequest(
                CashDirection.EXPENSE, new BigDecimal("2100.00"), CashCategory.MATERIALS,
                "Клей", DAY, false, CashEntryKind.OBJECT_RECEIPT));

        ArgumentCaptor<ProjectReceiptRequest> req =
                ArgumentCaptor.forClass(ProjectReceiptRequest.class);
        verify(projectReceiptService).update(eq(PROJECT), eq(till.getId()), eq(OWNER), req.capture());
        assertThat(req.getValue().amount()).isEqualByComparingTo("2100.00");
        assertThat(req.getValue().label()).isEqualTo("Клей");
        // «Хто за це платить» belongs to the object's receipts screen, in front of the photo — a
        // month's feed must not flip it in passing, least of all by omission.
        assertThat(req.getValue().reimbursable()).isNull();
    }

    @Test
    void deletingATillReceiptGoesThroughTheSameDoor() {
        ProjectReceipt till = tillReceipt("2000.00");
        when(projectReceiptRepository.findById(till.getId())).thenReturn(Optional.of(till));
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project())); // B-45

        service.delete(OWNER, till.getId(), CashEntryKind.OBJECT_RECEIPT);

        verify(projectReceiptService).delete(PROJECT, till.getId(), OWNER);
    }

    /**
     * Once an act has billed the paper it is inside a document the client holds, so the feed marks
     * the row read-only rather than offering an edit that the object's screen would refuse.
     */
    @Test
    void aTillReceiptAlreadyBilledOnAnActIsReadOnly() {
        ProjectReceipt billed = tillReceipt("2000.00");
        billed.setBilledOnActId(UUID.randomUUID());
        seedFeed(List.of(), List.of(), List.of(), List.of(billed), List.of());

        CashFlowResponse flow = service.flow(OWNER, DAY.withDayOfMonth(1), DAY.withDayOfMonth(30), false);

        assertThat(flow.entries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.readOnly()).isTrue();
                    assertThat(e.noteLocked()).isTrue();
                });
    }

    /**
     * An act receipt's figure is frozen inside the signed act's {@code doc_hash}: editing it could
     * only either lie about the client's document or invalidate it. 409, never a silent no-op.
     */
    @Test
    void anActReceiptCannotBeEditedOrDeletedFromTheFeed() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(OWNER, id, new CashEntryRequest(
                CashDirection.EXPENSE, BigDecimal.TEN, CashCategory.MATERIALS, null, DAY, false,
                CashEntryKind.ACT_RECEIPT)))
                .isInstanceOf(WorkActSignedException.class);
        assertThatThrownBy(() -> service.delete(OWNER, id, CashEntryKind.ACT_RECEIPT))
                .isInstanceOf(WorkActSignedException.class);

        verifyNoInteractions(projectReceiptService);
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

    /**
     * The strip sums the calendar MONTH (master's call — a week there was too small a window to be
     * worth a glance), and it answers the window it used so the client can label it rather than
     * re-deriving one and getting it subtly different.
     */
    @Test
    void theHomeStripSumsTheCalendarMonth() {
        seedFeed(List.of(), List.of(), List.of(personal(CashDirection.INCOME, "5000.00")));

        CashSummaryResponse summary = service.summary(OWNER);

        LocalDate today = LocalDate.now(LocalizationConfig.ZONE);
        assertThat(summary.from()).isEqualTo(today.withDayOfMonth(1));
        assertThat(summary.to()).isEqualTo(today.withDayOfMonth(today.lengthOfMonth()));
        assertThat(summary.hasEntries()).isTrue();
    }

    // ---- review round 2 §2: the period, and what an omitted field means -------------------

    /**
     * B-52. Reversed bounds were swapped in silence, so a caller that believed something about a
     * period got an answer about a different one — and a strip and a screen disagreeing about one
     * number is the one thing a money screen may not do.
     */
    @Test
    void aReversedPeriodIsRefused_notSilentlySwapped() {
        assertThatThrownBy(() -> service.flow(OWNER, LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 10, 1), false))
                .isInstanceOf(com.majstr.backend.exception.PaymentValidationException.class)
                .hasMessage("error.cash.period-reversed");
    }

    /** B-52. The widest view is a year, served month by month; anything beyond thirteen months
     *  would load every row the master has ever had in order to cut it to 500. */
    @Test
    void aPeriodWiderThanThirteenMonthsIsRefused() {
        assertThatThrownBy(() -> service.flow(OWNER, LocalDate.of(2024, 1, 1),
                LocalDate.of(2026, 1, 1), false))
                .isInstanceOf(com.majstr.backend.exception.PaymentValidationException.class)
                .hasMessage("error.cash.period-too-long");
    }

    /**
     * B-44. The object-expense edit builds its request by hand, so an omitted category used to
     * become OTHER — turning a MATERIALS cost into «Інше» — and an omitted date became today,
     * moving the row into a month the master was not even looking at. Latent while the PWA sends
     * both, and exactly the trap a replayed offline op walks into.
     */
    @Test
    void editingAnObjectExpenseWithoutACategoryOrADateKeepsTheStoredOnes() {
        ObjectExpense expense = expense("800.00");
        expense.setCategory(ExpenseCategory.MATERIALS);
        expense.setSpentAt(LocalDate.of(2026, 3, 7));
        when(expenseRepository.findById(expense.getId())).thenReturn(Optional.of(expense));
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project()));

        service.update(OWNER, expense.getId(), new CashEntryRequest(
                CashDirection.EXPENSE, new BigDecimal("900.00"), null, "клей", null, false,
                CashEntryKind.OBJECT_EXPENSE));

        ArgumentCaptor<ExpenseRequest> sent = ArgumentCaptor.forClass(ExpenseRequest.class);
        verify(expenseService).update(eq(PROJECT), eq(expense.getId()), eq(OWNER), sent.capture());
        assertThat(sent.getValue().category()).isEqualTo(ExpenseCategory.MATERIALS);
        assertThat(sent.getValue().spentAt()).isEqualTo(LocalDate.of(2026, 3, 7));
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
        seedFeed(receipts, expenses, own, List.of(), List.of());
    }

    private void seedFeed(List<PaymentReceipt> receipts, List<ObjectExpense> expenses,
                          List<CashEntry> own, List<ProjectReceipt> till,
                          List<WorkActReceipt> actReceipts) {
        when(receiptRepository.findByOwnerAndPeriod(eq(OWNER), any(), any())).thenReturn(receipts);
        when(expenseRepository.findByOwnerAndPeriod(eq(OWNER), any(), any())).thenReturn(expenses);
        when(cashRepository.findByOwnerAndPeriod(eq(OWNER), any(), any())).thenReturn(own);
        when(projectReceiptRepository.findOutOfPocketByOwnerAndPeriod(eq(OWNER), any(), any(), any(),
                any())).thenReturn(till);
        when(actReceiptRepository.findOutOfPocketByOwnerAndPeriod(eq(OWNER), any(), any()))
                .thenReturn(actReceipts);
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

    private static ProjectReceipt tillReceipt(String amount) {
        return ProjectReceipt.builder()
                .id(UUID.randomUUID()).projectId(PROJECT).label("Клей").amount(new BigDecimal(amount))
                .issuedAt(DAY).reimbursable(true)
                .build();
    }

    private static WorkActReceipt actReceipt(String amount, String returned) {
        WorkAct act = WorkAct.builder()
                .id(UUID.randomUUID()).userId(OWNER).project(project()).number("7")
                .build();
        return WorkActReceipt.builder()
                .id(UUID.randomUUID()).workAct(act).label("Плитка").amount(new BigDecimal(amount))
                .returnedAmount(new BigDecimal(returned)).issuedAt(DAY)
                .build();
    }

    private static CashEntry personal(CashDirection direction, String amount) {
        return CashEntry.builder()
                .id(UUID.randomUUID()).ownerId(OWNER).direction(direction)
                .amount(new BigDecimal(amount)).happenedOn(DAY).happenedAt(Instant.now())
                .build();
    }
}
