package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogUpdateNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CatalogUpdateNoticeRepository extends JpaRepository<CatalogUpdateNotice, UUID> {

    Optional<CatalogUpdateNotice> findByUserIdAndDismissedAtIsNull(UUID userId);
}
