package com.majstr.backend.repository;

import com.majstr.backend.entity.PriceListLink;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PriceListLinkRepository extends JpaRepository<PriceListLink, UUID> {

    Optional<PriceListLink> findFirstByOwnerIdAndRevokedFalse(UUID ownerId);

    boolean existsByOwnerId(UUID ownerId);

    @EntityGraph(attributePaths = "owner")
    Optional<PriceListLink> findByTokenAndRevokedFalse(String token);
}
