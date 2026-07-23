package com.majstr.backend.service;

import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.PublicEstimateItemView;
import com.majstr.backend.dto.PublicEstimateView;
import com.majstr.backend.dto.PublicPortalView;
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
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateQuestionRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything a client can reach without authentication. Two token families
 * resolve here: the legacy per-estimate {@code ?t=} links (kept alive for URLs
 * already sent out) and the object-level portal {@code ?p=} links, whose page
 * shows a section per {@code portalVisible} estimate. Sign / question / PDF
 * share one core; only the token resolution differs.
 */
@Service
@RequiredArgsConstructor
public class PublicEstimateService {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final EstimateShareLinkRepository shareLinkRepository;
    private final ProjectShareLinkRepository projectShareLinkRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository itemRepository;
    private final EstimateQuestionRepository questionRepository;
    private final EstimateService estimateService;
    private final ProjectPhotoService projectPhotoService;
    private final FeatureGuard featureGuard;
    private final PushService pushService;
    private final MessageSource messages;

    // ---- legacy per-estimate token (?t=) ----------------------------------

    @Transactional(readOnly = true)
    public PublicEstimateView view(String token) {
        Estimate estimate = resolveEstimate(token);
        return buildView(estimate);
    }

    @Transactional
    public PublicEstimateView sign(String token, SignRequest req, String clientIp) {
        Estimate estimate = resolveEstimate(token);
        doSign(estimate, req, clientIp);
        return buildView(estimate);
    }

    @Transactional
    public QuestionResponse askQuestion(String token, QuestionRequest req, String clientIp) {
        return doAsk(resolveEstimate(token), req, clientIp);
    }

    @Transactional(readOnly = true)
    public byte[] renderPdf(String token) throws IOException, DocumentException {
        Estimate estimate = resolveEstimate(token);
        return estimateService.renderPdf(estimate);
    }

    /**
     * Streams a photo the master shared with the client. The token resolves to the object;
     * only a SHARED photo of that same object is served (a private / receipt photo, or a
     * photo of another object, is a 404) — the client never reaches private assets.
     */
    @Transactional(readOnly = true)
    public ProjectPhotoService.PhotoFile readSharedPhoto(String token, UUID photoId) throws IOException {
        Estimate estimate = resolveEstimate(token);
        return projectPhotoService.readSharedFile(estimate.getProject().getId(), photoId);
    }

    // ---- project portal token (?p=) ---------------------------------------

    @Transactional(readOnly = true)
    public PublicPortalView viewPortal(String token) {
        Project project = resolveProject(token);
        List<PublicPortalView.Section> sections =
                estimateRepository.findByProjectIdAndPortalVisibleTrueOrderByCreatedAtAsc(project.getId())
                        .stream()
                        .map(this::sectionOf)
                        .toList();
        Client client = project.getClient();
        User contractor = project.getOwner();
        List<PublicEstimateView.SharedPhoto> sharedPhotos = projectPhotoService.sharedPhotos(project.getId())
                .stream()
                .map(p -> new PublicEstimateView.SharedPhoto(p.getId(), p.getCaption()))
                .toList();
        return new PublicPortalView(
                contractorOf(contractor),
                new PublicEstimateView.ProjectSummary(
                        project.getName(),
                        project.getAddress(),
                        client == null ? null : client.getFullName()),
                sections,
                sharedPhotos);
    }

    @Transactional
    public PublicPortalView signPortal(String token, UUID estimateId, SignRequest req, String clientIp) {
        Estimate estimate = resolvePortalEstimate(token, estimateId);
        doSign(estimate, req, clientIp);
        return viewPortal(token);
    }

    @Transactional
    public QuestionResponse askPortalQuestion(String token, UUID estimateId, QuestionRequest req, String clientIp) {
        return doAsk(resolvePortalEstimate(token, estimateId), req, clientIp);
    }

    @Transactional(readOnly = true)
    public byte[] renderPortalPdf(String token, UUID estimateId) throws IOException, DocumentException {
        return estimateService.renderPdf(resolvePortalEstimate(token, estimateId));
    }

    @Transactional(readOnly = true)
    public ProjectPhotoService.PhotoFile readPortalSharedPhoto(String token, UUID photoId) throws IOException {
        Project project = resolveProject(token);
        return projectPhotoService.readSharedFile(project.getId(), photoId);
    }

    // ---- shared core ------------------------------------------------------

    private void doSign(Estimate estimate, SignRequest req, String clientIp) {
        User contractor = estimate.getProject().getOwner();
        featureGuard.requireFeature(contractor, Feature.ONLINE_SIGNATURE);
        if (estimate.getStatus() == EstimateStatus.SIGNED) {
            // 409 + code ESTIMATE_SIGNED via the advice — same contract as the
            // contractor-side guard, localized for the portal client.
            throw new EstimateSignedException();
        }
        estimate.setStatus(EstimateStatus.SIGNED);
        estimate.setSignedAt(Instant.now());
        estimate.setSignerName(req.clientName().trim());
        estimate.setSignerPhone(req.clientPhone().trim());
        estimate.setSignerIp(clientIp);
        // Economy counting is default-on and owner-curated (a signed consolidated
        // must stay excluded), so signing no longer force-sets the flag.
        // A signed estimate means work begins — activate the project so it
        // counts in the "active projects" metric. Don't override a project
        // that's already in progress or completed.
        Project project = estimate.getProject();
        if (project.getStatus() != ProjectStatus.IN_PROGRESS && project.getStatus() != ProjectStatus.COMPLETED) {
            project.setStatus(ProjectStatus.IN_PROGRESS);
        }
        // Notify the contractor in real time (fail-soft — never breaks signing).
        // Contractor notifications always use the product language (uk base bundle).
        Totals totals = totalsOf(estimate);
        String title = messages.getMessage("push.estimate-signed",
                new Object[]{req.clientName().trim(), formatHryvnia(totals.total())},
                LocalizationConfig.UKRAINIAN);
        pushService.sendToUser(contractor, title, pushBody(estimate), "/projects/" + project.getId());
    }

    private QuestionResponse doAsk(Estimate estimate, QuestionRequest req, String clientIp) {
        EstimateQuestion question = EstimateQuestion.builder()
                .estimate(estimate)
                .authorName(blankToNull(req.authorName()))
                .authorPhone(blankToNull(req.authorPhone()))
                .message(req.message().trim())
                .authorIp(clientIp)
                .build();
        QuestionResponse saved = QuestionResponse.from(questionRepository.save(question));
        // Notify the contractor in real time (fail-soft — never breaks the question).
        // The body carries the estimate name so the master knows which variant it's about.
        User contractor = estimate.getProject().getOwner();
        String body = estimate.getName() == null || estimate.getName().isBlank()
                ? question.getMessage()
                : "«" + estimate.getName() + "»: " + question.getMessage();
        pushService.sendToUser(contractor,
                messages.getMessage("push.question.title", null, LocalizationConfig.UKRAINIAN),
                body,
                "/projects/" + estimate.getProject().getId());
        return saved;
    }

    // ---- token resolution -------------------------------------------------

    /**
     * Looks up the legacy share link by raw token. Every failure mode — unknown
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

    /** Portal token → project, same neutral 404 on every failure mode. */
    private Project resolveProject(String token) {
        if (token == null || token.isBlank()) {
            throw new ResourceNotFoundException("Share link not found");
        }
        ProjectShareLink link = projectShareLinkRepository.findByToken(token).orElse(null);
        if (link == null || !link.isUsable(Instant.now())) {
            throw new ResourceNotFoundException("Share link not found");
        }
        return link.getProject();
    }

    /** An estimate is reachable through the portal only while the master shows it there. */
    private Estimate resolvePortalEstimate(String token, UUID estimateId) {
        Project project = resolveProject(token);
        Estimate estimate = estimateRepository.findById(estimateId).orElse(null);
        if (estimate == null
                || !estimate.getProject().getId().equals(project.getId())
                || !estimate.isPortalVisible()) {
            throw new ResourceNotFoundException("Estimate not found");
        }
        return estimate;
    }

    // ---- view building ----------------------------------------------------

    private record Totals(List<PublicEstimateItemView> items, BigDecimal works, BigDecimal materials,
                          BigDecimal total, BigDecimal deposit, BigDecimal balance) {}

    private Totals totalsOf(Estimate estimate) {
        List<EstimateItem> items = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimate.getId());
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
        BigDecimal deposit = estimate.getDepositAmount();
        BigDecimal balance = deposit == null
                ? total
                : total.subtract(deposit).max(BigDecimal.ZERO).setScale(MONEY_SCALE, MONEY_ROUNDING);
        return new Totals(itemViews, works, materials, total, deposit, balance);
    }

    private PublicPortalView.Section sectionOf(Estimate estimate) {
        Totals t = totalsOf(estimate);
        return new PublicPortalView.Section(
                estimate.getId(),
                estimate.getName(),
                estimate.getStatus(),
                estimate.getValidUntil(),
                estimate.getNotes(),
                estimate.getCreatedAt(),
                t.items(),
                t.works(),
                t.materials(),
                t.total(),
                t.deposit(),
                t.balance(),
                signatureOf(estimate));
    }

    private PublicEstimateView buildView(Estimate estimate) {
        Project project = estimate.getProject();
        Client client = project.getClient();
        Totals t = totalsOf(estimate);

        List<PublicEstimateView.SharedPhoto> sharedPhotos = projectPhotoService.sharedPhotos(project.getId())
                .stream()
                .map(p -> new PublicEstimateView.SharedPhoto(p.getId(), p.getCaption()))
                .toList();

        return new PublicEstimateView(
                contractorOf(project.getOwner()),
                new PublicEstimateView.ProjectSummary(
                        project.getName(),
                        project.getAddress(),
                        client == null ? null : client.getFullName()),
                estimate.getStatus(),
                estimate.getValidUntil(),
                estimate.getNotes(),
                estimate.getCreatedAt(),
                t.items(),
                t.works(),
                t.materials(),
                t.total(),
                t.deposit(),
                t.balance(),
                signatureOf(estimate),
                sharedPhotos
        );
    }

    private PublicEstimateView.Contractor contractorOf(User contractor) {
        return new PublicEstimateView.Contractor(
                contractor.getCompanyName(),
                contractor.getFullName(),
                contractor.getPhone(),
                contractor.getLogoUrl() == null ? null : "/api/files/" + contractor.getLogoUrl()
        );
    }

    private static PublicEstimateView.Signature signatureOf(Estimate estimate) {
        return estimate.getSignedAt() == null
                ? null
                : new PublicEstimateView.Signature(estimate.getSignedAt(), estimate.getSignerName());
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

    /** «Питання по кошторису» push body / project name pairing for the sign push. */
    private static String pushBody(Estimate estimate) {
        String projectName = estimate.getProject().getName();
        return estimate.getName() == null || estimate.getName().isBlank()
                ? projectName
                : projectName + " · «" + estimate.getName() + "»";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Formats an amount as "61 070 ₴" — space-grouped, no decimals. */
    private static String formatHryvnia(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(' '); // non-breaking space
        DecimalFormat df = new DecimalFormat("#,##0", symbols);
        return df.format(amount.setScale(0, RoundingMode.HALF_UP)) + " ₴";
    }
}
