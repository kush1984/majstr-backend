package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import org.springframework.data.domain.Pageable;
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

    /** Catalog size for one owner — admin user detail ("did they fill their catalog?"). */
    long countByOwnerId(UUID ownerId);

    /** Distinct, non-empty categories for a contractor — feeds the category picker. */
    @Query("""
            SELECT DISTINCT c.category FROM CatalogItem c
            WHERE c.owner.id = :ownerId AND c.category IS NOT NULL
            ORDER BY c.category ASC
            """)
    List<String> findDistinctCategoriesByOwner(@Param("ownerId") UUID ownerId);

    /**
     * Autocomplete search over the owner's catalog by partial name
     * (case-insensitive), with an optional type filter. Exact-prefix matches
     * rank first, then alphabetical. {@code Pageable} caps the result count.
     *
     * <p>{@code pattern} ({@code %term%}) and {@code prefix} ({@code term%}) are
     * built in Java (see {@code CatalogService}) and bound as plain text LIKE
     * operands — never an untyped parameter inside {@code LOWER(CONCAT(...))},
     * which is what produced the {@code lower(bytea)} failure (Fix K).</p>
     */
    @Query("""
            SELECT c FROM CatalogItem c
            WHERE c.owner.id = :ownerId
              AND (:type IS NULL OR c.type = :type)
              AND LOWER(c.name) LIKE :pattern
            ORDER BY CASE WHEN LOWER(c.name) LIKE :prefix THEN 0 ELSE 1 END, LOWER(c.name) ASC
            """)
    List<CatalogItem> searchByOwner(@Param("ownerId") UUID ownerId,
                                    @Param("type") ItemType type,
                                    @Param("pattern") String pattern,
                                    @Param("prefix") String prefix,
                                    Pageable pageable);
}
