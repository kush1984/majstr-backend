package com.majstr.backend.security;

import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LastActiveTrackerTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private LastActiveTracker tracker;

    @Test
    void recordsDeviceForAKnownUserAgent() {
        UUID id = UUID.randomUUID();
        tracker.touch(id, "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Mobile Safari/604.1");

        verify(userRepository).touchLastActiveAndDevice(eq(id), any(Instant.class), eq("MOBILE"), eq("iOS"));
        verify(userRepository, never()).touchLastActive(any(), any());
    }

    @Test
    void fallsBackToPlainTouchForAnUnrecognizedUserAgent() {
        UUID id = UUID.randomUUID();
        tracker.touch(id, "curl/8.4.0"); // API tool — must not wipe a known device

        verify(userRepository).touchLastActive(eq(id), any(Instant.class));
        verify(userRepository, never()).touchLastActiveAndDevice(any(), any(), any(), any());
    }

    @Test
    void throttlesRepeatTouchesWithinTheWindow() {
        UUID id = UUID.randomUUID();
        tracker.touch(id, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0 Safari/537.36");
        tracker.touch(id, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0 Safari/537.36");

        // Second call inside the 5-min throttle is a no-op.
        verify(userRepository).touchLastActiveAndDevice(eq(id), any(Instant.class), eq("DESKTOP"), eq("Windows"));
    }
}
