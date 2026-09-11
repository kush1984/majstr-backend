package com.majstr.backend.repository;

import com.majstr.backend.entity.MaterialNorm;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The norm lookup is a LADDER, and the second rung carries the load.
 *
 * <p>{@code estimate_items.trade} is nullable by design (V125 — ADDENDUM and hand-typed lines), and
 * V118 stores a position two trades both ship exactly once, under whichever trade claimed it first.
 * A lookup keyed on the trade therefore misses silently on lines that are perfectly ordinary. The
 * engine asks {@link #findByTradeAndKey} first, so a trade-specific norm can override a general
 * one, and falls back to {@link #findByKey}. Never collapse the ladder into the first rung.</p>
 *
 * <p>The two rung queries return default norms ({@code owner IS NULL}) only and exist to pin the
 * ladder's shape. The engine reads through {@link #findAllByNameKeysForOwner}, which loads the
 * master's own norms alongside the defaults; the fork hides the default it was copied from in
 * Java, under the natural key, rather than in a third rung here.</p>
 */
@Repository
public interface MaterialNormRepository extends JpaRepository<MaterialNorm, UUID> {

    /** Rung 1 — the position's own trade, when it has one. */
    @Query("""
            SELECT n FROM MaterialNorm n
            WHERE n.owner IS NULL AND n.trade = :trade AND n.nameKey = :nameKey AND n.unit = :unit
            ORDER BY n.sortOrder ASC
            """)
    List<MaterialNorm> findByTradeAndKey(@Param("trade") Trade trade,
                                         @Param("nameKey") String nameKey,
                                         @Param("unit") Unit unit);

    /** Rung 2 — name and unit alone, whatever trade filed the norm. */
    @Query("""
            SELECT n FROM MaterialNorm n
            WHERE n.owner IS NULL AND n.nameKey = :nameKey AND n.unit = :unit
            ORDER BY n.sortOrder ASC
            """)
    List<MaterialNorm> findByKey(@Param("nameKey") String nameKey, @Param("unit") Unit unit);

    /**
     * Bulk pre-load for a whole estimate — one query instead of one per line. The fetch is LEFT
     * because a norm may deliberately carry no material («checked, consumes nothing», V127), and an
     * inner join would drop exactly the rows that keep those positions out of the coverage report.
     *
     * <p>It returns the defaults AND this master's own norms together, because which of the two
     * wins is decided per natural key, not per query: a master who has corrected one coefficient
     * still gets every other norm from the shipped set.</p>
     */
    @Query("""
            SELECT n FROM MaterialNorm n LEFT JOIN FETCH n.material
            WHERE (n.owner IS NULL OR n.owner.id = :ownerId) AND n.nameKey IN :nameKeys
            ORDER BY n.sortOrder ASC
            """)
    List<MaterialNorm> findAllByNameKeysForOwner(@Param("nameKeys") List<String> nameKeys,
                                                 @Param("ownerId") UUID ownerId);

    /**
     * The master's own norms for one position. The trade and the material are matched in Java: the
     * trade is nullable, and a JPQL predicate that has to spell «either both null or equal» for it
     * reads worse than the two-line filter it replaces.
     */
    List<MaterialNorm> findByOwnerIdAndNameKeyAndUnit(UUID ownerId, String nameKey, Unit unit);
}
