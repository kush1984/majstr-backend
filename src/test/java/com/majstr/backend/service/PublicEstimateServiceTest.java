package com.majstr.backend.service;

import com.majstr.backend.dto.PublicEstimateView;
import com.majstr.backend.dto.PublicPortalView;
import com.majstr.backend.dto.QuestionRequest;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.dto.SignRequest;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.ProjectMessageRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectPaymentRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PublicEstimateServiceTest {

    @Mock private EstimateShareLinkRepository shareLinkRepository;
    @Mock private ProjectShareLinkRepository projectShareLinkRepository;
    @Mock private ProjectPaymentRepository projectPaymentRepository;
    @Mock private PaymentReceiptRepository paymentReceiptRepository;
    @Mock private EstimateRepository estimateRepository;
    @Mock private EstimateItemRepository itemRepository;
    @Mock private ProjectMessageRepository messageRepository;
    @Mock private EstimateService estimateService;
    @Mock private ProjectPhotoService projectPhotoService;
    @Mock private FeatureGuard featureGuard;
    @Mock private PushService pushService;

    private PublicEstimateService publicService;

    private final String token = "valid-token-xyz-1234567890";

    @BeforeEach
    void setUp() {
        // Real bundle (not a mock) so push texts come out as the contractor sees them.
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        publicService = new PublicEstimateService(shareLinkRepository, projectShareLinkRepository,
                projectPaymentRepository, paymentReceiptRepository, estimateRepository, itemRepository,
                messageRepository, estimateService, projectPhotoService, featureGuard, pushService, messages);
    }

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
        // The client page groups by this, so losing it from the public view would silently flatten
        // the portal back into one long list while every test still passed.
        assertThat(view.items().getFirst().category()).isEqualTo("Штукатурка стін");
        assertThat(view.items().getFirst().sortOrder()).isZero();
    }

    @Test
    void view_namesAPercentWhenExactlyOneLiveTotalLineExplainsIt() {
        // Mirrors TypeBreakdown's own rule (EstimateEditorPage.tsx): a % is shown only when a single
        // live TOTAL-kind line accounts for the whole markup/discount figure.
        Estimate estimate = sampleEstimate();
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(estimate)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId())).willReturn(List.of(
                workItem(estimate),
                totalPercentItem(estimate, "12.00", "550.80"),
                totalPercentItem(estimate, "-5.00", "-229.50")));

        PublicEstimateView view = publicService.view(token);

        assertThat(view.markupAmount()).isEqualByComparingTo("550.80");
        assertThat(view.markupPercent()).isEqualByComparingTo("12.00");
        assertThat(view.discountAmount()).isEqualByComparingTo("-229.50");
        assertThat(view.discountPercent()).isEqualByComparingTo("5.00");
    }

    @Test
    void view_fallsBackToSumOnlyWhenSeveralLinesShareTheSameDirection() {
        // Two markup lines: no single % explains the total, so the % must not be fabricated.
        Estimate estimate = sampleEstimate();
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(estimate)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId())).willReturn(List.of(
                workItem(estimate),
                totalPercentItem(estimate, "10.00", "459.00"),
                totalPercentItem(estimate, "2.00", "91.80")));

        PublicEstimateView view = publicService.view(token);

        assertThat(view.markupAmount()).isEqualByComparingTo("550.80");
        assertThat(view.markupPercent()).isNull();
    }

    @Test
    void view_fallsBackToSumOnlyWhenAFrozenLineSharesTheDirection() {
        // A frozen (carried-over) percent line has no "live" quantity worth showing as a %, so its
        // presence in a bucket blocks naming a single percent for that bucket, same as several lines.
        Estimate estimate = sampleEstimate();
        EstimateItem frozenMarkup = totalPercentItem(estimate, "8.00", "367.20");
        frozenMarkup.setBaseOriginLabel("10% від робіт · кошторис «Економ»");
        frozenMarkup.setPercentBaseKind(null);
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(estimate)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId())).willReturn(List.of(
                workItem(estimate),
                totalPercentItem(estimate, "12.00", "550.80"),
                frozenMarkup));

        PublicEstimateView view = publicService.view(token);

        assertThat(view.markupAmount()).isEqualByComparingTo("918.00");
        assertThat(view.markupPercent()).isNull();
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
        // Signing activates the project so it counts in "active projects".
        assertThat(estimate.getProject().getStatus()).isEqualTo(ProjectStatus.IN_PROGRESS);
        // Economy counting is default-on (not sign-triggered) and stays on through sign.
        assertThat(estimate.isCountInEconomy()).isTrue();
        assertThat(view.signature()).isNotNull();
        assertThat(view.signature().signerName()).isEqualTo("Олена Іваненко");
        // Contractor is pushed a real-time signing notification.
        verify(pushService).sendToUser(
                eq(estimate.getProject().getOwner()),
                contains("підписав(ла) кошторис"),
                eq("Квартира на Хрещатику"),
                contains("/projects/"));
    }

    @Test
    void sign_rejectsAlreadySignedEstimateWith409Semantics() {
        Estimate estimate = sampleEstimate();
        estimate.setStatus(EstimateStatus.SIGNED);
        estimate.setSignedAt(Instant.now());
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(estimate)));

        assertThatThrownBy(() -> publicService.sign(
                token, new SignRequest("Друга Особа", "+380670000000"), "203.0.113.43"))
                .isInstanceOf(EstimateSignedException.class);
        // The original signature is untouched.
        assertThat(estimate.getSignerName()).isNull();
    }

    @Test
    void sign_doesNotOverrideAlreadyCompletedProject() {
        Estimate estimate = sampleEstimate();
        estimate.getProject().setStatus(ProjectStatus.COMPLETED);
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(estimate)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId()))
                .willReturn(List.of());

        publicService.sign(token, new SignRequest("Олена", "+380671234567"), "203.0.113.42");

        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.SIGNED);
        assertThat(estimate.getProject().getStatus()).isEqualTo(ProjectStatus.COMPLETED);
    }

    @Test
    void sign_ofADuplicate_autoReopensAStillSignedParent() {
        // Economy-rework: A was signed first, the master duplicated it with a discount → B, and
        // now the client signs B too. A must auto-reopen to DRAFT and be stamped with which
        // duplicate superseded it — the whole "negative difference" workaround this replaces.
        Estimate parent = sampleEstimate();
        parent.setStatus(EstimateStatus.SIGNED);
        parent.setSignedAt(Instant.now());
        Estimate duplicate = Estimate.builder()
                .id(UUID.randomUUID())
                .project(parent.getProject())
                .status(EstimateStatus.DRAFT)
                .duplicatedFromId(parent.getId())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(duplicate)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(duplicate.getId()))
                .willReturn(List.of(workItem(duplicate)));
        given(estimateRepository.findById(parent.getId())).willReturn(Optional.of(parent));

        publicService.sign(token, new SignRequest("Олена Іваненко", "+380671234567"), "203.0.113.42");

        assertThat(duplicate.getStatus()).isEqualTo(EstimateStatus.SIGNED);
        verify(estimateService).applyReopen(parent, null); // system call, no owner attribution
        assertThat(parent.getSupersededByEstimateId()).isEqualTo(duplicate.getId());
    }

    @Test
    void sign_ofADuplicateWhoseParentIsStillDraft_leavesTheParentAlone() {
        // The auto-reopen only fires when the parent was ITSELF a live signed deal — an ordinary
        // "duplicate a draft, sign the copy" flow (the everyday markup/бригадир case) must not
        // touch the still-DRAFT parent at all.
        Estimate parent = sampleEstimate(); // DRAFT by default
        Estimate duplicate = Estimate.builder()
                .id(UUID.randomUUID())
                .project(parent.getProject())
                .status(EstimateStatus.DRAFT)
                .duplicatedFromId(parent.getId())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(duplicate)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(duplicate.getId()))
                .willReturn(List.of(workItem(duplicate)));
        given(estimateRepository.findById(parent.getId())).willReturn(Optional.of(parent));

        publicService.sign(token, new SignRequest("Олена Іваненко", "+380671234567"), "203.0.113.42");

        verify(estimateService, never()).applyReopen(any(), any());
        assertThat(parent.getSupersededByEstimateId()).isNull();
    }

    @Test
    void askQuestion_persistsAndReturnsSummary() {
        Estimate estimate = sampleEstimate();
        given(shareLinkRepository.findByToken(token)).willReturn(Optional.of(usableLink(estimate)));
        given(messageRepository.save(any(ProjectMessage.class))).willAnswer(inv -> {
            ProjectMessage q = inv.getArgument(0);
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
        // Contractor is pushed a real-time question notification.
        verify(pushService).sendToUser(
                eq(estimate.getProject().getOwner()),
                eq("Нове питання від клієнта"),
                contains("перенести"),
                contains("/projects/"));
    }

    // ---- project portal (?p=) --------------------------------------------

    @Test
    void viewPortal_rendersOneSectionPerVisibleEstimate() {
        Estimate first = sampleEstimate();
        first.setName("Економ");
        Estimate second = Estimate.builder()
                .id(UUID.randomUUID())
                .project(first.getProject())
                .name("Преміум")
                .status(EstimateStatus.SENT)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        given(projectShareLinkRepository.findByTokenAndKind(token, ShareLinkKind.PORTAL))
                .willReturn(Optional.of(usablePortalLink(first.getProject())));
        given(estimateRepository.findByProjectIdAndPortalVisibleTrueOrderByCreatedAtAsc(first.getProject().getId()))
                .willReturn(List.of(first, second));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(first.getId()))
                .willReturn(List.of(workItem(first)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(second.getId()))
                .willReturn(List.of(materialItem(second)));

        PublicPortalView view = publicService.viewPortal(token);

        assertThat(view.estimates()).hasSize(2);
        assertThat(view.estimates().get(0).name()).isEqualTo("Економ");
        assertThat(view.estimates().get(0).total()).isEqualByComparingTo("4590.00");
        assertThat(view.estimates().get(1).name()).isEqualTo("Преміум");
        assertThat(view.estimates().get(1).total()).isEqualByComparingTo("2220.00");
        assertThat(view.contractor().companyName()).isEqualTo("Іван-Електрик ФОП");
        assertThat(view.payments()).isNull(); // toggle off by default
    }

    @Test
    void viewPortal_paymentsVisible_cardSumsOnlySharedEstimates_neverAllOfTheMasters() {
        // Isolation: the object may have OTHER (private, unshared) counted estimates — the
        // portal's contractedTotal must sum only what THIS client is actually shown.
        Estimate shared = sampleEstimate();
        shared.setName("Економ");
        ProjectShareLink link = ProjectShareLink.builder()
                .id(UUID.randomUUID()).project(shared.getProject()).token(token)
                .createdAt(Instant.now()).revoked(false).paymentsVisible(true).build();
        given(projectShareLinkRepository.findByTokenAndKind(token, ShareLinkKind.PORTAL))
                .willReturn(Optional.of(link));
        given(estimateRepository.findByProjectIdAndPortalVisibleTrueOrderByCreatedAtAsc(shared.getProject().getId()))
                .willReturn(List.of(shared));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(shared.getId()))
                .willReturn(List.of(workItem(shared)));
        given(projectPaymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(shared.getProject().getId()))
                .willReturn(List.of());
        given(paymentReceiptRepository.sumByProjectId(shared.getProject().getId()))
                .willReturn(new BigDecimal("1000.00"));

        PublicPortalView view = publicService.viewPortal(token);

        assertThat(view.payments()).isNotNull();
        assertThat(view.payments().contractedTotal()).isEqualByComparingTo("4590.00"); // shared estimate only
        assertThat(view.payments().received()).isEqualByComparingTo("1000.00");
        assertThat(view.payments().remaining()).isEqualByComparingTo("3590.00");
        assertThat(view.payments().unplannedReceipts()).isEmpty();
    }

    @Test
    void viewPortal_anUnplannedReceipt_appearsAsItsOwnLineItem() {
        // Regression: `received` already includes unplanned receipts (no matching plan stage),
        // but without their own row the client sees a total the itemized rows don't add up to.
        Estimate shared = sampleEstimate();
        shared.setName("Економ");
        ProjectShareLink link = ProjectShareLink.builder()
                .id(UUID.randomUUID()).project(shared.getProject()).token(token)
                .createdAt(Instant.now()).revoked(false).paymentsVisible(true).build();
        given(projectShareLinkRepository.findByTokenAndKind(token, ShareLinkKind.PORTAL))
                .willReturn(Optional.of(link));
        given(estimateRepository.findByProjectIdAndPortalVisibleTrueOrderByCreatedAtAsc(shared.getProject().getId()))
                .willReturn(List.of(shared));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(shared.getId()))
                .willReturn(List.of(workItem(shared)));
        given(projectPaymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(shared.getProject().getId()))
                .willReturn(List.of());
        given(paymentReceiptRepository.sumByProjectId(shared.getProject().getId()))
                .willReturn(new BigDecimal("2700.00"));
        given(paymentReceiptRepository.findByProjectIdOrderByReceivedAtAscCreatedAtAsc(shared.getProject().getId()))
                .willReturn(List.of(com.majstr.backend.entity.PaymentReceipt.builder()
                        .id(UUID.randomUUID()).project(shared.getProject())
                        .amount(new BigDecimal("2700.00")).receivedAt(java.time.LocalDate.now())
                        .label("Частково за матеріали").build()));

        PublicPortalView view = publicService.viewPortal(token);

        assertThat(view.payments().unplannedReceipts()).hasSize(1);
        assertThat(view.payments().unplannedReceipts().get(0).label()).isEqualTo("Частково за матеріали");
        assertThat(view.payments().unplannedReceipts().get(0).amount()).isEqualByComparingTo("2700.00");
    }

    @Test
    void signPortal_rejectsEstimateHiddenFromThePortal() {
        Estimate estimate = sampleEstimate();
        estimate.setPortalVisible(false);
        given(projectShareLinkRepository.findByTokenAndKind(token, ShareLinkKind.PORTAL))
                .willReturn(Optional.of(usablePortalLink(estimate.getProject())));
        given(estimateRepository.findById(estimate.getId())).willReturn(Optional.of(estimate));

        assertThatThrownBy(() -> publicService.signPortal(
                token, estimate.getId(), new SignRequest("Олена", "+380671234567"), "203.0.113.42"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.DRAFT);
    }

    @Test
    void signPortal_rejectsEstimateOfAnotherProject() {
        Estimate foreign = sampleEstimate(); // belongs to a different project than the token's
        foreign.setPortalVisible(true);
        Project tokenProject = sampleEstimate().getProject();
        given(projectShareLinkRepository.findByTokenAndKind(token, ShareLinkKind.PORTAL))
                .willReturn(Optional.of(usablePortalLink(tokenProject)));
        given(estimateRepository.findById(foreign.getId())).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> publicService.signPortal(
                token, foreign.getId(), new SignRequest("Олена", "+380671234567"), "203.0.113.42"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void signPortal_signsAVisibleEstimateOfTheTokensProject() {
        Estimate estimate = sampleEstimate();
        estimate.setPortalVisible(true);
        given(projectShareLinkRepository.findByTokenAndKind(token, ShareLinkKind.PORTAL))
                .willReturn(Optional.of(usablePortalLink(estimate.getProject())));
        given(estimateRepository.findById(estimate.getId())).willReturn(Optional.of(estimate));
        given(estimateRepository.findByProjectIdAndPortalVisibleTrueOrderByCreatedAtAsc(estimate.getProject().getId()))
                .willReturn(List.of(estimate));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId()))
                .willReturn(List.of(workItem(estimate)));

        PublicPortalView view = publicService.signPortal(
                token, estimate.getId(), new SignRequest("Олена Іваненко", "+380671234567"), "203.0.113.42");

        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.SIGNED);
        assertThat(view.estimates().get(0).signature().signerName()).isEqualTo("Олена Іваненко");
    }

    @Test
    void askPortalQuestion_prefixesThePushWithTheEstimateName() {
        Estimate estimate = sampleEstimate();
        estimate.setName("Економ");
        estimate.setPortalVisible(true);
        given(projectShareLinkRepository.findByTokenAndKind(token, ShareLinkKind.PORTAL))
                .willReturn(Optional.of(usablePortalLink(estimate.getProject())));
        given(estimateRepository.findById(estimate.getId())).willReturn(Optional.of(estimate));
        given(messageRepository.save(any(ProjectMessage.class))).willAnswer(inv -> {
            ProjectMessage q = inv.getArgument(0);
            q.setId(UUID.randomUUID());
            q.setCreatedAt(Instant.now());
            return q;
        });

        publicService.askPortalQuestion(token, estimate.getId(),
                new QuestionRequest("Олена", null, "Скільки триватиме?"), "203.0.113.42");

        verify(pushService).sendToUser(
                eq(estimate.getProject().getOwner()),
                eq("Нове питання від клієнта"),
                eq("«Економ»: Скільки триватиме?"),
                contains("/projects/"));
    }

    // ---- fixtures ---------------------------------------------------------

    private ProjectShareLink usablePortalLink(Project project) {
        return ProjectShareLink.builder()
                .id(UUID.randomUUID())
                .project(project)
                .token(token)
                .createdAt(Instant.now())
                .revoked(false)
                .build();
    }

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
                .trades(Set.of(Trade.ELECTRICAL))
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
                .category("Штукатурка стін")
                .unit(Unit.M2)
                .quantity(new BigDecimal("25.500"))
                .unitPrice(new BigDecimal("180.00"))
                // The stored amount (V88): the portal reads it and computes nothing, because a read
                // must never rewrite what a client has already signed.
                .lineTotal(new BigDecimal("4590.00"))
                .sortOrder(0)
                .build();
    }

    /** A live (non-frozen) TOTAL-kind percent line — {@code quantity} is the % itself,
     *  {@code lineTotal} the amount it works out to (sign carries the markup/discount direction). */
    private EstimateItem totalPercentItem(Estimate estimate, String quantity, String lineTotal) {
        return EstimateItem.builder()
                .id(UUID.randomUUID())
                .estimate(estimate)
                .type(ItemType.WORK)
                .name("Націнка")
                .unit(Unit.PERCENT)
                .percentBaseKind(PercentBaseKind.TOTAL)
                .quantity(new BigDecimal(quantity))
                .lineTotal(new BigDecimal(lineTotal))
                .sortOrder(2)
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
                .lineTotal(new BigDecimal("2220.00"))
                .sortOrder(1)
                .build();
    }
}
