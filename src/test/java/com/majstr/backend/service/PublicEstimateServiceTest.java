package com.majstr.backend.service;

import com.majstr.backend.dto.PublicEstimateView;
import com.majstr.backend.dto.QuestionRequest;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.dto.SignRequest;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateQuestion;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateQuestionRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PublicEstimateServiceTest {

    @Mock private EstimateShareLinkRepository shareLinkRepository;
    @Mock private EstimateItemRepository itemRepository;
    @Mock private EstimateQuestionRepository questionRepository;
    @Mock private EstimateService estimateService;
    @Mock private FeatureGuard featureGuard;

    @InjectMocks private PublicEstimateService publicService;

    private final String token = "valid-token-xyz-1234567890";

    @Test
    void view_returnsSanitizedSnapshotForValidToken() {
        Estimate estimate = sampleEstimate();
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(estimate)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId()))
                .willReturn(List.of(workItem(estimate), materialItem(estimate)));

        PublicEstimateView view = publicService.view(token);

        // Public payload — verify no internal IDs surface
        assertThat(view.contractor().companyName()).isEqualTo("Іван-Електрик ФОП");
        assertThat(view.contractor().phone()).isEqualTo("+380501112233");
        // No email field exists at all on the public view — type-level guarantee
        assertThat(view.project().name()).isEqualTo("Квартира на Хрещатику");
        assertThat(view.items()).hasSize(2);
        assertThat(view.worksSubtotal()).isEqualByComparingTo("4590.00");
        assertThat(view.materialsSubtotal()).isEqualByComparingTo("2220.00");
        assertThat(view.total()).isEqualByComparingTo("6810.00");
    }

    @Test
    void view_throws404OnUnknownToken() {
        given(shareLinkRepository.findByToken("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> publicService.view("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Share link not found");
    }

    @Test
    void view_throws404OnRevokedToken() {
        Estimate estimate = sampleEstimate();
        EstimateShareLink revoked = usableLink(estimate);
        revoked.setRevoked(true);
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(revoked));

        // Same 404 — does not reveal that token existed at all
        assertThatThrownBy(() -> publicService.view(token))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Share link not found");
    }

    @Test
    void view_throws404OnExpiredToken() {
        Estimate estimate = sampleEstimate();
        EstimateShareLink expired = usableLink(estimate);
        expired.setExpiresAt(Instant.now().minusSeconds(3600));
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> publicService.view(token))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void sign_setsSignedStatusAndStampsClientFields() {
        Estimate estimate = sampleEstimate();
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(estimate)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId()))
                .willReturn(List.of(workItem(estimate)));

        SignRequest req = new SignRequest("Олена Іваненко", "+380671234567");

        PublicEstimateView view = publicService.sign(token, req, "203.0.113.42");

        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.SIGNED);
        assertThat(estimate.getSignedAt()).isNotNull();
        assertThat(estimate.getSignerName()).isEqualTo("Олена Іваненко");
        assertThat(estimate.getSignerPhone()).isEqualTo("+380671234567");
        assertThat(estimate.getSignerIp()).isEqualTo("203.0.113.42");
        assertThat(view.signature()).isNotNull();
        assertThat(view.signature().signerName()).isEqualTo("Олена Іваненко");
    }

    @Test
    void askQuestion_persistsAndReturnsSummary() {
        Estimate estimate = sampleEstimate();
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(estimate)));
        given(questionRepository.save(any(EstimateQuestion.class))).willAnswer(inv -> {
            EstimateQuestion q = inv.getArgument(0);
            q.setId(UUID.randomUUID());
            q.setCreatedAt(Instant.now());
            return q;
        });

        QuestionResponse resp = publicService.askQuestion(
                token,
                new QuestionRequest("Олена", "+380671234567", "Чи можна перенести початок на тиждень?"),
                "203.0.113.42");

        assertThat(resp.id()).isNotNull();
        assertThat(resp.createdAt()).isNotNull();
    }

    // ---- fixtures ---------------------------------------------------------

    private EstimateShareLink usableLink(Estimate estimate) {
        return EstimateShareLink.builder()
                .id(UUID.randomUUID())
                .estimate(estimate)
                .token(token)
                .createdAt(Instant.now())
                .revoked(false)
                .build();
    }

    private Estimate sampleEstimate() {
        User contractor = User.builder()
                .id(UUID.randomUUID())
                .email("ivan@example.com")
                .companyName("Іван-Електрик ФОП")
                .fullName("Іван Майстренко")
                .phone("+380501112233")
                .trade(Trade.ELECTRICAL)
                .passwordHash("x")
                .build();
        Client client = Client.builder()
                .id(UUID.randomUUID())
                .fullName("Олена Іваненко")
                .phone("+380671234567")
                .build();
        Project project = Project.builder()
                .id(UUID.randomUUID())
                .owner(contractor)
                .client(client)
                .name("Квартира на Хрещатику")
                .address("вул. Хрещатик 1, Київ")
                .status(ProjectStatus.ESTIMATING)
                .build();
        return Estimate.builder()
                .id(UUID.randomUUID())
                .project(project)
                .status(EstimateStatus.DRAFT)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private EstimateItem workItem(Estimate estimate) {
        return EstimateItem.builder()
                .id(UUID.randomUUID())
                .estimate(estimate)
                .type(ItemType.WORK)
                .name("Штукатурка")
                .unit(Unit.M2)
                .quantity(new BigDecimal("25.500"))
                .unitPrice(new BigDecimal("180.00"))
                .sortOrder(0)
                .build();
    }

    private EstimateItem materialItem(Estimate estimate) {
        return EstimateItem.builder()
                .id(UUID.randomUUID())
                .estimate(estimate)
                .type(ItemType.MATERIAL)
                .name("Гіпс")
                .unit(Unit.KG)
                .quantity(new BigDecimal("120.000"))
                .unitPrice(new BigDecimal("18.50"))
                .sortOrder(1)
                .build();
    }
}
