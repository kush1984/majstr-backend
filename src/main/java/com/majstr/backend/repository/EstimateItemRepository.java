package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateItemRepository extends JpaRepository<EstimateItem, UUID> {

    List<EstimateItem> findByEstimateIdOrderBySortOrderAscIdAsc(UUID estimateId);

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
}
