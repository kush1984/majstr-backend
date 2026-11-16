package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One message as it arrived — from the Telegram bot or the PWA's quick add (V211) — stored BEFORE
 * anything is parsed out of it, so nothing a master sent can be lost to a failed model call.
 */
@Entity
@Table(name = "inbox_entry")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class InboxEntry {

    public enum Source { TELEGRAM, PWA }

    /** PARSING → NEW (something waits) or DONE (nothing does); FAILED when the parse could not run. */
    public enum Status { PARSING, NEW, DONE, FAILED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private Source source;

    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    private String rawText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "telegram_update_id")
    private Long telegramUpdateId;

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    /** Forwarded from someone else (V212): nothing in it is filed without the master's «Записати». */
    @Column(name = "forwarded", nullable = false)
    private boolean forwarded;

    /** A voice note of this many seconds (V213); null = typed. */
    @Column(name = "voice_seconds")
    private Integer voiceSeconds;

    /** Telegram's handle on the audio until it is transcribed (V213); cleared once it is. */
    @Column(name = "voice_file_id", length = 255)
    private String voiceFileId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
