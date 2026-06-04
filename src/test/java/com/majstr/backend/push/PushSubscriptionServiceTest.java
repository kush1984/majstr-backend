package com.majstr.backend.push;

import com.majstr.backend.dto.PushSubscribeRequest;
import com.majstr.backend.entity.PushSubscription;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.PushSubscriptionRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTest {

    @Mock private PushSubscriptionRepository subscriptionRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private PushSubscriptionService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void subscribe_storesNewSubscriptionWhenEndpointUnknown() {
        given(subscriptionRepository.findByEndpoint("https://fcm.example/abc")).willReturn(Optional.empty());
        given(userRepository.getReferenceById(userId)).willReturn(User.builder().id(userId).build());

        service.subscribe(userId, new PushSubscribeRequest(
                "https://fcm.example/abc", "p256dh-key", "auth-key", "Mozilla/5.0"));

        ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
        verify(subscriptionRepository).save(captor.capture());
        PushSubscription saved = captor.getValue();
        assertThat(saved.getEndpoint()).isEqualTo("https://fcm.example/abc");
        assertThat(saved.getP256dh()).isEqualTo("p256dh-key");
        assertThat(saved.getAuth()).isEqualTo("auth-key");
        assertThat(saved.getUser().getId()).isEqualTo(userId);
    }

    @Test
    void subscribe_upsertsExistingEndpointWithoutDuplicating() {
        PushSubscription existing = PushSubscription.builder()
                .id(UUID.randomUUID())
                .endpoint("https://fcm.example/abc")
                .p256dh("old-p256dh")
                .auth("old-auth")
                .build();
        given(subscriptionRepository.findByEndpoint("https://fcm.example/abc")).willReturn(Optional.of(existing));
        given(userRepository.getReferenceById(userId)).willReturn(User.builder().id(userId).build());

        service.subscribe(userId, new PushSubscribeRequest(
                "https://fcm.example/abc", "new-p256dh", "new-auth", null));

        ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
        verify(subscriptionRepository).save(captor.capture());
        // Same row reused (same id) — no duplicate created.
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
        assertThat(captor.getValue().getP256dh()).isEqualTo("new-p256dh");
        assertThat(captor.getValue().getAuth()).isEqualTo("new-auth");
    }

    @Test
    void unsubscribe_deletesByEndpointAndUser() {
        service.unsubscribe(userId, "https://fcm.example/abc");

        verify(subscriptionRepository).deleteByEndpointAndUserId("https://fcm.example/abc", userId);
    }
}
