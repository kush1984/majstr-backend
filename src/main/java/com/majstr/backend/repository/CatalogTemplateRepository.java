package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CatalogTemplateRepository extends JpaRepository<CatalogTemplate, UUID> {

    List<CatalogTemplate> findByTrade(Trade trade);
}
