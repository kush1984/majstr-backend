package com.majstr.backend.repository;

import com.majstr.backend.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ClientRepository extends JpaRepository<Client, UUID> {

    List<Client> findByOwnerIdOrderByFullNameAsc(UUID ownerId);

    long countByOwnerId(UUID ownerId);

    /** Client count per owner for a set of users — admin activity columns (no N+1). */
    @Query("SELECT c.owner.id AS ownerId, COUNT(c) AS cnt FROM Client c "
            + "WHERE c.owner.id IN :ownerIds GROUP BY c.owner.id")
    List<OwnerCount> countByOwnerIdIn(@Param("ownerIds") Collection<UUID> ownerIds);
}
