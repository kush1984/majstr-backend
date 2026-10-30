package com.majstr.backend.service.broadcast;

import com.majstr.backend.repository.BroadcastRecipientRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** «Не надсилати мені такі листи»: the token is a broadcast letter's own, never a user id. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnsubscribeService {

    private final BroadcastRecipientRepository recipientRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public boolean isKnown(String token) {
        return recipientRepository.findByUnsubscribeToken(token).isPresent();
    }

    /** Idempotent: a second click keeps the first timestamp. False for a token that matches nothing. */
    @Transactional
    public boolean optOut(String token) {
        return recipientRepository.findByUnsubscribeToken(token)
                .flatMap(r -> userRepository.findById(r.getUserId()))
                .map(user -> {
                    if (user.getBroadcastOptOutAt() == null) {
                        user.setBroadcastOptOutAt(Instant.now());
                        log.info("User {} unsubscribed from broadcasts", user.getId());
                    }
                    return true;
                })
                .orElse(false);
    }
}
