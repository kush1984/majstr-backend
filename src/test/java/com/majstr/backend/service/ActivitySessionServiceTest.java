package com.majstr.backend.service;

import com.majstr.backend.repository.UserActivitySessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ActivitySessionServiceTest {

    @Mock UserActivitySessionRepository repository;
    @InjectMocks ActivitySessionService service;

    private final UUID user = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-11T10:00:00Z");

    @Test
    void stretchesTheOpenSessionWhenThereIsOne() {
        given(repository.extendOpen(user, now.minus(ActivitySessionService.GAP), now)).willReturn(1);

        service.record(user, now);

        verify(repository, never()).open(any(), any(), any());
    }

    @Test
    void opensANewSessionAfterTheGap() {
        given(repository.extendOpen(user, now.minus(ActivitySessionService.GAP), now)).willReturn(0);

        service.record(user, now);

        verify(repository).open(any(UUID.class), eq(user), eq(now));
    }
}
