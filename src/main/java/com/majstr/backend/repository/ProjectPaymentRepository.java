package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectPaymentRepository extends JpaRepository<ProjectPayment, UUID> {

    List<ProjectPayment> findByProjectIdOrderBySortOrderAscIdAsc(UUID projectId);

    Optional<ProjectPayment> findByIdAndProjectId(UUID id, UUID projectId);

    @Query("SELECT COALESCE(MAX(p.sortOrder) + 1, 0) FROM ProjectPayment p WHERE p.project.id = :projectId")
    int nextSortOrder(@Param("projectId") UUID projectId);
}
