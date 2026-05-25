package com.majstr.backend.repository;

import com.majstr.backend.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ClientRepository extends JpaRepository<Client, UUID> {

    List<Client> findByOwnerIdOrderByFullNameAsc(UUID ownerId);
}
