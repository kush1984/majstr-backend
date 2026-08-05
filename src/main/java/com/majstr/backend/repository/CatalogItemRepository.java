package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CatalogItemRepository extends JpaRepository<CatalogItem, UUID> {

    /**
     * Every distinct position masters hold, aggregated across accounts — the raw material for
     * the admin catalog-insight screens.
     *
     * <p>Grouped only by <b>exact lowercased name + type + unit</b>. The real grouping is by
     * {@code CatalogNameKey} and happens in Java, deliberately: putting that rule in SQL would
     * be a second copy of it, and this project already pays the "change them in pairs" tax on
     * one mirrored formula. This step exists purely to cut tens of thousands of rows down to a
     * few thousand before they reach the JVM.
     *
     * <p><b>No master's price is exposed.</b> The projection carries a median and a range across
     * accounts — a master's own pricing is their commercial data, and the point here is to learn
     * what positions should exist, not what any one person charges. A WIDE range is itself the
     * useful signal: it means the position is ambiguously defined, not that the average is off.
     *
     * <p><b>{@code source <> 'LIBRARY'} is the whole point of the filter.</b> Without it the list
     * is dominated by positions WE handed out: everything the V70–V73 cleanup deleted from the
     * defaults is absent from {@code catalog_templates} too, so it reads as master-invented. In
     * production that surfaced as a position credited to 64 masters — nobody invents the same
     * thing 64 times independently; that is a seeding batch. Provenance is recorded at write
     * time (V79) precisely because it cannot be reconstructed afterwards.
     */
    @Query(value = """
            SELECT MIN(ci.name)                                                        AS name,
                   ci.type                                                             AS type,
                   ci.unit                                                             AS unit,
                   MIN(ci.category)                                                    AS category,
                   COUNT(DISTINCT ci.owner_id)                                         AS masters,
                   MIN(ci.created_at)                                                  AS firstSeen,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY ci.default_price)       AS medianPrice,
                   MIN(ci.default_price)                                               AS minPrice,
                   MAX(ci.default_price)                                               AS maxPrice
            FROM catalog_items ci
            WHERE ci.source <> 'LIBRARY'
            GROUP BY lower(ci.name), ci.type, ci.unit
            """, nativeQuery = true)
    List<MasterPositionRow> aggregateMasterPositions();

    /** Projection for {@link #aggregateMasterPositions()}. */
    interface MasterPositionRow {
        String getName();
        String getType();
        String getUnit();
        String getCategory();
        long getMasters();
        java.time.Instant getFirstSeen();
        java.math.BigDecimal getMedianPrice();
        java.math.BigDecimal getMinPrice();
        java.math.BigDecimal getMaxPrice();
    }

    List<CatalogItem> findByOwnerIdOrderByNameAsc(UUID ownerId);

    List<CatalogItem> findByOwnerIdAndTypeOrderByNameAsc(UUID ownerId, ItemType type);

    /** Catalog size for one owner — admin user detail ("did they fill their catalog?"). */
    long countByOwnerId(UUID ownerId);

    /**
     * Where a new position goes: one past the end of this owner's catalog.
     *
     * <p>{@code COALESCE(MAX + 1, 0)} rather than a count, because a count is wrong the moment
     * anything has been deleted — two positions would claim the same slot and the list order would
     * depend on the id tie-break rather than on the master.</p>
     */
    @Query("SELECT COALESCE(MAX(c.sortOrder) + 1, 0) FROM CatalogItem c WHERE c.owner.id = :ownerId")
    int nextSortOrder(UUID ownerId);

    /** The owner's whole catalog in HIS order — what the reorder and bulk-delete paths work over. */
    List<CatalogItem> findByOwnerIdOrderBySortOrderAscIdAsc(UUID ownerId);

    /** Bulk delete, scoped to the owner: an id belonging to someone else simply matches nothing. */
    @Modifying
    @Query("DELETE FROM CatalogItem c WHERE c.owner.id = :ownerId AND c.id IN :ids")
    int deleteByOwnerIdAndIdIn(UUID ownerId, Collection<UUID> ids);

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
