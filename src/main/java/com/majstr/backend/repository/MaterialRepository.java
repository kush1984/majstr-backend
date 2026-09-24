package com.majstr.backend.repository;

import com.majstr.backend.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Reads are by id only: a material is reached through a norm, or through a line the master already
 * picked. The by-name and list-all finders went with review B-31 — nothing called them, and a dead
 * finder reads as a lookup someone meant to use.
 */
@Repository
public interface MaterialRepository extends JpaRepository<Material, UUID> {
}
