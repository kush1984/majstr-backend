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
 * A norm is keyed by NAME and UNIT, and the trade is a filter on the answer, not part of the key.
 *
 * <p>There is no foreign key to hang a norm off: {@code catalog_items} does not point at
 * {@code catalog_templates}, and every catalog rebuild (V82, V116, V122) deletes and recreates the
 * templates — so the key is (nameKey, unit), and {@link #findByKey} is what the name-keying itself
 * means. {@link #findByTradeAndKey} narrows the same key to one trade.</p>
 *
 * <p><b>Which norms may answer for a position is decided in the SERVICE, not here</b> — see
 * {@code MaterialCalculatorService#normsFor}. An earlier draft asked this repository for the
 * position's trade and, on a miss, took whatever trade had filed the norm; that put a painter's
 * шпаклівка into a drywall estimate («оце все з малярки не має взагалі попадати», master's ruling,
 * 2026-09-11). The rule now is the position's trade, or no trade on the norm at all.</p>
 *
 * <p>Both queries above return default norms ({@code owner IS NULL}) only and exist to pin the key's
 * shape. The engine reads through {@link #findAllByNameKeysForOwner}, which loads the master's own
 * norms alongside the defaults; the fork hides the default it was copied from in Java, under the
 * natural key, rather than in a query here.</p>
 */
@Repository
public interface MaterialNormRepository extends JpaRepository<MaterialNorm, UUID> {

    /** The key narrowed to one trade. Used to pin the shape; the engine filters in Java. */
    @Query("""
            SELECT n FROM MaterialNorm n
            WHERE n.owner IS NULL AND n.trade = :trade AND n.nameKey = :nameKey AND n.unit = :unit
            ORDER BY n.sortOrder ASC
            """)
    List<MaterialNorm> findByTradeAndKey(@Param("trade") Trade trade,
                                         @Param("nameKey") String nameKey,
                                         @Param("unit") Unit unit);

    /** The key itself — name and unit, whatever trade filed the norm. NOT an answer on its own. */
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
