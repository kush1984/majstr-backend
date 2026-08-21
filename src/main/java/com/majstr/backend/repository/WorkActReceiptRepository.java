package com.majstr.backend.repository;

import com.majstr.backend.entity.WorkActReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkActReceiptRepository extends JpaRepository<WorkActReceipt, UUID> {

    List<WorkActReceipt> findByWorkActIdOrderBySortOrderAscCreatedAtAsc(UUID workActId);

    Optional<WorkActReceipt> findByIdAndWorkActId(UUID id, UUID workActId);

    @Query("SELECT COALESCE(MAX(r.sortOrder), -1) FROM WorkActReceipt r WHERE r.workAct.id = :actId")
    int maxSortOrder(@Param("actId") UUID actId);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM WorkActReceipt r WHERE r.workAct.id = :actId")
    BigDecimal sumByWorkActId(@Param("actId") UUID actId);

    /**
     * Σ receipt amounts over the object's SIGNED acts — the receipts half of «Прийнято актами».
     *
     * <p><b>Pairs with {@code WorkActItemRepository.sumSignedActLineTotals}: both must be added
     * wherever «Прийнято актами» is computed.</b> Signing rolls the receipts into the act's SIGNED
     * ADDENDUM estimate ({@code count_in_economy = true}), so they are inside «За договором»; leaving
     * them out here would make the numerator smaller than the money the client actually owes and the
     * works axis would read a phantom «Невідпрацьований аванс» the moment the act is paid.</p>
     */
    @Query(value = """
            SELECT COALESCE(SUM(r.amount), 0)
            FROM work_act_receipt r
            JOIN work_act wa ON wa.id = r.work_act_id
            WHERE wa.project_id = :projectId
              AND wa.status = 'SIGNED'
            """, nativeQuery = true)
    BigDecimal sumSignedActReceipts(@Param("projectId") UUID projectId);
}
