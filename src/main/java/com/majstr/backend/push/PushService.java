package com.majstr.backend.push;

import com.majstr.backend.config.VapidProperties;
import com.majstr.backend.entity.PushSubscription;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.PushSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.Security;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends Web Push notifications (VAPID / RFC 8291) to a contractor's subscribed
 * browsers. The VAPID keypair comes from {@link VapidProperties} (env only);
 * when unset the service logs and skips, so the feature is inert in dev without
 * keys — mirroring the email transport.
 *
 * <p>Each send runs on a background thread ({@link Async}) so it never blocks
 * the request, and swallows+logs failures so a flaky push service can't break
 * the calling flow. Subscriptions that the push service reports as gone
 * (HTTP 404 / 410) are pruned automatically.
 */
@Slf4j
@Service
public class PushService {

    static {
        // web-push relies on BouncyCastle for the ECDH key handling.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final VapidProperties props;
    private final PushSubscriptionRepository subscriptionRepository;
    private final ObjectMapper objectMapper;

    /** Lazily built once the keys are known to be valid; null until then. */
    private volatile nl.martijndwars.webpush.PushService nativePushService;

    public PushService(VapidProperties props,
                       PushSubscriptionRepository subscriptionRepository,
                       ObjectMapper objectMapper) {
        this.props = props;
        this.subscriptionRepository = subscriptionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Push a notification to every browser the user has subscribed. Fail-soft:
     * a missing config, a bad key, or a push-service error is logged, never
     * thrown. Subscriptions reported gone (404/410) are deleted.
     *
     * @param url optional click-through target (relative path resolved against
     *            the PWA origin, e.g. {@code /projects/{id}}); may be null
     */
    @Async
    @Transactional
    public void sendToUser(User user, String title, String body, String url) {
        if (!props.isConfigured()) {
            log.warn("VAPID keys not set — skipping push to user {} (title: {})", user.getId(), title);
            return;
        }
        nl.martijndwars.webpush.PushService service = pushService();
        if (service == null) {
            return;
        }
        List<PushSubscription> subscriptions = subscriptionRepository.findByUserId(user.getId());
        if (subscriptions.isEmpty()) {
            return;
        }
        byte[] payload = payload(title, body, url);
        for (PushSubscription sub : subscriptions) {
            sendOne(service, sub, payload, title);
        }
    }

    private void sendOne(nl.martijndwars.webpush.PushService service, PushSubscription sub,
                         byte[] payload, String title) {
        try {
            HttpResponse response = deliver(service, sub, payload);
            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                // Subscription is gone (browser unsubscribed / expired) — prune it.
                subscriptionRepository.delete(sub);
                log.info("Removed dead push subscription {} (HTTP {})", sub.getId(), status);
            } else if (status >= 400) {
                log.warn("Push to subscription {} failed: HTTP {}", sub.getId(), status);
            } else {
                log.info("Push sent to subscription {} (title: {})", sub.getId(), title);
            }
        } catch (Exception e) {
            log.error("Failed to push to subscription {} (title: {}): {}", sub.getId(), title, e.getMessage());
        }
    }

    private byte[] payload(String title, String body, String url) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("title", title);
        data.put("body", body);
        if (url != null && !url.isBlank()) {
            data.put("url", url);
        }
        return objectMapper.writeValueAsBytes(data);
    }

    private nl.martijndwars.webpush.PushService pushService() {
        nl.martijndwars.webpush.PushService service = nativePushService;
        if (service == null) {
            synchronized (this) {
                service = nativePushService;
                if (service == null) {
                    try {
                        service = createPushService();
                        nativePushService = service;
                    } catch (Exception e) {
                        log.error("Invalid VAPID configuration — push disabled: {}", e.getMessage());
                        return null;
                    }
                }
            }
        }
        return service;
    }

    /** Seam for tests: builds the underlying web-push client from the VAPID keys. */
    nl.martijndwars.webpush.PushService createPushService() throws Exception {
        return new nl.martijndwars.webpush.PushService(
                props.publicKey(), props.privateKey(), props.subject());
    }

    /** Seam for tests: encrypts and sends one notification, returning the push service's response. */
    HttpResponse deliver(nl.martijndwars.webpush.PushService service, PushSubscription sub, byte[] payload)
            throws Exception {
        // Force the modern aes128gcm encoding. The send(Notification) overload defaults to
        // the legacy "aesgcm" scheme ("Authorization: WebPush <jwt>"), which current FCM
        // rejects with 403; aes128gcm uses the "vapid t=,k=" header FCM accepts.
        return service.send(
                new Notification(sub.getEndpoint(), sub.getP256dh(), sub.getAuth(), payload),
                Encoding.AES128GCM);
    }
}

