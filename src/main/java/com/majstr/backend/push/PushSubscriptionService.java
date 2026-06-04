package com.majstr.backend.push;

import com.majstr.backend.dto.PushSubscribeRequest;
import com.majstr.backend.entity.PushSubscription;
import com.majstr.backend.repository.PushSubscriptionRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Manages a user's Web Push subscriptions. The push endpoint is globally
 * unique, so {@link #subscribe} upserts by endpoint (re-subscribing the same
 * browser refreshes its keys instead of creating a duplicate) and reassigns it
 * to the current user if a different account had it before.
 */
@Service
@RequiredArgsConstructor
public class PushSubscriptionService {

    private final PushSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    @Transactional
    public void subscribe(UUID userId, PushSubscribeRequest req) {
        PushSubscription sub = subscriptionRepository.findByEndpoint(req.endpoint())
                .orElseGet(PushSubscription::new);
        sub.setUser(userRepository.getReferenceById(userId));
        sub.setEndpoint(req.endpoint());
        sub.setP256dh(req.p256dh());
        sub.setAuth(req.auth());
        sub.setUserAgent(req.userAgent());
        subscriptionRepository.save(sub);
    }

    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        subscriptionRepository.deleteByEndpointAndUserId(endpoint, userId);
    }
}
