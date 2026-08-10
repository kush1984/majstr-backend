package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateItemRepository extends JpaRepository<EstimateItem, UUID> {

    List<EstimateItem> findByEstimateIdOrderBySortOrderAscIdAsc(UUID estimateId);

    /** The lines of a duplicate copied from the given parent lines — the cascade delete reads this
     *  to find each removed position's twin in the client-price copy. */
    List<EstimateItem> findByEstimateIdAndSourceItemIdIn(UUID estimateId, Collection<UUID> sourceItemIds);

    /**
     * Every distinct line name that has ever been written into an estimate, with how often.
     * Feeds the admin's "which of our default positions does nobody use" screen.
     *
     * <p><b>This can only ever be approximate, and the reason is deliberate design.</b>
     * {@code estimate_items} are snapshots — own name, unit and price copied when the line was
     * added, with NO foreign key to {@code catalog_items} — precisely so an estimate keeps the
     * figures it was signed with. The cost of that guarantee is exactly this: usage can only be
     * matched back by NAME. A master who renamed a line after adding it counts as a different
     * position here.
     *
     * <p>Good enough for "nobody has ever typed anything like this" (which is what the screen
     * asks), never good enough to publish as a usage statistic. Don't turn this into one.
     */
    @Query(value = """
            SELECT lower(ei.name) AS name, COUNT(*) AS uses
            FROM estimate_items ei
            GROUP BY lower(ei.name)
            """, nativeQuery = true)
    List<UsedNameRow> aggregateUsedNames();

    /** Projection for {@link #aggregateUsedNames()}. */
    interface UsedNameRow {
        String getName();
        long getUses();
    }

    /**
     * Step 1 of the community-price median (see {@code PriceInsightService}): one row per
     * (master, exact lowercased line name, type, unit), with that master's OWN median price
     * across every non-rejected WORK line he ever wrote under that exact spelling.
     *
     * <p><b>Why per-master first.</b> A single master can carry many draft/duplicate estimates
     * with the same line — without this step his 30 near-identical rows would out-vote 2 other
     * masters' 1 row each, both diluting the median toward his number and inflating the apparent
     * "how many masters agree" count. This reduces him to exactly one number before anything is
     * compared across masters.
     *
     * <p><b>SQL, not in-memory, on purpose.</b> {@code estimate_items} is the biggest table in the
     * schema. Postgres computes the percentile over however many raw rows exist; only the
     * resulting (master × exact spelling) summary — orders of magnitude smaller — reaches the
     * JVM. The fuzzy {@code CatalogNameKey} fold across spellings, the outlier trim, and the
     * across-master median all happen there, on that already-small result, for the same reason
     * {@code CatalogItemRepository#aggregateMasterPositions} keeps the normalisation rule out of
     * SQL: one copy of {@code CatalogNameKey}, not two.
     *
     * <p>Filters: not REJECTED (a rejected estimate's prices were never agreed to), WORK only
     * (materials price from receipts, not this), not PERCENT (a percentage is not a price), a
     * positive price (an unpriced draft line is not a quote).
     */
    @Query(value = """
            SELECT p.owner_id                                                  AS masterId,
                   lower(trim(ei.name))                                        AS rawKey,
                   MIN(ei.name)                                                AS name,
                   MIN(ei.category)                                            AS category,
                   ei.type                                                     AS type,
                   ei.unit                                                     AS unit,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY ei.unit_price)   AS perMasterMedianPrice,
                   MIN(e.created_at)                                           AS firstSeen
            FROM estimate_items ei
            JOIN estimates e ON e.id = ei.estimate_id
            JOIN projects p ON p.id = e.project_id
            WHERE e.status <> 'REJECTED'
              AND ei.type = 'WORK'
              AND ei.unit <> 'PERCENT'
              AND ei.unit_price > 0
            GROUP BY p.owner_id, lower(trim(ei.name)), ei.type, ei.unit
            """, nativeQuery = true)
    List<PerMasterPriceRow> aggregatePerMasterWorkPrices();

    /** Projection for {@link #aggregatePerMasterWorkPrices()}. */
    interface PerMasterPriceRow {
        java.util.UUID getMasterId();
        String getRawKey();
        String getName();
        String getCategory();
        String getType();
        String getUnit();
        java.math.BigDecimal getPerMasterMedianPrice();
        java.time.Instant getFirstSeen();
    }
}
