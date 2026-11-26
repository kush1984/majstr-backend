package com.majstr.backend.service;

import com.majstr.backend.dto.HelpOfferResponse;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.HelpReason;
import com.majstr.backend.entity.PdfRender;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.PdfRenderRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * When the home screen offers «Допомога з налаштуванням» (V191, widened in V226), and why. Decided here, not in
 * the PWA: the second case needs to know whether anything ever reached a client, and the same reason is
 * stamped on the call when he asks, for the admin's list.
 *
 * <ul>
 *   <li>{@link HelpReason#NO_OBJECT} — a day in and no object of his own (the training sample is not one);</li>
 *   <li>{@link HelpReason#NOT_SENT} — a week in, an object, and nothing handed to a client: no estimate SENT or
 *       SIGNED and no PDF made in the app — the funnel's «Віддали клієнту» ({@link ClientHandoffs}), for one
 *       master.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class HelpOfferService {

    static final Duration NO_OBJECT_AFTER = Duration.ofDays(1);
    static final Duration NOT_SENT_AFTER = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final PdfRenderRepository pdfRenderRepository;

    /** The card, or empty: nothing to offer, or he already asked. The phone field starts with his Telegram one. */
    @Transactional(readOnly = true)
    public Optional<HelpOfferResponse> offer(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getHelpRequestedAt() != null) {
            return Optional.empty();
        }
        return Optional.ofNullable(reasonFor(user))
                .map(reason -> new HelpOfferResponse(reason, user.getContactPhone()));
    }

    /** Why the card stands for this master right now, or null. */
    @Transactional(readOnly = true)
    public HelpReason reasonFor(User user) {
        Duration age = Duration.between(user.getCreatedAt(), Instant.now());
        boolean hasObject = projectRepository.countByOwnerIdAndSampleFalse(user.getId()) > 0;
        if (!hasObject) {
            return age.compareTo(NO_OBJECT_AFTER) > 0 ? HelpReason.NO_OBJECT : null;
        }
        if (age.compareTo(NOT_SENT_AFTER) < 0) {
            return null;
        }
        boolean handed = estimateRepository.existsForOwnerWithStatusIn(user.getId(),
                List.of(EstimateStatus.SENT, EstimateStatus.SIGNED))
                || pdfRenderRepository.existsByOwnerIdAndReader(user.getId(), PdfRender.Reader.MASTER);
        return handed ? null : HelpReason.NOT_SENT;
    }
}
