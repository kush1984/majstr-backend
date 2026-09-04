package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogItemSynonym;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CatalogItemSynonymRepository extends JpaRepository<CatalogItemSynonym, UUID> {

    /** All synonyms for one master — loaded once per parse and consulted before the Dice pass. */
    List<CatalogItemSynonym> findByOwnerId(UUID ownerId);

    /**
     * Delete the existing synonym for this owner + spoken form, if any. The unique constraint means
     * teaching a new target for an already-taught wording is a delete + insert in the same tx — the
     * app never lets two synonyms compete for the same key.
     */
    @Modifying
    @Query("DELETE FROM CatalogItemSynonym s WHERE s.owner.id = :ownerId AND s.spokenNormalized = :spoken")
    int deleteByOwnerIdAndSpokenNormalized(UUID ownerId, String spoken);
}
