package com.majstr.backend.service.broadcast;

import com.majstr.backend.entity.BroadcastSegment;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.OwnerCount;
import com.majstr.backend.repository.OwnerEstimateFill;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads the funnel facts for every broadcast-eligible master and sorts them into segments.
 * Three aggregate queries for the whole audience — never a loop of per-user lookups.
 */
@Service
@RequiredArgsConstructor
public class BroadcastAudience {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;

    @Transactional(readOnly = true)
    public Map<BroadcastSegment, List<AudienceRules.Master>> bySegment(Instant now) {
        List<User> users = userRepository.findBroadcastAudience();
        Map<BroadcastSegment, List<AudienceRules.Master>> out = new EnumMap<>(BroadcastSegment.class);
        for (BroadcastSegment s : BroadcastSegment.values()) {
            out.put(s, new ArrayList<>());
        }
        if (users.isEmpty()) {
            return out;
        }
        List<UUID> ids = users.stream().map(User::getId).toList();
        Map<UUID, Long> objects = projectRepository.countByOwnerIdIn(ids).stream()
                .collect(Collectors.toMap(OwnerCount::getOwnerId, OwnerCount::getCnt));
        Map<UUID, OwnerEstimateFill> fill = estimateRepository.findEstimateFillByOwnerIdIn(ids).stream()
                .collect(Collectors.toMap(OwnerEstimateFill::getOwnerId, Function.identity()));

        for (User u : users) {
            OwnerEstimateFill f = fill.get(u.getId());
            AudienceRules.Master m = new AudienceRules.Master(
                    u.getId(), u.getFullName(), u.getEmail(), u.isEmailVerified(),
                    u.getCreatedAt(), u.getLastActiveAt(),
                    objects.getOrDefault(u.getId(), 0L),
                    f == null ? 0 : f.getEstimates(),
                    f == null ? 0 : f.getMaxLines());
            AudienceRules.segmentOf(m, now).ifPresent(s -> out.get(s).add(m));
        }
        out.values().forEach(list -> list.sort(Comparator.comparing(AudienceRules.Master::registeredAt).reversed()));
        return out;
    }

    @Transactional(readOnly = true)
    public List<AudienceRules.Master> members(BroadcastSegment segment, Instant now) {
        return bySegment(now).get(segment);
    }
}
