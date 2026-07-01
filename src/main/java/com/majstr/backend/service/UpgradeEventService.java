package com.majstr.backend.service;

import com.majstr.backend.dto.UpgradeInterestResponse;
import com.majstr.backend.dto.UpgradeUserActivity;
import com.majstr.backend.entity.UpgradeEvent;
import com.majstr.backend.entity.UpgradeEventType;
import com.majstr.backend.repository.UpgradeEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Records PRO upgrade-intent events and aggregates them for the admin dashboard.
 * Every CLICK is stored (intensity + by-trigger); an INTEREST submission is a warm
 * lead. Reads are single aggregate queries (no per-user loop).
 */
@Service
@RequiredArgsConstructor
public class UpgradeEventService {

    private final UpgradeEventRepository repository;

    @Transactional
    public void recordClick(UUID userId, String trigger) {
        repository.save(UpgradeEvent.builder()
                .userId(userId)
                .type(UpgradeEventType.CLICK)
                .triggerSource(trigger != null && !trigger.isBlank() ? trigger.trim() : "OTHER")
                .build());
    }

    @Transactional
    public void recordInterest(UUID userId, String reason) {
        repository.save(UpgradeEvent.builder()
                .userId(userId)
                .type(UpgradeEventType.INTEREST)
                .reason(reason != null && !reason.isBlank() ? reason.trim() : null)
                .build());
    }

    @Transactional(readOnly = true)
    public UpgradeInterestResponse interestStats() {
        List<UpgradeInterestResponse.TriggerBreakdown> byTrigger = repository.countClicksByTrigger().stream()
                .map(t -> new UpgradeInterestResponse.TriggerBreakdown(
                        t.getTriggerSource() != null ? t.getTriggerSource() : "OTHER", t.getTotal()))
                .sorted(Comparator.comparingLong(UpgradeInterestResponse.TriggerBreakdown::count).reversed())
                .toList();
        return new UpgradeInterestResponse(
                repository.countDistinctUsersByType(UpgradeEventType.CLICK),
                repository.countByType(UpgradeEventType.CLICK),
                repository.countDistinctUsersByType(UpgradeEventType.INTEREST),
                byTrigger,
                repository.findInterestLeads());
    }

    @Transactional(readOnly = true)
    public UpgradeUserActivity userActivity(UUID userId) {
        long clicks = repository.countByUserIdAndType(userId, UpgradeEventType.CLICK);
        Instant lastClick = repository.findFirstByUserIdAndTypeOrderByCreatedAtDesc(userId, UpgradeEventType.CLICK)
                .map(UpgradeEvent::getCreatedAt).orElse(null);
        Optional<UpgradeEvent> interest =
                repository.findFirstByUserIdAndTypeOrderByCreatedAtDesc(userId, UpgradeEventType.INTEREST);
        return new UpgradeUserActivity(
                clicks,
                lastClick,
                interest.isPresent(),
                interest.map(UpgradeEvent::getReason).orElse(null),
                interest.map(UpgradeEvent::getCreatedAt).orElse(null));
    }
}
