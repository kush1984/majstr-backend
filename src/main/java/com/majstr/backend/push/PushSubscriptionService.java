package com.majstr.backend.push;

import com.majstr.backend.dto.PushSubscribeRequest;
import com.majstr.backend.entity.PushSubscription;
import com.majstr.backend.exception.InvalidPushEndpointException;
import com.majstr.backend.repository.PushSubscriptionRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
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

    /** Every push fans out to all of them, one at a time: a phone and a laptop, a reinstall or two. */
    static final int MAX_PER_USER = 5;

    private final PushSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    @Transactional
    public void subscribe(UUID userId, PushSubscribeRequest req) {
        if (!PushEndpointPolicy.allows(req.endpoint())) {
            throw new InvalidPushEndpointException();
        }
        PushSubscription sub = subscriptionRepository.findByEndpoint(req.endpoint())
                .orElseGet(PushSubscription::new);
        sub.setUser(userRepository.getReferenceById(userId));
        sub.setEndpoint(req.endpoint());
        sub.setP256dh(req.p256dh());
        sub.setAuth(req.auth());
        sub.setUserAgent(req.userAgent());
        subscriptionRepository.save(sub);
        dropOldest(userId, req.endpoint());
    }

    /** Past the cap the oldest browsers go, never the one subscribing right now. */
    private void dropOldest(UUID userId, String current) {
        List<PushSubscription> others = subscriptionRepository.findByUserId(userId).stream()
                .filter(s -> !current.equals(s.getEndpoint()))
                .sorted(Comparator.comparing(PushSubscription::getCreatedAt))
                .toList();
        int excess = others.size() - (MAX_PER_USER - 1);
        if (excess > 0) {
            subscriptionRepository.deleteAll(others.subList(0, excess));
        }
    }

    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        subscriptionRepository.deleteByEndpointAndUserId(endpoint, userId);
    }
}
