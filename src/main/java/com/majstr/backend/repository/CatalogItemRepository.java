package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CatalogItemRepository extends JpaRepository<CatalogItem, UUID> {

    List<CatalogItem> findByOwnerIdOrderByNameAsc(UUID ownerId);

    List<CatalogItem> findByOwnerIdAndTypeOrderByNameAsc(UUID ownerId, ItemType type);

    /** Distinct, non-empty categories for a contractor — feeds the category picker. */
    @Query("""
            SELECT DISTINCT c.category FROM CatalogItem c
            WHERE c.owner.id = :ownerId AND c.category IS NOT NULL
            ORDER BY c.category ASC
            """)
    List<String> findDistinctCategoriesByOwner(@Param("ownerId") UUID ownerId);
}
