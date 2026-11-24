package com.majstr.backend.service;

import com.majstr.backend.dto.ReopenBlock;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** What the app is told before the tap (owner, 2026-11-24): the strongest act in the way, named. */
@ExtendWith(MockitoExtension.class)
class ReopenGuardTest {

    @Mock WorkActItemRepository workActItemRepository;
    @Mock WorkActRepository workActRepository;
    @InjectMocks ReopenGuard guard;

    private final UUID id = UUID.randomUUID();

    private Estimate estimate(EstimateStatus status, EstimateKind kind) {
        Estimate e = new Estimate();
        e.setId(id);
        e.setStatus(status);
        e.setKind(kind);
        return e;
    }

    private void acts(Object[]... rows) {
        given(workActItemRepository.findLiveActsOnEstimate(id)).willReturn(List.of(rows));
    }

    @Test
    void aDraftOrANotSignedEstimateCarriesNoBlock() {
        assertThat(guard.blockOf(estimate(EstimateStatus.DRAFT, EstimateKind.REGULAR))).isNull();
    }

    @Test
    void nothingInTheWay_noBlock() {
        acts();
        assertThat(guard.blockOf(estimate(EstimateStatus.SIGNED, EstimateKind.REGULAR))).isNull();
    }

    @Test
    void onlyDrafts_namesThemAll() {
        acts(new Object[] {"4", WorkActStatus.DRAFT}, new Object[] {"2", WorkActStatus.DRAFT});
        assertThat(guard.blockOf(estimate(EstimateStatus.SIGNED, EstimateKind.REGULAR)))
                .isEqualTo(new ReopenBlock(ReopenBlock.Reason.DRAFT_ACT, List.of("2", "4")));
    }

    @Test
    void aSentActBesideADraft_isASentBlockNamingBoth() {
        acts(new Object[] {"3", WorkActStatus.SENT}, new Object[] {"4", WorkActStatus.DRAFT});
        assertThat(guard.blockOf(estimate(EstimateStatus.SIGNED, EstimateKind.REGULAR)))
                .isEqualTo(new ReopenBlock(ReopenBlock.Reason.SENT_ACT, List.of("3", "4")));
    }

    @Test
    void aSignedActWins_andOnlyTheSignedOnesAreNamed() {
        acts(new Object[] {"1", WorkActStatus.SIGNED}, new Object[] {"2", WorkActStatus.SENT});
        assertThat(guard.blockOf(estimate(EstimateStatus.SIGNED, EstimateKind.REGULAR)))
                .isEqualTo(new ReopenBlock(ReopenBlock.Reason.SIGNED_ACT, List.of("1")));
    }

    @Test
    void anAddendumIsNeverReopened() {
        assertThat(guard.blockOf(estimate(EstimateStatus.SIGNED, EstimateKind.ADDENDUM)))
                .isEqualTo(new ReopenBlock(ReopenBlock.Reason.ADDENDUM, List.of()));
    }
}
