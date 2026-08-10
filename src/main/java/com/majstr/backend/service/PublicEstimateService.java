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
import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.push.PushService;
import com.majstr.backend.entity.ProjectPayment;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.ProjectMessageRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectPaymentRepository;
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
import java.time.LocalDate;
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
    private final ProjectPaymentRepository projectPaymentRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository itemRepository;
    private final ProjectMessageRepository messageRepository;
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
        ProjectShareLink link = resolveLink(token);
        Project project = link.getProject();
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
        PublicPortalView.PaymentsCard paymentsCard = link.isPaymentsVisible()
                ? buildPaymentsCard(project.getId(), sections)
                : null;
        return new PublicPortalView(
                contractorOf(contractor),
                new PublicEstimateView.ProjectSummary(
                        project.getName(),
                        project.getAddress(),
                        client == null ? null : client.getFullName()),
                sections,
                sharedPhotos,
                paymentsCard);
    }

    /**
     * {@code contractedTotal} sums only the SHARED sections passed in — never the master's
     * private "all counted estimates" total, which could include work this client was never
     * shown (isolation). {@code received}/{@code payments} are genuinely object-level: payments
     * were never tied to one estimate even before this iteration, so the full list is safe.
     */
    private PublicPortalView.PaymentsCard buildPaymentsCard(UUID objectId, List<PublicPortalView.Section> sections) {
        BigDecimal contracted = sections.stream()
                .map(PublicPortalView.Section::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ProjectPayment> rows = projectPaymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId);
        BigDecimal received = projectPaymentRepository.sumPaidByProjectId(objectId);
        BigDecimal remaining = contracted.subtract(received).max(BigDecimal.ZERO);
        LocalDate today = LocalDate.now(LocalizationConfig.ZONE);
        List<PublicPortalView.PaymentRow> paymentRows = rows.stream()
                .map(p -> new PublicPortalView.PaymentRow(
                        p.getPurpose(), p.getAmount(), p.getPaidAmount(),
                        p.getDueDate(), p.getNextStage(), p.status(today)))
                .toList();
        return new PublicPortalView.PaymentsCard(contracted, received, remaining, paymentRows);
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
        // A duplicate signed while its parent is STILL signed is a real deal replacing an old one,
        // not a second live deal — auto-reopen the parent to DRAFT (an "act" panel only exists for
        // SIGNED, so this alone drops it out of the economy) and mark which duplicate did it, so
        // the master sees a banner instead of two competing signed prices with no explanation
        // (economy-rework iteration; replaces the old "negative difference" double-count guard).
        if (estimate.getDuplicatedFromId() != null) {
            estimateRepository.findById(estimate.getDuplicatedFromId()).ifPresent(parent -> {
                if (parent.getStatus() == EstimateStatus.SIGNED) {
                    estimateService.applyReopen(parent, null);
                    parent.setSupersededByEstimateId(estimate.getId());
                }
            });
        }
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
        ProjectMessage question = ProjectMessage.builder()
                // The object is what a message belongs to now; the estimate rides along as context.
                // Builders let a missing field compile, so this one is only caught by the NOT NULL —
                // i.e. on a real client asking a real question. Covered by a test below.
                .project(estimate.getProject())
                .estimate(estimate)
                .authorName(blankToNull(req.authorName()))
                .authorPhone(blankToNull(req.authorPhone()))
                .message(req.message().trim())
                .authorIp(clientIp)
                .build();
        QuestionResponse saved = QuestionResponse.from(messageRepository.save(question));
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
        return resolveLink(token).getProject();
    }

    /** Portal token → the link itself (needed by {@link #viewPortal} for
     *  {@code isPaymentsVisible()}, not just the project it points at). */
    private ProjectShareLink resolveLink(String token) {
        if (token == null || token.isBlank()) {
            throw new ResourceNotFoundException("Share link not found");
        }
        // Kind matters here, not just the token: a MESSAGE link must never open the portal, or a
        // supplier sent the form URL would be shown the client's prices instead.
        ProjectShareLink link = projectShareLinkRepository
                .findByTokenAndKind(token, ShareLinkKind.PORTAL).orElse(null);
        if (link == null || !link.isUsable(Instant.now())) {
            throw new ResourceNotFoundException("Share link not found");
        }
        return link;
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
                          BigDecimal total, BigDecimal deposit, BigDecimal balance,
                          BigDecimal markup, BigDecimal discount,
                          BigDecimal markupPercent, BigDecimal discountPercent) {}

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

        // Same recap the app's black summary panel shows (AdjustNote / adjustTotals) — mirrored
        // here rather than re-derived on the page, so the portal never disagrees with the app.
        BigDecimal markup = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal discount = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        // Percent (portal-pdf-polish iteration): mirrors TypeBreakdown's own rule — a % is shown
        // ONLY when exactly one live (non-frozen) TOTAL-kind line explains the whole figure. A
        // frozen carried-over line never had a "live" quantity worth showing (TypeBreakdown itself
        // shows it as an amount-only "Перенесені…" row, never a percent), and more than one
        // contributing line has no single percent to report — either way this falls back to
        // sum-only rather than deriving/guessing a blended number nobody's estimate actually has.
        int markupTotalLines = 0;
        int discountTotalLines = 0;
        boolean markupHasFrozen = false;
        boolean discountHasFrozen = false;
        BigDecimal markupQuantity = null;
        BigDecimal discountQuantity = null;
        for (EstimateItem item : items) {
            if (item.getUnit() != Unit.PERCENT || item.getLineTotal() == null) {
                continue;
            }
            boolean isTotalPercent = item.getPercentBaseKind() == PercentBaseKind.TOTAL;
            boolean isFrozenPercent = item.getBaseOriginLabel() != null;
            if (!isTotalPercent && !isFrozenPercent) {
                continue;
            }
            if (item.getLineTotal().signum() > 0) {
                markup = markup.add(item.getLineTotal());
                if (isFrozenPercent) {
                    markupHasFrozen = true;
                } else {
                    markupTotalLines++;
                    markupQuantity = item.getQuantity();
                }
            } else if (item.getLineTotal().signum() < 0) {
                discount = discount.add(item.getLineTotal());
                if (isFrozenPercent) {
                    discountHasFrozen = true;
                } else {
                    discountTotalLines++;
                    discountQuantity = item.getQuantity();
                }
            }
        }
        BigDecimal markupPercent = (markupTotalLines == 1 && !markupHasFrozen && markupQuantity != null)
                ? markupQuantity.abs() : null;
        BigDecimal discountPercent = (discountTotalLines == 1 && !discountHasFrozen && discountQuantity != null)
                ? discountQuantity.abs() : null;
        return new Totals(itemViews, works, materials, total, deposit, balance,
                markup, discount, markupPercent, discountPercent);
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
                t.markup(),
                t.discount(),
                t.markupPercent(),
                t.discountPercent(),
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
                t.markup(),
                t.discount(),
                t.markupPercent(),
                t.discountPercent(),
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
        // The STORED amount (V88). The portal is what the client signs: re-deriving the arithmetic
        // in a seventh place is how the page a client agrees to ends up disagreeing with the PDF
        // beside it, and a percentage of the estimate's own subtotal cannot be derived per row.
        BigDecimal line = item.getLineTotal() == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING)
                : item.getLineTotal();
        return new PublicEstimateItemView(
                item.getType(),
                item.getName(),
                item.getUnit(),
                item.getQuantity(),
                item.getUnitPrice(),
                line,
                item.getSortOrder(),
                item.getCategory()
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
