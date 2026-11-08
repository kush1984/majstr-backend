package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateTemplate;
import com.majstr.backend.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateTemplateRepository extends JpaRepository<EstimateTemplate, UUID> {

    /** System defaults relevant to the master: any of their trades, plus the
     *  trade-agnostic (general) ones. Owner-independent — defaults are shared. */
    @Query("""
            SELECT t FROM EstimateTemplate t
            WHERE t.isDefault = true AND (t.trade IS NULL OR t.trade IN :trades)
            ORDER BY t.trade, t.name
            """)
    List<EstimateTemplate> findDefaultsForTrades(@Param("trades") Collection<Trade> trades);

    /** A master's own saved templates, newest first. */
    List<EstimateTemplate> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    /** All system defaults — for the admin editor. Explicit JPQL (like
     *  {@link #findDefaultsForTrades}) rather than a derived name, to avoid the
     *  boolean {@code isDefault} property-parsing gotcha. Trade groups them; nulls
     *  (general) sort last under Postgres' default NULLS LAST for ASC. */
    @Query("SELECT t FROM EstimateTemplate t WHERE t.isDefault = true ORDER BY t.trade, t.name")
    List<EstimateTemplate> findAllDefaults();
}
