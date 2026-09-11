package com.majstr.backend.repository;

import com.majstr.backend.entity.ShoppingListItem;
import com.majstr.backend.entity.ShoppingListItemSource;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
