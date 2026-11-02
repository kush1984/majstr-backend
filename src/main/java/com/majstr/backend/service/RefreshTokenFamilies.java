package com.majstr.backend.service;

import com.majstr.backend.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Its own bean for its own transaction: the rotation that detects a replayed token ends by refusing it,
 * which rolls back, and the revocation has to outlive that (review B-126).
 */
@Component
@RequiredArgsConstructor
class RefreshTokenFamilies {

    private final RefreshTokenRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revoke(UUID familyId) {
        repository.revokeFamily(familyId);
    }
}
