package com.majstr.backend.service;

import com.majstr.backend.dto.AdminUserDetail;
import com.majstr.backend.dto.AdminUserSummary;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.ClientRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.OwnerCount;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin activity read-models: the user list with per-user activity counts
 * (folded in with one grouped query per entity over the page — no N+1) and the
 * full per-master detail funnel. ADMIN-only via the controller / security config.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateShareLinkRepository shareLinkRepository;
    private final CatalogItemRepository catalogRepository;

    @Transactional(readOnly = true)
    public Page<AdminUserSummary> search(Plan plan, String search, Pageable pageable) {
        Page<User> page = userRepository.searchAdmin(plan, search, pageable);
        List<UUID> ids = page.getContent().stream().map(User::getId).toList();
        if (ids.isEmpty()) {
            return page.map(AdminUserSummary::from);
        }
        // One grouped query per entity for the whole page (not per user).
        Map<UUID, Long> clients = toMap(clientRepository.countByOwnerIdIn(ids));
        Map<UUID, Long> projects = toMap(projectRepository.countByOwnerIdIn(ids));
        Map<UUID, Long> estimates = toMap(estimateRepository.countByProjectOwnerIdIn(ids));
        Map<UUID, Long> signed = toMap(
                estimateRepository.countByProjectOwnerIdInAndStatus(ids, EstimateStatus.SIGNED));
        return page.map(u -> AdminUserSummary.of(
                u,
                clients.getOrDefault(u.getId(), 0L),
                projects.getOrDefault(u.getId(), 0L),
                estimates.getOrDefault(u.getId(), 0L),
                signed.getOrDefault(u.getId(), 0L)
        ));
    }

    @Transactional(readOnly = true)
    public AdminUserDetail detail(UUID userId) {
        // Eager-fetch trades (open-in-view off) — same guard as the rest of the app.
        User user = userRepository.findWithTradesById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Map<EstimateStatus, Long> byStatus = new EnumMap<>(EstimateStatus.class);
        for (Object[] row : estimateRepository.countByStatusForOwner(userId)) {
            byStatus.put((EstimateStatus) row[0], (Long) row[1]);
        }
        long draft = byStatus.getOrDefault(EstimateStatus.DRAFT, 0L);
        long sent = byStatus.getOrDefault(EstimateStatus.SENT, 0L);
        long signed = byStatus.getOrDefault(EstimateStatus.SIGNED, 0L);
        long rejected = byStatus.getOrDefault(EstimateStatus.REJECTED, 0L);
        AdminUserDetail.EstimateBreakdown breakdown = new AdminUserDetail.EstimateBreakdown(
                draft + sent + signed + rejected, draft, sent, signed, rejected);

        boolean hasLogo = user.getLogoUrl() != null && !user.getLogoUrl().isBlank();

        return new AdminUserDetail(
                user.getId(),
                user.getEmail(),
                user.isEmailVerified(),
                new LinkedHashSet<>(user.getTrades()),
                user.getPlan(),
                user.getRole(),
                user.getCreatedAt(),
                user.getLastActiveAt(),
                clientRepository.countByOwnerId(userId),
                projectRepository.countByOwnerId(userId),
                breakdown,
                shareLinkRepository.countByOwner(userId) > 0,
                signed > 0,
                catalogRepository.countByOwnerId(userId),
                hasLogo,
                estimateRepository.findLastEstimateCreatedAt(userId)
        );
    }

    private static Map<UUID, Long> toMap(List<OwnerCount> rows) {
        Map<UUID, Long> map = new HashMap<>();
        for (OwnerCount r : rows) {
            map.put(r.getOwnerId(), r.getCnt());
        }
        return map;
    }
}
