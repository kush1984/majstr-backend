package com.majstr.backend.service.ai;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may spend a model call on the FREE plan (review B-125). The per-account limits multiply with the
 * number of accounts, so an address nobody confirmed does not get a share of them. Same rule as the
 * trial and the client PDF. A paid plan passed that check on its way in.
 */
@Component
@RequiredArgsConstructor
public class AiAccess {

    private final UserRepository userRepository;

    public void requireAllowed(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (user.getPlan() == Plan.FREE && !user.isEmailVerified()) {
            throw new EmailNotVerifiedException("error.email-not-verified");
        }
    }
}
