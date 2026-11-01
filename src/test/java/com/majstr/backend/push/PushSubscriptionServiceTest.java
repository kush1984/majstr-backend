package com.majstr.backend.push;

import com.majstr.backend.dto.PushSubscribeRequest;
import com.majstr.backend.entity.PushSubscription;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.InvalidPushEndpointException;
import com.majstr.backend.repository.PushSubscriptionRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTest {

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc";

    @Mock private PushSubscriptionRepository subscriptionRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private PushSubscriptionService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void subscribe_storesNewSubscriptionWhenEndpointUnknown() {
        given(subscriptionRepository.findByEndpoint(ENDPOINT)).willReturn(Optional.empty());
        given(userRepository.getReferenceById(userId)).willReturn(User.builder().id(userId).build());

        service.subscribe(userId, new PushSubscribeRequest(
                ENDPOINT, "p256dh-key", "auth-key", "Mozilla/5.0"));

        ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
        verify(subscriptionRepository).save(captor.capture());
        PushSubscription saved = captor.getValue();
        assertThat(saved.getEndpoint()).isEqualTo(ENDPOINT);
        assertThat(saved.getP256dh()).isEqualTo("p256dh-key");
        assertThat(saved.getAuth()).isEqualTo("auth-key");
        assertThat(saved.getUser().getId()).isEqualTo(userId);
    }

    @Test
    void subscribe_upsertsExistingEndpointWithoutDuplicating() {
        PushSubscription existing = PushSubscription.builder()
                .id(UUID.randomUUID())
                .endpoint(ENDPOINT)
                .p256dh("old-p256dh")
                .auth("old-auth")
                .build();
        given(subscriptionRepository.findByEndpoint(ENDPOINT)).willReturn(Optional.of(existing));
        given(userRepository.getReferenceById(userId)).willReturn(User.builder().id(userId).build());

        service.subscribe(userId, new PushSubscribeRequest(
                ENDPOINT, "new-p256dh", "new-auth", null));

        ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
        verify(subscriptionRepository).save(captor.capture());
        // Same row reused (same id) — no duplicate created.
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
        assertThat(captor.getValue().getP256dh()).isEqualTo("new-p256dh");
        assertThat(captor.getValue().getAuth()).isEqualTo("new-auth");
    }

    @Test
    void subscribe_refusesAnEndpointThatIsNotAPushService() {
        assertThatThrownBy(() -> service.subscribe(userId, new PushSubscribeRequest(
                "http://postgres.railway.internal:5432/", "p256dh-key", "auth-key", null)))
                .isInstanceOf(InvalidPushEndpointException.class);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void subscribe_pastTheCapDropsTheOldestOtherBrowsers() {
        given(subscriptionRepository.findByEndpoint(ENDPOINT)).willReturn(Optional.empty());
        given(userRepository.getReferenceById(userId)).willReturn(User.builder().id(userId).build());
        Instant now = Instant.now();
        // Five older browsers plus this one, returned newest first to prove the sort.
        List<PushSubscription> others = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> PushSubscription.builder().id(UUID.randomUUID())
                        .endpoint("https://fcm.googleapis.com/fcm/send/" + i)
                        .createdAt(now.minusSeconds(i * 60L)).build())
                .toList();
        PushSubscription oldest = others.get(4);
        PushSubscription current = PushSubscription.builder().id(UUID.randomUUID())
                .endpoint(ENDPOINT).createdAt(now.minusSeconds(100_000)).build();
        given(subscriptionRepository.findByUserId(userId)).willReturn(
                java.util.stream.Stream.concat(others.stream(), java.util.stream.Stream.of(current)).toList());

        service.subscribe(userId, new PushSubscribeRequest(ENDPOINT, "p256dh-key", "auth-key", null));

        // The cap is five including the current one, so exactly the oldest OTHER row goes.
        verify(subscriptionRepository).deleteAll(List.of(oldest));
    }

    @Test
    void unsubscribe_deletesByEndpointAndUser() {
        service.unsubscribe(userId, ENDPOINT);

        verify(subscriptionRepository).deleteByEndpointAndUserId(ENDPOINT, userId);
    }
}
