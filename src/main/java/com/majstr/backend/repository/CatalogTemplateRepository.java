package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CatalogTemplateRepository extends JpaRepository<CatalogTemplate, UUID> {

    /** Templates for any of the user's trades — merged set for generalists. */
    List<CatalogTemplate> findByTradeIn(Collection<Trade> trades);

    /** Templates for the user's trades added AFTER the version they last synced —
     *  what "Add new from catalog" offers (never older versions → never re-adds
     *  what the master deleted/renamed). */
    @Query("SELECT t FROM CatalogTemplate t WHERE t.trade IN :trades AND t.addedInVersion > :sinceVersion")
    List<CatalogTemplate> findByTradeInAndAddedInVersionGreaterThan(@Param("trades") Collection<Trade> trades,
                                                                    @Param("sinceVersion") int sinceVersion);

    /** Current catalog version = the highest version any template was added in (≥ 1). */
    @Query("SELECT COALESCE(MAX(t.addedInVersion), 1) FROM CatalogTemplate t")
    int currentVersion();
}
