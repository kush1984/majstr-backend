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

/**
 * One stretch of a master using the app (V199): opened by the first authenticated request after
 * {@link com.majstr.backend.service.ActivitySessionService#GAP} of silence, stretched by the later
 * ones. {@code lastSeenAt} is as precise as the 5-minute touch throttle, no more.
 */
@Entity
@Table(name = "user_activity_session")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class UserActivitySession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
