package com.majstr.backend.repository;

import com.majstr.backend.entity.MaterialNormAlias;
import com.majstr.backend.entity.Unit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialNormAliasRepository extends JpaRepository<MaterialNormAlias, UUID> {

    List<MaterialNormAlias> findByOwnerId(UUID ownerId);

    Optional<MaterialNormAlias> findByOwnerIdAndNameKeyAndUnit(UUID ownerId, String nameKey, Unit unit);

    /** The owner's row lock for a save: two first answers to one wording would both insert. */
    @Query(value = "SELECT id FROM users WHERE id = :ownerId FOR UPDATE", nativeQuery = true)
    UUID lockOwner(@Param("ownerId") UUID ownerId);

    @Modifying
    @Query("DELETE FROM MaterialNormAlias a WHERE a.ownerId = :ownerId AND a.nameKey = :nameKey AND a.unit = :unit")
    int deleteOne(@Param("ownerId") UUID ownerId, @Param("nameKey") String nameKey, @Param("unit") Unit unit);
}
