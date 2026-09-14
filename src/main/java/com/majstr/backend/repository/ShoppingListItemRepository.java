package com.majstr.backend.repository;

import com.majstr.backend.entity.ShoppingListItem;
import com.majstr.backend.entity.ShoppingListItemSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, UUID> {

    /** Everything on the list, cleared rows included — the caller decides what to show. */
    List<ShoppingListItem> findByShoppingListIdOrderBySortOrderAscCreatedAtAsc(UUID shoppingListId);

    /**
     * Every row one CONTRIBUTION ever produced — settled rows included. A recalculation needs the
     * settled ones to know what is already covered; it just never writes to them. The source is part
     * of the filter because rows another source put on the same estimate are not its to rewrite.
     */
    List<ShoppingListItem> findByShoppingListIdAndSourceAndSourceEstimateId(
            UUID shoppingListId, ShoppingListItemSource source, UUID sourceEstimateId);

    /** Manual rows carry no estimate, so they are their own contribution. */
    List<ShoppingListItem> findByShoppingListIdAndSourceAndSourceEstimateIdIsNull(
            UUID shoppingListId, ShoppingListItemSource source);

    Optional<ShoppingListItem> findByIdAndShoppingListId(UUID id, UUID shoppingListId);

    List<ShoppingListItem> findByShoppingListIdAndBoughtTrueAndClearedAtIsNull(UUID shoppingListId);

    @Query("SELECT COALESCE(MAX(i.sortOrder), 0) FROM ShoppingListItem i WHERE i.shoppingListId = :listId")
    int maxSortOrder(@Param("listId") UUID listId);

    /**
     * The rows one estimate produced that the master never touched — dropped BEFORE the estimate
     * goes. {@code source_estimate_id} is {@code ON DELETE SET NULL}, and Postgres executes a SET
     * NULL as an UPDATE, so it is checked against {@code shopping_list_item_calculated_source_check}
     * — a CALCULATOR row with no estimate fails it and the whole delete 500s. An untouched row is
     * also worth nothing once its source is gone: nobody bought it and nobody corrected it.
     */
    @Modifying
    @Query("""
            DELETE FROM ShoppingListItem i
            WHERE i.sourceEstimateId = :estimateId
              AND i.bought = false AND i.clearedAt IS NULL AND i.edited = false
            """)
    int deleteUntouchedByEstimate(@Param("estimateId") UUID estimateId);

    /**
     * What survives the delete above — a bought, cleared or hand-corrected row — becomes the
     * master's own MANUAL row. It cannot merely lose its {@code sourceEstimateId}: the CHECK named
     * above forbids a CALCULATOR row without one, and {@code ux_shopping_list_item_open} is
     * {@code NULLS NOT DISTINCT}, so two orphaned rows for the same material would collide on the
     * next estimate delete instead. MANUAL is also what such a row now IS — nothing can recalculate
     * it any more, and what he bought stays on his list.
     *
     * <p>A bulk update for the same reason {@code ShoppingListRepository.updateArchivedAt} is one:
     * the caller must not depend on {@code ShoppingListService}, and the statement has to reach the
     * database before the estimate's own delete is flushed.</p>
     */
    @Modifying
    @Query("""
            UPDATE ShoppingListItem i
            SET i.source = com.majstr.backend.entity.ShoppingListItemSource.MANUAL,
                i.sourceEstimateId = NULL,
                i.estimateItemId = NULL,
                i.suggestedQuantity = NULL,
                i.updatedAt = CURRENT_TIMESTAMP
            WHERE i.sourceEstimateId = :estimateId
            """)
    int detachFromEstimate(@Param("estimateId") UUID estimateId);

    /**
     * Every shopping row of one object, dropped before the object itself. The rows hang off the list
     * (CASCADE) and off {@code estimates} (SET NULL), which are two SIBLING branches of the same
     * project cascade — Postgres does not define which fires first, so the SET NULL can reach a row
     * whose list is still there and fail the CALCULATOR check. The object is going anyway, so there
     * is nothing here to keep.
     */
    @Modifying
    @Query("""
            DELETE FROM ShoppingListItem i
            WHERE i.shoppingListId IN (SELECT l.id FROM ShoppingList l WHERE l.projectId = :projectId)
            """)
    int deleteByProjectId(@Param("projectId") UUID projectId);

    /**
     * Delete one row as a bulk statement, on purpose: Hibernate flushes entity UPDATEs before
     * entity DELETEs, so removing a merged sibling with {@code delete(entity)} would let the
     * surviving row's «not bought after all» update run first and violate
     * {@code ux_shopping_list_item_open}. See {@code ShoppingListService#mergeOpenSibling}.
     */
    @Modifying
    @Query("DELETE FROM ShoppingListItem i WHERE i.id = :id")
    int deleteRow(@Param("id") UUID id);
}
