package com.majstr.backend.push;

import com.majstr.backend.config.VapidProperties;
import com.majstr.backend.entity.PushSubscription;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.PushSubscriptionRepository;
import nl.martijndwars.webpush.Utils;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                .endpoint("https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID())
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

    @Test
    void sendToUser_neverConnectsToAnEndpointThatIsNotAPushService() throws Exception {
        PushService service = configuredService();
        PushSubscription foreign = subscription();
        foreign.setEndpoint("http://postgres.railway.internal:5432/");
        given(repository.findByUserId(user.getId())).willReturn(List.of(foreign));

        service.sendToUser(user, "Title", "Body", "/projects/1");

        verify(service, never()).deliver(any(), any(), any());
        verify(repository).delete(foreign);
    }

    @Test
    void sendToUser_holdsNoTransactionAcrossTheNetworkCall() throws Exception {
        assertThat(PushService.class
                .getMethod("sendToUser", User.class, String.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    void deliver_givesUpOnAnEndpointThatAcceptsAndNeverAnswers() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        List<Socket> held = new ArrayList<>();
        try (ServerSocket tarpit = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        held.add(tarpit.accept());
                    }
                } catch (IOException closed) {
                    // the test is over
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            KeyPairGenerator keys = KeyPairGenerator.getInstance("ECDH", "BC");
            keys.initialize(new ECGenParameterSpec("prime256v1"));
            KeyPair server = keys.generateKeyPair();
            byte[] auth = new byte[16];
            new SecureRandom().nextBytes(auth);
            Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
            PushSubscription stalled = subscription();
            stalled.setEndpoint("http://127.0.0.1:" + tarpit.getLocalPort() + "/");
            stalled.setP256dh(base64.encodeToString(Utils.encode((ECPublicKey) keys.generateKeyPair().getPublic())));
            stalled.setAuth(base64.encodeToString(auth));

            PushService service = spy(new PushService(
                    new VapidProperties("public-key", "private-key", "mailto:admin@majstr.app"), repository, objectMapper));
            doReturn(300L).when(service).sendTimeoutMillis();

            long started = System.nanoTime();
            assertThatThrownBy(() -> service.deliver(
                    new nl.martijndwars.webpush.PushService(server, "mailto:admin@majstr.app"), stalled, new byte[]{1}))
                    .isInstanceOf(TimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        }
    }
}
