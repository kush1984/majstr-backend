package com.majstr.backend.service;

import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.dto.ShareLinkResponse;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ClientEmailMissingException;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * The per-estimate share link ({@code ?t=}) — one link, one estimate, independent of the object's
 * portal links. This is what "поділитися цим кошторисом" from the estimate editor mints: the client
 * who opens it sees exactly that document and nothing else of the object, and publishing it neither
 * reads nor touches {@code portal_visible} / {@code economy_visible}. The set-based object links
 * live in {@link ProjectPortalService}; the public read side of all of them is
 * {@link PublicEstimateService}.
 */
@Service
@RequiredArgsConstructor
public class ShareLinkService {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String PORTAL_PATH = "/portal/index.html";

    private final EstimateShareLinkRepository repository;
    private final EstimateService estimateService;
    private final FeatureGuard featureGuard;
    private final PortalProperties portalProperties;
    private final EmailService emailService;

    /**
     * Mints the estimate's link, or returns the one it already has — idempotent, like every other
     * share flow here. The master taps "поділитися" once per send, but a re-tap must hand back the
     * SAME URL: minting a fresh token each time would leave every previously copied link live and
     * unrevokable-in-practice (the master has no idea how many exist), and the client who bookmarked
     * yesterday's URL would silently be on a different link from the one just sent.
     */
    @Transactional
    public ShareLinkResponse create(UUID estimateId, UUID ownerId) {
        Estimate estimate = estimateService.loadOwned(estimateId, ownerId);
        requireSharable(estimate);
        flipToSentIfDraft(estimate);
        EstimateShareLink link = usableLink(estimateId).orElseGet(() -> repository.save(newLink(estimate)));
        return ShareLinkResponse.from(link, buildUrl(link.getToken()));
    }

    /**
     * Email the estimate's portal link to its client. Same gate + DRAFT->SENT
     * flip as {@code create}, but reuses an existing active link if there is
     * one. Requires the client to have an email on file.
     */
    @Transactional
    public ShareLinkResponse sendByEmail(UUID estimateId, UUID ownerId) {
        Estimate estimate = estimateService.loadOwned(estimateId, ownerId);
        requireSharable(estimate);

        Client client = estimate.getProject().getClient();
        if (client == null || client.getEmail() == null || client.getEmail().isBlank()) {
            throw new ClientEmailMissingException("error.client-email-missing");
        }
        flipToSentIfDraft(estimate);

        EstimateShareLink link = usableLink(estimateId).orElseGet(() -> repository.save(newLink(estimate)));
        String url = buildUrl(link.getToken());

        User owner = estimate.getProject().getOwner();
        String contractorName = (owner.getCompanyName() != null && !owner.getCompanyName().isBlank())
                ? owner.getCompanyName()
                : owner.getFullName();
        emailService.sendEstimateShareEmail(
                client.getEmail(), client.getFullName(), contractorName,
                estimate.getProject().getName(), url);

        return ShareLinkResponse.from(link, url);
    }

    @Transactional
    public void revoke(UUID estimateId, UUID linkId, UUID ownerId) {
        EstimateShareLink link = repository.findById(linkId)
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found: " + linkId));
        if (!link.getEstimate().getId().equals(estimateId)) {
            throw new ResourceNotFoundException("Share link not found in estimate " + estimateId);
        }
        if (!link.getEstimate().getProject().getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Share link does not belong to the current user");
        }
        link.setRevoked(true);
    }

    /** The estimate's current link, if it is still usable — an EXPIRED one is not reused (the
     *  repository query only filters out revoked ones), it is replaced by a fresh mint. */
    private Optional<EstimateShareLink> usableLink(UUID estimateId) {
        return repository.findFirstByEstimateIdAndRevokedFalseOrderByCreatedAtDesc(estimateId)
                .filter(l -> l.isUsable(Instant.now()));
    }

    /** Plan feature + verified-email preconditions for any client-facing share (checked before mutating). */
    private void requireSharable(Estimate estimate) {
        User owner = estimate.getProject().getOwner();
        featureGuard.requireFeature(owner, Feature.CLIENT_PORTAL);
        if (!owner.isEmailVerified()) {
            throw new EmailNotVerifiedException("error.email-not-verified");
        }
    }

    /** Sharing means the estimate is no longer a draft (feeds the "pending" metric / "Надіслано" badge). */
    private void flipToSentIfDraft(Estimate estimate) {
        if (estimate.getStatus() == EstimateStatus.DRAFT) {
            estimate.setStatus(EstimateStatus.SENT);
        }
    }

    private EstimateShareLink newLink(Estimate estimate) {
        return EstimateShareLink.builder()
                .estimate(estimate)
                .token(generateToken())
                .revoked(false)
                .build();
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    public String buildUrl(String token) {
        return portalProperties.publicBaseUrl() + PORTAL_PATH + "?t=" + token;
    }
}
