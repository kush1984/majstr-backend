package com.majstr.backend.repository;

import com.majstr.backend.entity.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateRepository extends JpaRepository<Estimate, UUID> {

    List<Estimate> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
