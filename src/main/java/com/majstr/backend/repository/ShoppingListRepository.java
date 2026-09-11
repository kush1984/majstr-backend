package com.majstr.backend.repository;

import com.majstr.backend.entity.ShoppingList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShoppingListRepository extends JpaRepository<ShoppingList, UUID> {

    Optional<ShoppingList> findByProjectId(UUID projectId);

    /**
     * Archive/unarchive from the object-status hook. A bulk update rather than a service call so
     * {@code ProjectService} does not have to depend on {@code ShoppingListService}, which depends
     * on it back.
     */
    @Modifying
    @Query("UPDATE ShoppingList l SET l.archivedAt = :archivedAt, l.updatedAt = CURRENT_TIMESTAMP WHERE l.projectId = :projectId")
    int updateArchivedAt(@Param("projectId") UUID projectId, @Param("archivedAt") Instant archivedAt);

    /** One grouped query for the home-screen card — never one per object. */
    @Query("""
            SELECT p.id AS projectId, p.name AS projectName,
                   COUNT(i) AS totalCount,
                   SUM(CASE WHEN i.bought = true THEN 1L ELSE 0L END) AS boughtCount
            FROM ShoppingList l
            JOIN Project p ON p.id = l.projectId
            JOIN ShoppingListItem i ON i.shoppingListId = l.id AND i.clearedAt IS NULL
            WHERE p.owner.id = :ownerId AND l.archivedAt IS NULL
            GROUP BY p.id, p.name
            ORDER BY p.name ASC
            """)
    List<ShoppingListSummaryRow> summaryRows(@Param("ownerId") UUID ownerId);

    interface ShoppingListSummaryRow {
        UUID getProjectId();
        String getProjectName();
        long getTotalCount();
        long getBoughtCount();
    }
}
