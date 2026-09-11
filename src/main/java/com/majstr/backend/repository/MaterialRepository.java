package com.majstr.backend.repository;

import com.majstr.backend.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MaterialRepository extends JpaRepository<Material, UUID> {

    /** {@code spec} is nullable, so this is a JPQL {@code IS NULL}-safe lookup done by the caller. */
    Optional<Material> findByNameAndSpec(String name, String spec);

    Optional<Material> findByNameAndSpecIsNull(String name);

    List<Material> findAllByOrderByNameAsc();
}
