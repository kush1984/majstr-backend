package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateTemplateItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateTemplateItemRepository extends JpaRepository<EstimateTemplateItem, UUID> {

    List<EstimateTemplateItem> findByTemplateIdOrderBySortOrderAscIdAsc(UUID templateId);

    /** Item count per template for the picker's "N позицій" hint — one grouped
     *  query folded into the summaries (no N+1). `cnt`, not `count` (reserved). */
    @Query("""
            SELECT i.template.id AS templateId, COUNT(i) AS cnt
            FROM EstimateTemplateItem i
            WHERE i.template.id IN :templateIds
            GROUP BY i.template.id
            """)
    List<TemplateItemCount> countByTemplateIds(@Param("templateIds") Collection<UUID> templateIds);

    interface TemplateItemCount {
        UUID getTemplateId();
        long getCnt();
    }
}
