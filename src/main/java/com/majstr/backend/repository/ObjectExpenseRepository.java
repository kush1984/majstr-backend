package com.majstr.backend.repository;

import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ObjectExpenseRepository extends JpaRepository<ObjectExpense, UUID> {

    /** Journal for one object, newest spend first. */
    List<ObjectExpense> findByObjectIdOrderBySpentAtDescCreatedAtDesc(UUID objectId);

    /**
     * Every expense across ALL of one master's objects in a period (V135, «Мої гроші») — the first
     * owner-wide money query in this codebase; everything else here reads {@code WHERE object_id = ?}.
     *
     * <p>{@link ObjectExpense} carries a plain {@code objectId} and no association, so ownership is
     * a subquery over the master's projects rather than a join. Bounds are INCLUSIVE — «вересень»
     * ends on the 30th.</p>
     *
     * <p><b>A reimbursable till receipt is absent from this, and that is the point.</b> V129 ruled
     * that a receipt the client pays back is a receivable, not a cost, so it writes no row here —
     * which means the cash screen inherits that ruling for free by reading this and never
     * {@code project_receipt}. One definition of «витрата» in the whole app.</p>
     */
    @Query("""
            SELECT e FROM ObjectExpense e
            WHERE e.spentAt BETWEEN :from AND :to
              AND e.objectId IN (SELECT p.id FROM Project p WHERE p.owner.id = :ownerId)
            ORDER BY e.spentAt DESC, e.createdAt DESC
            """)
    List<ObjectExpense> findByOwnerAndPeriod(@Param("ownerId") UUID ownerId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    /** Owner-scoped load for edit/delete — the service also checks the object is owned. */
    Optional<ObjectExpense> findByIdAndObjectId(UUID id, UUID objectId);

    /** Expense totals grouped by category for the economy breakdown — one query. */
    @Query("""
            SELECT e.category AS category, COALESCE(SUM(e.amount), 0) AS total
            FROM ObjectExpense e WHERE e.objectId = :objectId GROUP BY e.category
            """)
    List<CategoryTotal> sumByCategory(@Param("objectId") UUID objectId);

    /** Total expenses of one source (RECEIPT = real material cost; MANUAL = unforeseen). */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ObjectExpense e WHERE e.objectId = :objectId AND e.source = :source")
    BigDecimal sumBySource(@Param("objectId") UUID objectId, @Param("source") ExpenseSource source);

    /** Everything spent on the object, regardless of source or category — the "Витрати" figure
     *  the simplified (economy-rework) profit model subtracts from the contracted total. */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ObjectExpense e WHERE e.objectId = :objectId")
    BigDecimal sumAll(@Param("objectId") UUID objectId);

    interface CategoryTotal {
        ExpenseCategory getCategory();
        java.math.BigDecimal getTotal();
    }
}
