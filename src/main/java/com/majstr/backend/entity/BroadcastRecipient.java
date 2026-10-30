package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One letter of a {@link Broadcast}. Ids, not associations: the dispatcher runs outside any session. */
@Entity
@Table(name = "broadcast_recipient")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class BroadcastRecipient {

    public enum Status { PENDING, SENT, FAILED, SKIPPED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "broadcast_id", nullable = false, updatable = false)
    private UUID broadcastId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "email", nullable = false, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "unsubscribe_token", nullable = false, unique = true, length = 64, updatable = false)
    private String unsubscribeToken;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
