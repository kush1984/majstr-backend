package com.majstr.backend.service;

import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.dto.PortalStateResponse;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ClientEmailMissingException;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
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

/**
 * Owner side of the object-level client portal: which estimates are visible
 * and the (single, idempotently-minted) share URL. The public read side is in
 * {@link PublicEstimateService}.
 */
@Service
@RequiredArgsConstructor
public class ProjectPortalService {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** Distinct query param from the legacy per-estimate {@code ?t=} links. */
    private static final String PORTAL_PATH = "/portal/index.html?p=";

    private final ProjectShareLinkRepository linkRepository;
    private final EstimateRepository estimateRepository;
    private final ProjectService projectService;
    private final FeatureGuard featureGuard;
    private final PortalProperties portalProperties;
    private final EmailService emailService;

    @Transactional(readOnly = true)
    public PortalStateResponse state(UUID projectId, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        Optional<ProjectShareLink> link = linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.PORTAL)
                .filter(l -> l.isUsable(Instant.now()));
        String url = link.map(l -> buildUrl(l.getToken())).orElse(null);
        boolean paymentsVisible = link.map(ProjectShareLink::isPaymentsVisible).orElse(false);
        return new PortalStateResponse(url, estimateFlags(project.getId()), paymentsVisible);
    }

    /**
     * Publishes the portal: the given estimates become visible, every other
     * estimate of the object is hidden. Mints the project link on first use and
     * reuses it afterwards (idempotent — one live URL per object). Newly
     * visible DRAFTs flip to SENT, same as the legacy share.
     */
    @Transactional
    public PortalStateResponse update(UUID projectId, List<UUID> estimateIds, boolean paymentsVisible, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        requireSharable(project.getOwner());

        List<Estimate> estimates = estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        Set<UUID> wanted = new HashSet<>(estimateIds);
        for (Estimate estimate : estimates) {
            boolean visible = wanted.remove(estimate.getId());
            if (visible && estimate.getStatus() == EstimateStatus.DRAFT) {
                estimate.setStatus(EstimateStatus.SENT);
            }
            estimate.setPortalVisible(visible);
        }
        // Ids that matched no estimate of this object — reject rather than half-apply.
        if (!wanted.isEmpty()) {
            throw new ResourceNotFoundException("Estimate not found in project " + projectId + ": " + wanted.iterator().next());
        }

        ProjectShareLink link = linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.PORTAL)
                .filter(l -> l.isUsable(Instant.now()))
                .orElseGet(() -> linkRepository.save(ProjectShareLink.builder()
                        .project(project)
                        .token(generateToken())
                        .revoked(false)
                        .build()));
        link.setPaymentsVisible(paymentsVisible);

        return new PortalStateResponse(buildUrl(link.getToken()), estimateFlags(projectId), link.isPaymentsVisible());
    }

    /**
     * Emails the portal link to the object's client. Requires a published
     * portal (an active link must exist — the PWA always PUTs before sending).
     */
    @Transactional(readOnly = true)
    public PortalStateResponse sendEmail(UUID projectId, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        requireSharable(project.getOwner());

        Client client = project.getClient();
        if (client == null || client.getEmail() == null || client.getEmail().isBlank()) {
            throw new ClientEmailMissingException("error.client-email-missing");
        }
        ProjectShareLink link = linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                projectId, ShareLinkKind.PORTAL)
                .filter(l -> l.isUsable(Instant.now()))
                .orElseThrow(() -> new ResourceNotFoundException("Portal link not found for project " + projectId));

        String url = buildUrl(link.getToken());
        User owner = project.getOwner();
        String contractorName = (owner.getCompanyName() != null && !owner.getCompanyName().isBlank())
                ? owner.getCompanyName()
                : owner.getFullName();
        emailService.sendEstimateShareEmail(
                client.getEmail(), client.getFullName(), contractorName, project.getName(), url);

        return new PortalStateResponse(url, estimateFlags(projectId), link.isPaymentsVisible());
    }

    // ---- helpers ----------------------------------------------------------

    private List<PortalStateResponse.PortalEstimate> estimateFlags(UUID projectId) {
        return estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(e -> new PortalStateResponse.PortalEstimate(
                        e.getId(), e.getName(), e.getStatus(), e.getCreatedAt(), e.isPortalVisible()))
                .toList();
    }

    /** Same plan + verified-email gate as the legacy per-estimate share. */
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

    private String buildUrl(String token) {
        return portalProperties.publicBaseUrl() + PORTAL_PATH + token;
    }
}
