package com.majstr.backend.repository;

import com.majstr.backend.entity.TelegramLinkCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface TelegramLinkCodeRepository extends JpaRepository<TelegramLinkCode, String> {

    @Modifying
    @Query("DELETE FROM TelegramLinkCode c WHERE c.userId = :userId OR c.expiresAt < :now")
    void deleteForUserOrExpired(UUID userId, Instant now);
}
