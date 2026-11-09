package com.majstr.backend.service;

import com.majstr.backend.dto.HelpRequestLead;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The admin's «Просять допомогти» call list (V191, V193). */
@Service
@RequiredArgsConstructor
public class HelpRequestService {

    static final int LIST_LIMIT = 200;

    private final UserRepository userRepository;

    /** Open calls first, the database decides it: past the limit an old unhandled call must not fall off. */
    @Transactional(readOnly = true)
    public List<HelpRequestLead> list() {
        return userRepository.findHelpRequestsOpenFirst(PageRequest.of(0, LIST_LIMIT)).stream()
                .map(HelpRequestService::lead)
                .toList();
    }

    @Transactional
    public HelpRequestLead markHandled(UUID userId, boolean handled) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        u.setHelpHandledAt(handled ? Instant.now() : null);
        return lead(u);
    }

    private static HelpRequestLead lead(User u) {
        return new HelpRequestLead(u.getId(), u.getEmail(), u.getFullName(),
                u.getHelpPhone() != null ? u.getHelpPhone() : u.getPhone(),
                u.getHelpRequestedAt(), u.getHelpHandledAt());
    }
}
