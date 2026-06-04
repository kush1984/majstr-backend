package com.majstr.backend.push;

import com.majstr.backend.config.VapidProperties;
import com.majstr.backend.entity.PushSubscription;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.PushSubscriptionRepository;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PushServiceTest {

    private final PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
    // Mocked — the payload bytes are irrelevant here; delivery is stubbed.
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final User user = User.builder().id(UUID.randomUUID()).build();

    private PushService configuredService() {
        VapidProperties props = new VapidProperties("public-key", "private-key", "mailto:admin@majstr.app");
        PushService service = spy(new PushService(props, repository, objectMapper));
        // Bypass real VAPID key parsing — we only test the dispatch/prune logic.
        nl.martijndwars.webpush.PushService nativeMock = mock(nl.martijndwars.webpush.PushService.class);
        try {
            doReturn(nativeMock).when(service).createPushService();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return service;
    }

    private PushSubscription subscription() {
        return PushSubscription.builder()
                .id(UUID.randomUUID())
                .user(user)
                .endpoint("https://fcm.example/" + UUID.randomUUID())
                .p256dh("p256dh")
                .auth("auth")
                .build();
    }

    private HttpResponse responseWithStatus(int status) {
        HttpResponse response = mock(HttpResponse.class);
        StatusLine line = mock(StatusLine.class);
        given(line.getStatusCode()).willReturn(status);
        given(response.getStatusLine()).willReturn(line);
        return response;
    }

    @Test
    void sendToUser_skipsWhenVapidNotConfigured() {
        VapidProperties blank = new VapidProperties("", "", "mailto:admin@majstr.app");
        PushService service = new PushService(blank, repository, objectMapper);

        service.sendToUser(user, "Title", "Body", "/projects/1");

        verifyNoInteractions(repository);
    }

    @Test
    void sendToUser_deliversToEverySubscription() throws Exception {
        PushService service = configuredService();
        PushSubscription a = subscription();
        PushSubscription b = subscription();
        given(repository.findByUserId(user.getId())).willReturn(List.of(a, b));
        doReturn(responseWithStatus(201)).when(service).deliver(any(), any(), any());

        service.sendToUser(user, "Title", "Body", "/projects/1");

        verify(service, times(2)).deliver(any(), any(), any());
        verify(repository, never()).delete(any());
    }

    @Test
    void sendToUser_prunesSubscriptionGoneFromPushService() throws Exception {
        PushService service = configuredService();
        PushSubscription dead = subscription();
        given(repository.findByUserId(user.getId())).willReturn(List.of(dead));
        // 410 Gone — the browser unsubscribed / the subscription expired.
        doReturn(responseWithStatus(410)).when(service).deliver(any(), any(), any());

        service.sendToUser(user, "Title", "Body", "/projects/1");

        verify(repository).delete(dead);
    }
}
