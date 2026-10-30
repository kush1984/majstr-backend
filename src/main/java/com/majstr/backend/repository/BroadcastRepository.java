package com.majstr.backend.repository;

import com.majstr.backend.entity.Broadcast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BroadcastRepository extends JpaRepository<Broadcast, UUID> {

    List<Broadcast> findTop50ByOrderByCreatedAtDesc();

    List<Broadcast> findByFinishedAtIsNull();
}
