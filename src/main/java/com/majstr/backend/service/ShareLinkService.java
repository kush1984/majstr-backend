package com.majstr.backend.service;

import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.dto.ShareLinkResponse;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

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

    @Transactional
    public ShareLinkResponse create(UUID estimateId, UUID ownerId) {
        Estimate estimate = estimateService.loadOwned(estimateId, ownerId);
        featureGuard.requireFeature(estimate.getProject().getOwner(), Feature.CLIENT_PORTAL);
        // Sharing with a client means the estimate is no longer a draft. Flip
        // DRAFT -> SENT so it counts towards "pending signature" and shows the
        // right badge. Leave SIGNED / REJECTED untouched.
        if (estimate.getStatus() == EstimateStatus.DRAFT) {
            estimate.setStatus(EstimateStatus.SENT);
        }
        EstimateShareLink link = EstimateShareLink.builder()
                .estimate(estimate)
                .token(generateToken())
                .revoked(false)
                .build();
        link = repository.save(link);
        return ShareLinkResponse.from(link, buildUrl(link.getToken()));
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

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    public String buildUrl(String token) {
        return portalProperties.publicBaseUrl() + PORTAL_PATH + "?t=" + token;
    }
}
