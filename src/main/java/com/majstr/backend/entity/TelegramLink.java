package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A master's Telegram chat (V211): one chat per master, one master per chat. */
@Entity
@Table(name = "telegram_link")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "userId")
public class TelegramLink {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "username", length = 64)
    private String username;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;
}
