package com.majstr.backend.repository;

import com.majstr.backend.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken r set r.revoked = true where r.userId = :userId and r.revoked = false")
    int revokeAllForUser(@Param("userId") UUID userId);

    /** Sweep dead rows: expired tokens plus revoked ones (rotation leaves these behind). */
    @Modifying
    @Query("delete from RefreshToken r where r.expiresAt < :cutoff or r.revoked = true")
    int deleteExpiredOrRevoked(@Param("cutoff") Instant cutoff);
}
