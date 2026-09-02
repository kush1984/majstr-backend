package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Repository
public interface CatalogTemplateRepository extends JpaRepository<CatalogTemplate, UUID> {

    /** Templates for any of the user's trades — merged set for generalists, in the library's own
     *  order (V118), which is the order the copies are numbered in. */
    List<CatalogTemplate> findByTradeInOrderBySortOrderAsc(Collection<Trade> trades);

    /** Templates for the user's trades added AFTER the version they last synced —
     *  what "Add new from catalog" offers (never older versions → never re-adds
     *  what the master deleted/renamed). */
    @Query("""
            SELECT t FROM CatalogTemplate t
            WHERE t.trade IN :trades AND t.addedInVersion > :sinceVersion
            ORDER BY t.sortOrder
            """)
    List<CatalogTemplate> findByTradeInAndAddedInVersionGreaterThan(@Param("trades") Collection<Trade> trades,
                                                                    @Param("sinceVersion") int sinceVersion);

    /** Current catalog version = the highest version any template was added in (≥ 1). */
    @Query("SELECT COALESCE(MAX(t.addedInVersion), 1) FROM CatalogTemplate t")
    int currentVersion();

    /**
     * Every (normalized name, type, unit) key more than one trade recognizes — one row per sharing
     * trade, carrying THAT trade's category. {@code catalog_items} has one row per (owner, name,
     * type, unit) — the unique index has no room for a second row under the same name — so a master
     * running two trades that happen to share identical wording (V99: PAINTER's
     * organizational-services category duplicates several of TILING's) only ever owns ONE row,
     * tagged whichever trade claimed it first. {@link com.majstr.backend.service.CatalogService}
     * uses this to tell the client's trade filter that such a row is legitimately shared, not just
     * filed wrong.
     *
     * <p>The category travels with the trade because the two can legitimately disagree: V116 copied
     * ten PAINTER/DEMOLITION positions VERBATIM into DRYWALL phases, so «Поклейка склополотна» is
     * PAINTER's «Шпалери» and DRYWALL's «Оздоблення під фарбування» at the same time. Showing the
     * stored one under the other trade's chip is what put a Шпалери folder on a drywall screen.</p>
     */
    @Query(value = """
            SELECT lower(trim(name)) AS name_key, type, unit, trade, min(category) AS category
            FROM catalog_templates
            WHERE (lower(trim(name)), type, unit) IN (
                SELECT lower(trim(name)), type, unit
                FROM catalog_templates
                GROUP BY lower(trim(name)), type, unit
                HAVING count(DISTINCT trade) > 1)
            GROUP BY lower(trim(name)), type, unit, trade
            ORDER BY 1, 2, 3, 4
            """, nativeQuery = true)
    List<Object[]> findNameKeysSharedAcrossTrades();

    /**
     * Where each (trade, category) sits in that trade's own sequence — {@code min(sort_order)} over
     * V118's global ranking, which for DRYWALL is the order the work is actually done in
     * (Підготовка та захист → Каркас і обшивка → Звукоізоляція та утеплення → Оздоблення під
     * фарбування → Надбавки).
     *
     * <p>The client cannot derive this. {@code catalog_items.sort_order} is ONE rank per row, taken
     * from the trade the row is STORED under, while a row re-filed for display belongs to another
     * trade's phase — so the drywall chip opened on «Каркас і обшивка» because one plumbing row
     * («Установка люка-ревізії простого», copied into that phase by V120) outranked every drywall
     * row on the board. Ordering the SECTIONS by this key, and leaving the rows inside a section in
     * the master's own order, is what puts the phases back in sequence.</p>
     */
    @Query(value = """
            SELECT trade, category, min(sort_order)
            FROM catalog_templates
            WHERE category IS NOT NULL
            GROUP BY trade, category
            """, nativeQuery = true)
    List<Object[]> findCategoryRanks();

    /** Admin editor: optional trade + type filters + optional name substring. The
     *  LIKE pattern is built in Java (see {@link #likePattern}) and compared against
     *  {@code LOWER(name)} — keeping the bind a plain text operand, so Postgres can
     *  infer its type (the CONCAT/LOWER-around-the-param form 500'd as
     *  {@code lower(bytea)}; see {@code UserRepository.searchAdmin}). */
    default Page<CatalogTemplate> adminSearch(Trade trade, ItemType type, String q, Pageable pageable) {
        return adminSearchByPattern(trade, type, likePattern(q), pageable);
    }

    static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
    }

    @Query("""
            SELECT t FROM CatalogTemplate t
            WHERE (:trade IS NULL OR t.trade = :trade)
              AND (:type IS NULL OR t.type = :type)
              AND (:pattern IS NULL OR LOWER(t.name) LIKE :pattern)
            ORDER BY t.trade, t.category, t.name
            """)
    Page<CatalogTemplate> adminSearchByPattern(@Param("trade") Trade trade,
                                               @Param("type") ItemType type,
                                               @Param("pattern") String pattern,
                                               Pageable pageable);
}
