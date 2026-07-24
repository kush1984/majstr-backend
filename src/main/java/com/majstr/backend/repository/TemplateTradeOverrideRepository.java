package com.majstr.backend.repository;

import com.majstr.backend.entity.TemplateTradeOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateTradeOverrideRepository
        extends JpaRepository<TemplateTradeOverride, TemplateTradeOverride.Key> {

    /** All of one master's re-filings — one query, applied over the listed templates. */
    List<TemplateTradeOverride> findByUserId(UUID userId);

    Optional<TemplateTradeOverride> findByUserIdAndTemplateId(UUID userId, UUID templateId);
}
