package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogInsightDismissal;
import com.majstr.backend.entity.CatalogInsightKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CatalogInsightDismissalRepository extends JpaRepository<CatalogInsightDismissal, UUID> {

    List<CatalogInsightDismissal> findByKind(CatalogInsightKind kind);

    Optional<CatalogInsightDismissal> findByKindAndNameKey(CatalogInsightKind kind, String nameKey);
}
