package com.majstr.backend.service;

import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.dto.ActShareStateResponse;
import com.majstr.backend.dto.PortalStateResponse;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.ClientEmailMissingException;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.InvalidEstimateStatusException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Owner side of the object-level client portal — TWO separate links, one per intent:
 * {@link ShareLinkKind#PORTAL} (Кошторис tab, any-status estimates, for signature, never
 * payments) and {@link ShareLinkKind#ECONOMY} (Економіка tab, SIGNED acts only, plus an optional
 * payments card). Which estimates each shows is controlled by {@link Estimate#isPortalVisible()}
 * / {@link Estimate#isEconomyVisible()} respectively — two independent flags, not one repurposed
 * one, because "should sign this" and "should appear in the money summary" are independent
 * questions. The public read side is in {@link PublicEstimateService}.
 */
@Service
@RequiredArgsConstructor
public class ProjectPortalService {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** Distinct query params per kind — legacy {@code ?t=}, signature {@code ?p=}, economy {@code ?e=},
     *  act {@code ?a=}. */
    private static final String PORTAL_PATH = "/portal/index.html?p=";
    private static final String ECONOMY_PATH = "/portal/index.html?e=";
    private static final String ACT_PATH = "/portal/index.html?a=";

    private final ProjectShareLinkRepository linkRepository;
    private final EstimateRepository estimateRepository;
    private final WorkActRepository workActRepository;
    private final WorkActItemRepository workActItemRepository;
    private final WorkActReceiptRepository workActReceiptRepository;
    private final ProjectService projectService;
    private final FeatureGuard featureGuard;
    private final PortalProperties portalProperties;
    private final EmailService emailService;

    // ---- SIGNATURE portal (PORTAL) — Кошторис tab -----------------------------------------

    @Transactional(readOnly = true)
    public PortalStateResponse state(UUID projectId, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        return stateOf(project, usableLink(projectId, ShareLinkKind.PORTAL),
                ShareLinkKind.PORTAL, ProjectPortalService::isSignaturePortalVisible);
    }

    /**
     * Publishes the SIGNATURE portal: the given estimates become visible for signing, every
     * other estimate of the object is hidden from it. Mints the link on first use and reuses it
     * afterwards (idempotent — one live URL per object per kind). Newly visible DRAFTs flip to
     * SENT, same as the legacy share. Never touches payments — the SIGNATURE portal does not
     * have a payments card, so there is nothing here to toggle. A SIGNED id is coerced to hidden
     * regardless of what was requested — it has "moved" to ECONOMY, the SIGNATURE portal is not a
     * second place to show it (defense-in-depth; the picker never offers one to tick in the first
     * place, since {@link #state} already masks it out of what looks tickable).
     */
    @Transactional
    public PortalStateResponse update(UUID projectId, List<UUID> estimateIds, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        requireSharable(project.getOwner());

        applyVisibility(projectId, estimateIds, (estimate, visible) -> {
            boolean actualVisible = visible && estimate.getStatus() != EstimateStatus.SIGNED;
            if (actualVisible && estimate.getStatus() == EstimateStatus.DRAFT) {
                estimate.setStatus(EstimateStatus.SENT);
            }
            estimate.setPortalVisible(actualVisible);
        });

        ProjectShareLink link = mintOrReuse(project, ShareLinkKind.PORTAL);
        return stateOf(project, Optional.of(link), ShareLinkKind.PORTAL, ProjectPortalService::isSignaturePortalVisible);
    }

    /** Emails the SIGNATURE portal link. Requires a published link — the PWA always PUTs first. */
    @Transactional(readOnly = true)
    public PortalStateResponse sendEmail(UUID projectId, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        requireSharable(project.getOwner());
        ProjectShareLink link = requireUsableLink(projectId, ShareLinkKind.PORTAL);
        emailLink(project, buildUrl(link.getToken(), ShareLinkKind.PORTAL));
        return stateOf(project, Optional.of(link), ShareLinkKind.PORTAL, ProjectPortalService::isSignaturePortalVisible);
    }

    /** A SIGNED estimate reads as never portal-visible, regardless of the stored flag — it has
     *  "moved" to ECONOMY. Masking the READ side (rather than only refusing the WRITE side in
     *  {@link #update}) is what makes a stale pre-existing flag self-heal: the picker seeds its
     *  ticked set from this, so a SIGNED id drops out of what gets re-published on the very next
     *  publish, without needing a data migration. */
    private static boolean isSignaturePortalVisible(Estimate estimate) {
        return estimate.isPortalVisible() && estimate.getStatus() != EstimateStatus.SIGNED;
    }

    // ---- ECONOMY portal — Економіка tab ----------------------------------------------------

    @Transactional(readOnly = true)
    public PortalStateResponse economyState(UUID projectId, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        return stateOf(project, usableLink(projectId, ShareLinkKind.ECONOMY),
                ShareLinkKind.ECONOMY, Estimate::isEconomyVisible);
    }

    /**
     * Publishes the ECONOMY portal: the given SIGNED estimates become visible in the object's
     * money summary, every other estimate is hidden from it. Every id must already be SIGNED —
     * the ECONOMY portal is a settled-money view, not a second place to sign something; rejecting
     * a non-SIGNED id here is defense-in-depth, the picker never offers one. Mints/reuses the
     * ECONOMY link the same idempotent way the SIGNATURE one does.
     */
    @Transactional
    public PortalStateResponse updateEconomy(UUID projectId, List<UUID> estimateIds,
                                              boolean paymentsVisible, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        requireSharable(project.getOwner());

        applyVisibility(projectId, estimateIds, (estimate, visible) -> {
            if (visible && estimate.getStatus() != EstimateStatus.SIGNED) {
                throw new InvalidEstimateStatusException("error.estimate.not-signed-economy");
            }
            estimate.setEconomyVisible(visible);
        });

        ProjectShareLink link = mintOrReuse(project, ShareLinkKind.ECONOMY);
        link.setPaymentsVisible(paymentsVisible);
        return stateOf(project, Optional.of(link), ShareLinkKind.ECONOMY, Estimate::isEconomyVisible);
    }

    /** Emails the ECONOMY portal link. Requires a published link — the PWA always PUTs first. */
    @Transactional(readOnly = true)
    public PortalStateResponse sendEconomyEmail(UUID projectId, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        requireSharable(project.getOwner());
        ProjectShareLink link = requireUsableLink(projectId, ShareLinkKind.ECONOMY);
        emailLink(project, buildUrl(link.getToken(), ShareLinkKind.ECONOMY));
        return stateOf(project, Optional.of(link), ShareLinkKind.ECONOMY, Estimate::isEconomyVisible);
    }

    // ---- ACT portal — one link per act (Акти tab) ------------------------------------------

    @Transactional(readOnly = true)
    public ActShareStateResponse actState(UUID actId, UUID ownerId) {
        WorkAct act = loadOwnedAct(actId, ownerId);
        return actStateOf(act, actLink(actId));
    }

    /**
     * Publishes ONE act to its client link: flips a DRAFT act to SENT (a document the client can now
     * see and sign) and mints/reuses the act's own link. A REJECTED act cannot be shared. Idempotent
     * — re-publishing a SENT/SIGNED act just returns the existing link.
     */
    @Transactional
    public ActShareStateResponse updateAct(UUID actId, UUID ownerId) {
        WorkAct act = loadOwnedAct(actId, ownerId);
        requireSharable(act.getProject().getOwner());
        if (act.getStatus() == WorkActStatus.REJECTED) {
            throw new InvalidEstimateStatusException("error.work-act.not-shareable");
        }
        if (act.getStatus() == WorkActStatus.DRAFT) {
            // An empty act must never leave DRAFT (review fix): once SENT the client could sign it,
            // and a SIGNED act is immutable and undeletable.
            if (!workActItemRepository.existsByWorkActId(actId)
                    && !workActReceiptRepository.existsByWorkActId(actId)) {
                throw new WorkActValidationException("error.work-act.empty", "WORK_ACT_EMPTY");
            }
            act.setStatus(WorkActStatus.SENT);
            act.setSentAt(Instant.now());
        }
        return actStateOf(act, Optional.of(mintOrReuseActLink(act)));
    }

    /** Emails the act link to the client. Requires a published link — the PWA always PUTs first. */
    @Transactional(readOnly = true)
    public ActShareStateResponse sendActEmail(UUID actId, UUID ownerId) {
        WorkAct act = loadOwnedAct(actId, ownerId);
        requireSharable(act.getProject().getOwner());
        ProjectShareLink link = actLink(actId)
                .orElseThrow(() -> new ResourceNotFoundException("ACT link not found for act " + actId));
        emailLink(act.getProject(), portalProperties.publicBaseUrl() + ACT_PATH + link.getToken());
        return actStateOf(act, Optional.of(link));
    }

    private WorkAct loadOwnedAct(UUID actId, UUID ownerId) {
        return workActRepository.findByIdAndUserId(actId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Work act not found: " + actId));
    }

    private Optional<ProjectShareLink> actLink(UUID actId) {
        return linkRepository.findFirstByWorkActIdAndRevokedFalseOrderByCreatedAtDesc(actId)
                .filter(l -> l.isUsable(Instant.now()));
    }

    private ProjectShareLink mintOrReuseActLink(WorkAct act) {
        return actLink(act.getId())
                .orElseGet(() -> linkRepository.save(ProjectShareLink.builder()
                        .project(act.getProject())
                        .token(generateToken())
                        .kind(ShareLinkKind.ACT)
                        .workAct(act)
                        .revoked(false)
                        .build()));
    }

    private ActShareStateResponse actStateOf(WorkAct act, Optional<ProjectShareLink> link) {
        boolean shareable = act.getStatus() == WorkActStatus.SENT || act.getStatus() == WorkActStatus.SIGNED;
        String url = link.map(l -> portalProperties.publicBaseUrl() + ACT_PATH + l.getToken()).orElse(null);
        return new ActShareStateResponse(url, url != null && shareable);
    }

    // ---- shared helpers ---------------------------------------------------------------------

    /**
     * Takes the link as an already-resolved {@code Optional} rather than looking it up itself —
     * a caller that just minted/reused/mutated a link (e.g. {@code updateEconomy} setting
     * {@code paymentsVisible}) must pass THAT instance, not trigger a second repository query that
     * would read back the stale pre-mutation row (real bug, caught by
     * {@code updateEconomy_setsExactVisibleSetAndPaymentsVisible_mintsOneLink}).
     */
    private PortalStateResponse stateOf(Project project, Optional<ProjectShareLink> link,
                                         ShareLinkKind kind, Predicate<Estimate> visibleFlag) {
        String url = link.map(l -> buildUrl(l.getToken(), kind)).orElse(null);
        boolean paymentsVisible = link.map(ProjectShareLink::isPaymentsVisible).orElse(false);
        return new PortalStateResponse(url, estimateFlags(project.getId(), visibleFlag), paymentsVisible);
    }

    private Optional<ProjectShareLink> usableLink(UUID projectId, ShareLinkKind kind) {
        return linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(projectId, kind)
                .filter(l -> l.isUsable(Instant.now()));
    }

    private ProjectShareLink requireUsableLink(UUID projectId, ShareLinkKind kind) {
        return usableLink(projectId, kind)
                .orElseThrow(() -> new ResourceNotFoundException(kind + " link not found for project " + projectId));
    }

    private ProjectShareLink mintOrReuse(Project project, ShareLinkKind kind) {
        return usableLink(project.getId(), kind)
                .orElseGet(() -> linkRepository.save(ProjectShareLink.builder()
                        .project(project)
                        .token(generateToken())
                        .kind(kind)
                        .revoked(false)
                        .build()));
    }

    /** Applies a "these ids, and only these, are visible" set — rejecting rather than
     *  half-applying if an id belongs to no estimate of this object. */
    private void applyVisibility(UUID projectId, List<UUID> estimateIds, BiConsumer<Estimate, Boolean> apply) {
        List<Estimate> estimates = estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        Set<UUID> wanted = new HashSet<>(estimateIds);
        for (Estimate estimate : estimates) {
            apply.accept(estimate, wanted.remove(estimate.getId()));
        }
        if (!wanted.isEmpty()) {
            throw new ResourceNotFoundException("Estimate not found in project " + projectId + ": " + wanted.iterator().next());
        }
    }

    private List<PortalStateResponse.PortalEstimate> estimateFlags(UUID projectId, Predicate<Estimate> visibleFlag) {
        return estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(e -> new PortalStateResponse.PortalEstimate(
                        e.getId(), e.getName(), e.getStatus(), e.getCreatedAt(), visibleFlag.test(e)))
                .toList();
    }

    private void emailLink(Project project, String url) {
        Client client = project.getClient();
        if (client == null || client.getEmail() == null || client.getEmail().isBlank()) {
            throw new ClientEmailMissingException("error.client-email-missing");
        }
        User owner = project.getOwner();
        String contractorName = (owner.getCompanyName() != null && !owner.getCompanyName().isBlank())
                ? owner.getCompanyName()
                : owner.getFullName();
        emailService.sendEstimateShareEmail(
                client.getEmail(), client.getFullName(), contractorName, project.getName(), url);
    }

    /** Same plan + verified-email gate as the legacy per-estimate share, for both link kinds. */
    private void requireSharable(User owner) {
        featureGuard.requireFeature(owner, Feature.CLIENT_PORTAL);
        if (!owner.isEmailVerified()) {
            throw new EmailNotVerifiedException("error.email-not-verified");
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private String buildUrl(String token, ShareLinkKind kind) {
        String path = kind == ShareLinkKind.ECONOMY ? ECONOMY_PATH : PORTAL_PATH;
        return portalProperties.publicBaseUrl() + path + token;
    }
}
