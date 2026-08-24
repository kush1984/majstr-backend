package com.majstr.backend.service;

import com.majstr.backend.dto.AdminUserDetail;
import com.majstr.backend.dto.AdminUserSummary;
import com.majstr.backend.dto.UpgradeUserActivity;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.ClientRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.OwnerCount;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock UserRepository userRepository;
    @Mock ClientRepository clientRepository;
    @Mock ProjectRepository projectRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock EstimateShareLinkRepository shareLinkRepository;
    @Mock com.majstr.backend.repository.ProjectShareLinkRepository projectShareLinkRepository;
    @Mock CatalogItemRepository catalogRepository;
    @Mock com.majstr.backend.repository.PaymentRepository paymentRepository;
    @Mock com.majstr.backend.repository.ReferralRewardRepository referralRewardRepository;
    @Mock UpgradeEventService upgradeEventService;
    @InjectMocks AdminUserService adminUserService;

    @Test
    void search_foldsActivityCountsPerUser_missingRowsAreZero() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        User ua = user(a, "a@x", Plan.FREE, true);
        User ub = user(b, "b@x", Plan.PRO, false);
        Pageable pageable = PageRequest.of(0, 20);
        // activeSince is Instant.now() minus the service's own window — computed inside search(),
        // so the exact value here is unknown to the test; match on type instead. The 4-arg
        // search(...) overload delegates to the 6-arg repository method with registrationAscending=false.
        given(userRepository.searchAdmin(isNull(), isNull(), isNull(), any(Instant.class), eq(false), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(ua, ub), pageable, 2));
        given(clientRepository.countByOwnerIdIn(List.of(a, b))).willReturn(List.of(oc(a, 3)));
        given(projectRepository.countByOwnerIdIn(List.of(a, b))).willReturn(List.of(oc(a, 2), oc(b, 1)));
        given(estimateRepository.countByProjectOwnerIdIn(List.of(a, b))).willReturn(List.of(oc(a, 5), oc(b, 4)));
        given(estimateRepository.countByProjectOwnerIdInAndStatus(List.of(a, b), EstimateStatus.SIGNED))
                .willReturn(List.of(oc(a, 1)));

        Page<AdminUserSummary> page = adminUserService.search(null, null, null, pageable);

        AdminUserSummary sa = page.getContent().get(0);
        assertThat(sa.id()).isEqualTo(a);
        assertThat(sa.emailVerified()).isTrue();
        assertThat(sa.clientsCount()).isEqualTo(3);
        assertThat(sa.projectsCount()).isEqualTo(2);
        assertThat(sa.estimatesCount()).isEqualTo(5);
        assertThat(sa.signedEstimatesCount()).isEqualTo(1);

        AdminUserSummary sb = page.getContent().get(1);
        assertThat(sb.emailVerified()).isFalse();
        assertThat(sb.clientsCount()).isZero();          // absent in clients result → 0
        assertThat(sb.projectsCount()).isEqualTo(1);
        assertThat(sb.estimatesCount()).isEqualTo(4);
        assertThat(sb.signedEstimatesCount()).isZero();  // absent in signed result → 0
    }

    @Test
    void search_registrationAscending_isPassedThroughToTheRepository() {
        // Two-level sort: active-right-now users always stay pinned first (see
        // UserRepository#searchAdminByPatternRegistrationAscending's own ORDER BY) — this only
        // checks that the admin panel's «Реєстрація» toggle actually reaches the repository call,
        // the ordering itself is a repository/SQL concern, not this service's.
        Pageable pageable = PageRequest.of(0, 20);
        given(userRepository.searchAdmin(isNull(), isNull(), isNull(), any(Instant.class), eq(true), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        adminUserService.search(null, null, null, true, pageable);

        verify(userRepository).searchAdmin(isNull(), isNull(), isNull(), any(Instant.class), eq(true), eq(pageable));
    }

    @Test
    void detail_buildsFunnelFromCountsAndStatusBreakdown() {
        UUID id = UUID.randomUUID();
        User u = user(id, "a@x", Plan.FREE, true);
        u.setLogoUrl("logos/x.png");
        given(userRepository.findWithTradesById(id)).willReturn(Optional.of(u));
        given(estimateRepository.countByStatusForOwner(id)).willReturn(List.of(
                new Object[]{EstimateStatus.DRAFT, 2L},
                new Object[]{EstimateStatus.SIGNED, 1L}
        ));
        given(clientRepository.countByOwnerId(id)).willReturn(4L);
        given(projectRepository.countByOwnerId(id)).willReturn(3L);
        given(shareLinkRepository.countByOwner(id)).willReturn(1L);
        given(catalogRepository.countByOwnerId(id)).willReturn(42L);
        given(estimateRepository.findLastEstimateCreatedAt(id)).willReturn(Instant.now());
        given(upgradeEventService.userActivity(id))
                .willReturn(new UpgradeUserActivity(2, Instant.now(), true, "треба PRO", Instant.now()));

        AdminUserDetail d = adminUserService.detail(id);

        // How to reach this master. The screen identified them by email alone, which is the one
        // detail you cannot ring when a paying customer has a problem.
        assertThat(d.fullName()).isEqualTo("Name");
        assertThat(d.phone()).isEqualTo("+380671234567");
        assertThat(d.companyName()).isEqualTo("Co");

        assertThat(d.clientsCount()).isEqualTo(4);
        assertThat(d.projectsCount()).isEqualTo(3);
        assertThat(d.estimates().total()).isEqualTo(3); // 2 draft + 1 signed
        assertThat(d.estimates().draft()).isEqualTo(2);
        assertThat(d.estimates().sent()).isZero();
        assertThat(d.estimates().signed()).isEqualTo(1);
        assertThat(d.estimates().rejected()).isZero();
        assertThat(d.hasSigned()).isTrue();
        assertThat(d.hasShareLink()).isTrue();
        assertThat(d.catalogItemsCount()).isEqualTo(42);
        assertThat(d.hasLogo()).isTrue();
        assertThat(d.lastEstimateCreatedAt()).isNotNull();
    }

    // The card used to read only EstimateShareLink, exactly like the funnel step did. A master who
    // shares from the object (the main flow since the portal iteration) showed «Поділився хоч раз:
    // ні» while the fixed funnel counted him — two numbers on one admin page contradicting each other.
    @Test
    void detail_countsAMasterWhoOnlyEverSharedFromTheObject() {
        UUID id = UUID.randomUUID();
        given(userRepository.findWithTradesById(id)).willReturn(Optional.of(user(id, "a@x", Plan.FREE, true)));
        given(estimateRepository.countByStatusForOwner(id)).willReturn(List.of());
        given(shareLinkRepository.countByOwner(id)).willReturn(0L);
        given(projectShareLinkRepository.existsByProjectOwnerIdAndKindIn(id, ShareLinkKind.SHARED_WITH_CLIENT))
                .willReturn(true);
        given(upgradeEventService.userActivity(id))
                .willReturn(new UpgradeUserActivity(0, null, false, null, null));

        assertThat(adminUserService.detail(id).hasShareLink()).isTrue();
    }

    private static User user(UUID id, String email, Plan plan, boolean verified) {
        return User.builder()
                .id(id)
                .email(email)
                .fullName("Name")
                .phone("+380671234567")
                .companyName("Co")
                .trades(new LinkedHashSet<>(Set.of(Trade.BUILDER)))
                .plan(plan)
                .role(Role.USER)
                .emailVerified(verified)
                .build();
    }

    private static OwnerCount oc(UUID id, long count) {
        return new OwnerCount() {
            @Override public UUID getOwnerId() { return id; }
            @Override public long getCnt() { return count; }
        };
    }
}
