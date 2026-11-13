package com.majstr.backend.repository;

import com.majstr.backend.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads are by id, and by code for the one caller that starts from a format rather than a norm (the
 * tile layout). The by-name and list-all finders went with review B-31 — nothing called them, and a
 * dead finder reads as a lookup someone meant to use.
 */
@Repository
public interface MaterialRepository extends JpaRepository<Material, UUID> {

    /** A library material by its code — the tile layout's format material (V171, iteration-tile-layout). */
    Optional<Material> findByCode(String code);
}
