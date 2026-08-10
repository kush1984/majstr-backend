package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogUpdateNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogUpdateNoticeRepository extends JpaRepository<CatalogUpdateNotice, UUID> {

    /** Every pending notice for a master, oldest first — the queue the app-open banner reads. */
    List<CatalogUpdateNotice> findByUserIdAndDismissedAtIsNullOrderByCreatedAtAsc(UUID userId);

    /** Owner-scoped lookup for dismiss/accept — a foreign or missing id resolves to an empty
     *  Optional rather than an error, so both actions stay idempotent (see the service). */
    Optional<CatalogUpdateNotice> findByIdAndUserId(UUID id, UUID userId);
}
