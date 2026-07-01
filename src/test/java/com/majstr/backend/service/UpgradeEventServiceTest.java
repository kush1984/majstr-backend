package com.majstr.backend.service;

import com.majstr.backend.dto.UpgradeInterestResponse;
import com.majstr.backend.dto.UpgradeLead;
import com.majstr.backend.dto.UpgradeUserActivity;
import com.majstr.backend.entity.UpgradeEvent;
import com.majstr.backend.entity.UpgradeEventType;
import com.majstr.backend.repository.UpgradeEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UpgradeEventServiceTest {

    @Mock UpgradeEventRepository repository;
    @InjectMocks UpgradeEventService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void recordClick_savesClickWithTrigger() {
        service.recordClick(userId, "OBJECT_LIMIT");

        ArgumentCaptor<UpgradeEvent> captor = ArgumentCaptor.forClass(UpgradeEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(UpgradeEventType.CLICK);
        assertThat(captor.getValue().getTriggerSource()).isEqualTo("OBJECT_LIMIT");
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void recordClick_blankTrigger_defaultsToOther() {
        service.recordClick(userId, "  ");

        ArgumentCaptor<UpgradeEvent> captor = ArgumentCaptor.forClass(UpgradeEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTriggerSource()).isEqualTo("OTHER");
    }

    @Test
    void recordInterest_savesInterestWithReason() {
        service.recordInterest(userId, " треба експорт у Excel ");

        ArgumentCaptor<UpgradeEvent> captor = ArgumentCaptor.forClass(UpgradeEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(UpgradeEventType.INTEREST);
        assertThat(captor.getValue().getReason()).isEqualTo("треба експорт у Excel"); // trimmed
    }

    @Test
    void interestStats_aggregatesAndSortsByTriggerDesc() {
        given(repository.countDistinctUsersByType(UpgradeEventType.CLICK)).willReturn(5L);
        given(repository.countByType(UpgradeEventType.CLICK)).willReturn(12L);
        given(repository.countDistinctUsersByType(UpgradeEventType.INTEREST)).willReturn(3L);
        given(repository.countClicksByTrigger()).willReturn(List.of(
                triggerCount("PROFILE", 4), triggerCount("OBJECT_LIMIT", 8)));
        UpgradeLead lead = new UpgradeLead(userId, "m@e.com", "Іван", "хочу PDF без лого", Instant.now());
        given(repository.findInterestLeads()).willReturn(List.of(lead));

        UpgradeInterestResponse resp = service.interestStats();

        assertThat(resp.uniqueClickers()).isEqualTo(5);
        assertThat(resp.totalClicks()).isEqualTo(12);
        assertThat(resp.interested()).isEqualTo(3);
        // Sorted by count desc.
        assertThat(resp.byTrigger()).extracting(UpgradeInterestResponse.TriggerBreakdown::trigger)
                .containsExactly("OBJECT_LIMIT", "PROFILE");
        assertThat(resp.leads()).containsExactly(lead);
    }

    @Test
    void userActivity_reflectsClicksAndInterest() {
        Instant clickAt = Instant.parse("2026-06-30T10:00:00Z");
        UpgradeEvent interest = UpgradeEvent.builder()
                .type(UpgradeEventType.INTEREST).reason("треба TEAM").createdAt(clickAt).build();
        given(repository.countByUserIdAndType(userId, UpgradeEventType.CLICK)).willReturn(3L);
        given(repository.findFirstByUserIdAndTypeOrderByCreatedAtDesc(userId, UpgradeEventType.CLICK))
                .willReturn(Optional.of(UpgradeEvent.builder().createdAt(clickAt).build()));
        given(repository.findFirstByUserIdAndTypeOrderByCreatedAtDesc(userId, UpgradeEventType.INTEREST))
                .willReturn(Optional.of(interest));

        UpgradeUserActivity a = service.userActivity(userId);

        assertThat(a.clicks()).isEqualTo(3);
        assertThat(a.lastClickAt()).isEqualTo(clickAt);
        assertThat(a.interested()).isTrue();
        assertThat(a.interestReason()).isEqualTo("треба TEAM");
    }

    private static UpgradeEventRepository.TriggerCount triggerCount(String trigger, long total) {
        return new UpgradeEventRepository.TriggerCount() {
            public String getTriggerSource() { return trigger; }
            public long getTotal() { return total; }
        };
    }
}
