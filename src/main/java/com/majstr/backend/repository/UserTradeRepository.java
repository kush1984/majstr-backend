package com.majstr.backend.repository;

import com.majstr.backend.entity.UserTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserTradeRepository extends JpaRepository<UserTrade, UUID> {

    List<UserTrade> findByUserIdOrderBySortOrderAscIdAsc(UUID userId);

    Optional<UserTrade> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

    @Query("SELECT COALESCE(MAX(t.sortOrder) + 1, 0) FROM UserTrade t WHERE t.user.id = :userId")
    int nextSortOrder(@Param("userId") UUID userId);
}
