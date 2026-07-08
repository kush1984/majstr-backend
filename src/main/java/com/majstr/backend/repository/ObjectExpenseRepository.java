package com.majstr.backend.repository;

import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ObjectExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ObjectExpenseRepository extends JpaRepository<ObjectExpense, UUID> {

    /** Journal for one object, newest spend first. */
    List<ObjectExpense> findByObjectIdOrderBySpentAtDescCreatedAtDesc(UUID objectId);

    /** Owner-scoped load for edit/delete — the service also checks the object is owned. */
    Optional<ObjectExpense> findByIdAndObjectId(UUID id, UUID objectId);

    /** Expense totals grouped by category for the economy breakdown — one query. */
    @Query("""
            SELECT e.category AS category, COALESCE(SUM(e.amount), 0) AS total
            FROM ObjectExpense e WHERE e.objectId = :objectId GROUP BY e.category
            """)
    List<CategoryTotal> sumByCategory(@Param("objectId") UUID objectId);

    interface CategoryTotal {
        ExpenseCategory getCategory();
        java.math.BigDecimal getTotal();
    }
}
