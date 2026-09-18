package com.majstr.backend.service;

import com.majstr.backend.dto.ReceiptDuplicateRef;
import com.majstr.backend.dto.ReceiptKind;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.repository.WorkActRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The matching rules behind «цей чек уже є» (review item B-04).
 *
 * <p>The answer has to be STABLE — the master reads it beside a paper he is holding — so two rules
 * decide which row of a pair carries the warning, and both are asserted here: the earliest row wins
 * (so the warning lands on the paper filed second, V129's behaviour), and the same table beats the
 * other one (so a warning sends him to the list he is already looking at).</p>
 */
@ExtendWith(MockitoExtension.class)
class ReceiptIdentityIndexTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final String FN = "4000123456";

    @Mock private ProjectReceiptRepository projectReceipts;
    @Mock private WorkActReceiptRepository actReceipts;
    @Mock private WorkActRepository acts;

    @InjectMocks private ReceiptIdentityIndex index;

    @Test
    void anUnidentifiedPaperWarnsAboutNothing() {
        // A hand-written товарний чек has no printed identity, and that gap is real: it is better
        // than guessing from a label and an amount that two different papers are one.
        ProjectReceipt plain = objectReceipt("Без QR", null, "10:00");
        var twins = new ReceiptIdentityIndex.Twins(List.of(plain), List.of(), Map.of());

        assertThat(twins.forObject(plain)).isNull();
    }

    @Test
    void theWarningLandsOnThePaperFiledSecond() {
        ProjectReceipt first = objectReceipt("Епіцентр", "77", "10:00");
        ProjectReceipt second = objectReceipt("Епіцентр (копія)", "77", "10:05");
        var twins = new ReceiptIdentityIndex.Twins(List.of(first, second), List.of(), Map.of());

        assertThat(twins.forObject(first)).as("the original carries no warning").isNull();
        assertThat(twins.forObject(second))
                .isEqualTo(ReceiptDuplicateRef.object(first.getId(), "Епіцентр"));
    }

    /** The pair B-04 is named after — and the only one that could bill the client twice. */
    @Test
    void anObjectReceiptPointsAtItsTwinOnAnAct() {
        ProjectReceipt atTheTill = objectReceipt("Епіцентр", "77", "10:00");
        WorkActReceipt onTheAct = actReceipt("Цвяхи", "77", "7", "09:00");
        var twins = new ReceiptIdentityIndex.Twins(List.of(atTheTill), List.of(onTheAct), Map.of());

        assertThat(twins.forObject(atTheTill))
                .isEqualTo(ReceiptDuplicateRef.act(onTheAct.getId(), "Цвяхи", "7"));
        // …and the act side says it back, so whichever screen he is on names the other.
        assertThat(twins.forAct(onTheAct))
                .isEqualTo(ReceiptDuplicateRef.object(atTheTill.getId(), "Епіцентр"));
    }

    /**
     * Own table first, even when the other table's copy is older. The warning is something to act
     * on, and acting on it starts with finding the twin — in the list already on screen.
     */
    @Test
    void theSameTableBeatsTheOtherOne() {
        ProjectReceipt first = objectReceipt("Епіцентр", "77", "10:00");
        ProjectReceipt second = objectReceipt("Епіцентр (копія)", "77", "10:05");
        WorkActReceipt older = actReceipt("Цвяхи", "77", "7", "08:00");
        var twins = new ReceiptIdentityIndex.Twins(List.of(first, second), List.of(older), Map.of());

        assertThat(twins.forObject(second).kind()).isEqualTo(ReceiptKind.OBJECT);
        assertThat(twins.forObject(second).id()).isEqualTo(first.getId());
    }

    /**
     * A blank code is not an identity (review item B-21). Legacy rows were stored as {@code ""} and
     * every reader keyed them as the one string {@code "|"}, so every blank-identity receipt on the
     * object was flagged as the twin of every other one — across tables too.
     */
    @Test
    void aBlankFiscalCodeIsNotAnIdentity() {
        ProjectReceipt first = objectReceipt("Епіцентр", null, "10:00");
        first.setFiscalFn("");
        first.setFiscalId("");
        ProjectReceipt second = objectReceipt("Нова Лінія", null, "10:05");
        second.setFiscalFn("  ");
        second.setFiscalId("  ");
        WorkActReceipt onTheAct = actReceipt("Цвяхи", "77", "7", "09:00");
        onTheAct.setFiscalFn("");
        onTheAct.setFiscalId("");
        var twins = new ReceiptIdentityIndex.Twins(List.of(first, second), List.of(onTheAct), Map.of());

        assertThat(twins.forObject(second)).isNull();
        assertThat(twins.forAct(onTheAct)).isNull();
    }

    @Test
    void adifferentPaperIsNotATwin() {
        ProjectReceipt one = objectReceipt("Епіцентр", "77", "10:00");
        ProjectReceipt other = objectReceipt("Нова Лінія", "88", "10:05");
        var twins = new ReceiptIdentityIndex.Twins(List.of(one, other), List.of(), Map.of());

        assertThat(twins.forObject(other)).isNull();
    }

    /**
     * The act NUMBER beside «вже списано актом № 7» comes from the act rows already loaded whenever
     * it can. The extra lookup exists for the case those rows cannot answer — an act whose receipts
     * carry no identity of their own still billed the object's paper.
     */
    @Test
    void anActThatTheLoadedRowsCannotNameIsLookedUpOnce() {
        UUID actId = UUID.randomUUID();
        ProjectReceipt billed = objectReceipt("Епіцентр", null, "10:00");
        billed.setBilledOnActId(actId);
        when(projectReceipts.findIdentifiedByProjectId(PROJECT)).thenReturn(List.of());
        when(actReceipts.findIdentifiedByProjectId(PROJECT)).thenReturn(List.of());
        when(acts.findAllById(any()))
                .thenReturn(List.of(WorkAct.builder().id(actId).number("7").build()));

        var twins = index.forProject(PROJECT, List.of(billed));

        assertThat(twins.actNumber(actId)).isEqualTo("7");
        assertThat(twins.actNumber(null)).isNull();
    }

    @Test
    void anActAlreadyNamedByTheLoadedRowsIsNotLookedUpAgain() {
        WorkActReceipt onTheAct = actReceipt("Цвяхи", "77", "7", "09:00");
        UUID actId = onTheAct.getWorkAct().getId();
        ProjectReceipt billed = objectReceipt("Епіцентр", "77", "10:00");
        billed.setBilledOnActId(actId);
        when(projectReceipts.findIdentifiedByProjectId(PROJECT)).thenReturn(List.of(billed));
        when(actReceipts.findIdentifiedByProjectId(PROJECT)).thenReturn(List.of(onTheAct));

        var twins = index.forProject(PROJECT, List.of(billed));

        assertThat(twins.actNumber(actId)).isEqualTo("7");
        verify(acts, never()).findAllById(any());
    }

    // ---- fixtures ---------------------------------------------------------

    private static ProjectReceipt objectReceipt(String label, String fiscalId, String at) {
        ProjectReceipt r = ProjectReceipt.builder()
                .id(UUID.randomUUID()).projectId(PROJECT).label(label)
                .amount(new BigDecimal("483.50"))
                .build();
        if (fiscalId != null) {
            r.setFiscalFn(FN);
            r.setFiscalId(fiscalId);
        }
        r.setCreatedAt(Instant.parse("2026-09-08T" + at + ":00Z"));
        return r;
    }

    private static WorkActReceipt actReceipt(String label, String fiscalId, String actNumber, String at) {
        WorkActReceipt r = WorkActReceipt.builder()
                .id(UUID.randomUUID()).label(label).amount(new BigDecimal("483.50"))
                .workAct(WorkAct.builder()
                        .id(UUID.randomUUID()).number(actNumber)
                        .project(Project.builder().id(PROJECT).build())
                        .build())
                .fiscalFn(FN).fiscalId(fiscalId)
                .createdAt(Instant.parse("2026-09-08T" + at + ":00Z"))
                .build();
        return r;
    }
}
