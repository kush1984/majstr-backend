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

    /** Clears {@code rotatedAt} too: a token rotated a moment ago must lose its grace window with the rest. */
    @Modifying
    @Query("update RefreshToken r set r.revoked = true, r.rotatedAt = null where r.userId = :userId")
    int revokeAllForUser(@Param("userId") UUID userId);

    @Modifying
    @Query("update RefreshToken r set r.revoked = true, r.rotatedAt = null where r.familyId = :familyId")
    int revokeFamily(@Param("familyId") UUID familyId);

    /** 1 for the request that rotated it, 0 for one that lost the race. */
    @Modifying
    @Query("update RefreshToken r set r.revoked = true, r.rotatedAt = :now where r.id = :id and r.revoked = false")
    int markRotated(@Param("id") UUID id, @Param("now") Instant now);

    /** 1 while the replay allowance lasts. Atomic, so a burst cannot all read the same count. */
    @Modifying
    @Query("update RefreshToken r set r.graceUses = r.graceUses + 1 "
            + "where r.id = :id and r.rotatedAt is not null and r.graceUses < :max")
    int spendGrace(@Param("id") UUID id, @Param("max") short max);

    /**
     * Sweep dead rows. A rotated row stays until {@code rotatedCutoff}: it is what recognises a stolen
     * token being replayed, and deleted it would be just an unknown token.
     */
    @Modifying
    @Query("delete from RefreshToken r where r.expiresAt < :cutoff "
            + "or (r.revoked = true and (r.rotatedAt is null or r.rotatedAt < :rotatedCutoff))")
    int deleteExpiredOrRevoked(@Param("cutoff") Instant cutoff, @Param("rotatedCutoff") Instant rotatedCutoff);
}
