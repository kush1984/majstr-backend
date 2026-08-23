package com.majstr.backend.repository;

import com.majstr.backend.entity.TemplateDefaultOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateDefaultOverrideRepository
        extends JpaRepository<TemplateDefaultOverride, TemplateDefaultOverride.Key> {

    /** Everything this master has hidden or forked — one query, applied over the listed defaults. */
    List<TemplateDefaultOverride> findByUserId(UUID userId);

    Optional<TemplateDefaultOverride> findByUserIdAndTemplateId(UUID userId, UUID templateId);

    void deleteByUserId(UUID userId);
}
