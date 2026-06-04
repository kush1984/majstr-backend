package com.majstr.backend.service;

import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.PublicEstimateItemView;
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
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateQuestionRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PublicEstimateService {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final EstimateShareLinkRepository shareLinkRepository;
    private final EstimateItemRepository itemRepository;
    private final EstimateQuestionRepository questionRepository;
    private final EstimateService estimateService;
    private final FeatureGuard featureGuard;
    private final PushService pushService;

    @Transactional(readOnly = true)
    public PublicEstimateView view(String token) {
        Estimate estimate = resolveEstimate(token);
        List<EstimateItem> items = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId());
        return buildView(estimate, items);
    }

    @Transactional
    public PublicEstimateView sign(String token, SignRequest req, String clientIp) {
        Estimate estimate = resolveEstimate(token);
        User contractor = estimate.getProject().getOwner();
        featureGuard.requireFeature(contractor, Feature.ONLINE_SIGNATURE);
        if (estimate.getStatus() == EstimateStatus.SIGNED) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Estimate is already signed");
        }
        estimate.setStatus(EstimateStatus.SIGNED);
        estimate.setSignedAt(Instant.now());
        estimate.setSignerName(req.clientName().trim());
        estimate.setSignerPhone(req.clientPhone().trim());
        estimate.setSignerIp(clientIp);
        // A signed estimate means work begins — activate the project so it
        // counts in the "active projects" metric. Don't override a project
        // that's already in progress or completed.
        Project project = estimate.getProject();
        if (project.getStatus() != ProjectStatus.IN_PROGRESS && project.getStatus() != ProjectStatus.COMPLETED) {
            project.setStatus(ProjectStatus.IN_PROGRESS);
        }
        List<EstimateItem> items = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId());
        PublicEstimateView view = buildView(estimate, items);
        // Notify the contractor in real time (fail-soft — never breaks signing).
        pushService.sendToUser(contractor,
                req.clientName().trim() + " підписав(ла) кошторис на " + formatHryvnia(view.total()),
                project.getName(),
                "/projects/" + project.getId());
        return view;
    }

    @Transactional
    public QuestionResponse askQuestion(String token, QuestionRequest req, String clientIp) {
        Estimate estimate = resolveEstimate(token);
        EstimateQuestion question = EstimateQuestion.builder()
                .estimate(estimate)
                .authorName(blankToNull(req.authorName()))
                .authorPhone(blankToNull(req.authorPhone()))
                .message(req.message().trim())
                .authorIp(clientIp)
                .build();
        QuestionResponse saved = QuestionResponse.from(questionRepository.save(question));
        // Notify the contractor in real time (fail-soft — never breaks the question).
        User contractor = estimate.getProject().getOwner();
        pushService.sendToUser(contractor,
                "Нове питання від клієнта",
                question.getMessage(),
                "/projects/" + estimate.getProject().getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public byte[] renderPdf(String token) throws IOException, DocumentException {
        Estimate estimate = resolveEstimate(token);
        return estimateService.renderPdf(estimate);
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Looks up the share link by raw token. Every failure mode — unknown
     * token, revoked, expired — collapses to the same 404 so attackers
     * cannot probe whether a token exists.
     */
    private Estimate resolveEstimate(String token) {
        if (token == null || token.isBlank()) {
            throw new ResourceNotFoundException("Share link not found");
        }
        EstimateShareLink link = shareLinkRepository.findByToken(token).orElse(null);
        if (link == null || !link.isUsable(Instant.now())) {
            throw new ResourceNotFoundException("Share link not found");
        }
        return link.getEstimate();
    }

    private PublicEstimateView buildView(Estimate estimate, List<EstimateItem> items) {
        Project project = estimate.getProject();
        Client client = project.getClient();
        User contractor = project.getOwner();

        BigDecimal works = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal materials = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        List<PublicEstimateItemView> itemViews = items.stream()
                .map(this::toItemView)
                .toList();
        for (PublicEstimateItemView v : itemViews) {
            if (v.type() == ItemType.WORK) {
                works = works.add(v.lineTotal());
            } else {
                materials = materials.add(v.lineTotal());
            }
        }
        BigDecimal total = works.add(materials);

        PublicEstimateView.Contractor contractorDto = new PublicEstimateView.Contractor(
                contractor.getCompanyName(),
                contractor.getFullName(),
                contractor.getPhone(),
                contractor.getLogoUrl() == null ? null : "/api/files/" + contractor.getLogoUrl()
        );
        PublicEstimateView.ProjectSummary projectDto = new PublicEstimateView.ProjectSummary(
                project.getName(),
                project.getAddress(),
                client == null ? null : client.getFullName()
        );
        PublicEstimateView.Signature signatureDto = estimate.getSignedAt() == null
                ? null
                : new PublicEstimateView.Signature(estimate.getSignedAt(), estimate.getSignerName());

        return new PublicEstimateView(
                contractorDto,
                projectDto,
                estimate.getStatus(),
                estimate.getValidUntil(),
                estimate.getNotes(),
                estimate.getCreatedAt(),
                itemViews,
                works,
                materials,
                total,
                signatureDto
        );
    }

    private PublicEstimateItemView toItemView(EstimateItem item) {
        BigDecimal line = item.getQuantity().multiply(item.getUnitPrice())
                .setScale(MONEY_SCALE, MONEY_ROUNDING);
        return new PublicEstimateItemView(
                item.getType(),
                item.getName(),
                item.getUnit(),
                item.getQuantity(),
                item.getUnitPrice(),
                line,
                item.getSortOrder()
        );
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Formats an amount as "61 070 ₴" — space-grouped, no decimals. */
    private static String formatHryvnia(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(' '); // non-breaking space
        DecimalFormat df = new DecimalFormat("#,##0", symbols);
        return df.format(amount.setScale(0, RoundingMode.HALF_UP)) + " ₴";
    }
}
