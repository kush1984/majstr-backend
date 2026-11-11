package com.majstr.backend.repository;

import com.majstr.backend.entity.AdminPlanGrant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AdminPlanGrantRepository extends JpaRepository<AdminPlanGrant, UUID> {

    @Query("SELECT g FROM AdminPlanGrant g WHERE g.userId = :userId ORDER BY g.createdAt DESC")
    List<AdminPlanGrant> findRecent(@Param("userId") UUID userId, Pageable pageable);
}
