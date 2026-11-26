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

    /** Every line of several estimates at once — one query where a loop would be N+1. Used by the
     *  economy to rebuild each marked-up copy at the crew's prices. */
    List<EstimateItem> findByEstimateIdInOrderBySortOrderAscIdAsc(Collection<UUID> estimateIds);

    /**
     * The lines a catalog comment reaches (V207): this master's, in estimates the client has not signed,
     * the same position by type + unit + folded name, and never one whose comment he changed in place.
     */
    @Query("""
            SELECT i FROM EstimateItem i JOIN FETCH i.estimate e
            WHERE e.project.owner.id = :ownerId
              AND e.status <> com.majstr.backend.entity.EstimateStatus.SIGNED
              AND i.type = :type AND i.unit = :unit
              AND lower(trim(i.name)) IN :nameKeys
              AND i.noteOwn = false
            """)
    List<EstimateItem> findCatalogNoteTargets(UUID ownerId, com.majstr.backend.entity.ItemType type,
                                              com.majstr.backend.entity.Unit unit, Collection<String> nameKeys);

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
            JOIN estimates e ON e.id = ei.estimate_id
            JOIN projects p ON p.id = e.project_id
            WHERE NOT p.sample
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
     *
     * <p>The same rows feed the oblast medians (V222): one query, so the two levels can never
     * disagree about what a master's price is.
     *
     * <p><b>His catalog counts too (V225, owner 2026-11-26)</b> — most masters send a PDF and never
     * have it signed, and many state their rates in the catalog or import a price list. A catalog row
     * joins his lines as one more price, but only when {@code own_price} (typed, edited or imported):
     * a library price he never touched is OUR number, and counting it would make every median ours.
     *
     * <p><b>Six months of estimates (owner, 2026-11-26)</b> — prices rise; a line from two years ago is
     * not today's price. Counted from when the estimate was MADE (a typo fixed in an old one does not
     * bring its prices back). His catalog price has no window: it is what he charges now.
     *
     * <p><b>No markup copies (owner, 2026-11-26)</b> — a copy made «з націнкою» or «зі знижкою» is one
     * master's earning on top of the base price: +15 % today, +30 % tomorrow, −5 % for a friend. Counted,
     * every such copy would push the market up for everyone, the crews included. The base prices — the
     * original estimate and the catalog — count; the copy ({@code crew_priced} or a non-zero
     * {@code markup_percent}, a discount too) does not. A markup typed by hand into the lines leaves no
     * trace and cannot be told apart (open-questions, «An in-place markup leaves no trace»).
     */
    @Query(value = """
            SELECT x.owner_id                                                  AS masterId,
                   lower(trim(x.name))                                         AS rawKey,
                   MIN(x.name)                                                 AS name,
                   MIN(x.category)                                             AS category,
                   x.type                                                      AS type,
                   x.unit                                                      AS unit,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY x.price)         AS perMasterMedianPrice,
                   MIN(x.seen_at)                                              AS firstSeen,
                   MIN(u.region)                                               AS region
            FROM (SELECT p.owner_id, ei.name, ei.category, ei.type, ei.unit,
                         ei.unit_price AS price, e.created_at AS seen_at
                  FROM estimate_items ei
                  JOIN estimates e ON e.id = ei.estimate_id
                  JOIN projects p ON p.id = e.project_id
                  WHERE e.status <> 'REJECTED'
                    AND e.created_at >= now() - interval '6 months'
                    AND NOT e.crew_priced
                    AND coalesce(e.markup_percent, 0) = 0
                    AND NOT p.sample
                    AND ei.type = 'WORK'
                    AND ei.unit <> 'PERCENT'
                    AND ei.unit_price > 0
                  UNION ALL
                  SELECT ci.owner_id, ci.name, ci.category, ci.type, ci.unit,
                         ci.default_price, ci.created_at
                  FROM catalog_items ci
                  WHERE ci.own_price
                    AND ci.type = 'WORK'
                    AND ci.unit <> 'PERCENT'
                    AND ci.default_price > 0) x
            JOIN users u ON u.id = x.owner_id
            GROUP BY x.owner_id, lower(trim(x.name)), x.type, x.unit
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
        /** The master's oblast (V219), null when he has not said — {@code RegionalPriceService}
         *  splits by it; the national median ignores it. One per master, so MIN is just a pick. */
        String getRegion();
    }
}
