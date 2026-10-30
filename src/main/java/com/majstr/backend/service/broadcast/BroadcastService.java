package com.majstr.backend.service.broadcast;

import com.majstr.backend.config.BroadcastProperties;
import com.majstr.backend.config.EmailProperties;
import com.majstr.backend.dto.BroadcastPreviewRequest;
import com.majstr.backend.dto.BroadcastPreviewResponse;
import com.majstr.backend.dto.BroadcastRecipientView;
import com.majstr.backend.dto.BroadcastSegmentView;
import com.majstr.backend.dto.BroadcastSendRequest;
import com.majstr.backend.dto.BroadcastSummary;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Broadcast;
import com.majstr.backend.entity.BroadcastRecipient;
import com.majstr.backend.entity.BroadcastSegment;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.BroadcastRecipientRepository;
import com.majstr.backend.repository.BroadcastRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The admin «Розсилки» tab: what each segment holds, a preview, a test letter to oneself, the real
 * send and its history. The segments are fixed in code ({@link AudienceRules}); the admin picks one
 * and writes the text — there is deliberately no way to send a query from the browser.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String SAMPLE_NAME = "Петро";

    private final BroadcastAudience audience;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final BroadcastDispatcher dispatcher;
    private final EmailService emailService;
    private final BroadcastProperties broadcastProperties;
    private final EmailProperties emailProperties;
    private final AfterCommit afterCommit;

    @Transactional(readOnly = true)
    public List<BroadcastSegmentView> segments() {
        Map<BroadcastSegment, List<AudienceRules.Master>> all = audience.bySegment(Instant.now());
        return all.entrySet().stream()
                .map(e -> new BroadcastSegmentView(e.getKey(), e.getKey().label(), e.getKey().criteria(),
                        e.getValue().size()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BroadcastRecipientView> recipients(BroadcastSegment segment) {
        Map<UUID, Instant> lastSent = recipientRepository.findLastSentBySegment(segment).stream()
                .collect(Collectors.toMap(BroadcastRecipientRepository.UserSentAt::getUserId,
                        BroadcastRecipientRepository.UserSentAt::getSentAt));
        return audience.members(segment, Instant.now()).stream()
                .map(m -> new BroadcastRecipientView(m.id(), m.fullName(), m.email(), m.registeredAt(),
                        m.lastActiveAt(), m.objects(), m.estimates(), m.maxEstimateLines(), lastSent.get(m.id())))
                .toList();
    }

    /** Rendered for the segment's first master (the newest), or a sample name when it is empty. */
    @Transactional(readOnly = true)
    public BroadcastPreviewResponse preview(BroadcastPreviewRequest req) {
        String name = audience.members(req.segment(), Instant.now()).stream()
                .findFirst().map(AudienceRules.Master::fullName).orElse(SAMPLE_NAME);
        BroadcastLetter.Rendered letter = BroadcastLetter.render(req.subject(), req.body(), name,
                req.segment().buttonLabel(), emailProperties.appUrl() + "/", "#");
        return new BroadcastPreviewResponse(name, letter.subject(), letter.html());
    }

    /**
     * The letter to the admin's own address, synchronously, so the panel can say whether it went.
     * The button opens the app even for UNVERIFIED (no verify token is minted for the admin), and
     * the unsubscribe link carries a token that matches no row.
     */
    public String sendTest(BroadcastPreviewRequest req, String adminEmail) {
        User admin = userRepository.findByEmailIgnoreCase(adminEmail).orElse(null);
        String name = admin == null ? SAMPLE_NAME : admin.getFullName();
        String unsubscribe = dispatcher.unsubscribeUrl("test");
        BroadcastLetter.Rendered letter = BroadcastLetter.render(req.subject(), req.body(), name,
                req.segment().buttonLabel(), emailProperties.appUrl() + "/", unsubscribe);
        emailService.sendBroadcast(new EmailService.BroadcastEmail(adminEmail,
                broadcastProperties.fromName(), broadcastProperties.replyTo(),
                "[ТЕСТ] " + letter.subject(), letter.html(), letter.text(), unsubscribe));
        return adminEmail;
    }

    /**
     * Records the broadcast and one PENDING row per letter, then hands it to the dispatcher once
     * this transaction has committed (the dispatcher reads those rows). Empty when none of the
     * ticked masters is still in the segment.
     */
    @Transactional
    public Optional<BroadcastSummary> send(BroadcastSendRequest req, String adminEmail) {
        Set<UUID> ticked = new HashSet<>(req.userIds());
        List<AudienceRules.Master> targets = audience.members(req.segment(), Instant.now()).stream()
                .filter(m -> ticked.contains(m.id()))
                .toList();
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        Broadcast broadcast = broadcastRepository.save(Broadcast.builder()
                .segment(req.segment())
                .subject(req.subject().trim())
                .body(req.body())
                .sentBy(adminEmail)
                .build());
        recipientRepository.saveAll(targets.stream()
                .map(m -> BroadcastRecipient.builder()
                        .broadcastId(broadcast.getId())
                        .userId(m.id())
                        .email(m.email())
                        .status(BroadcastRecipient.Status.PENDING)
                        .unsubscribeToken(newToken())
                        .build())
                .toList());
        UUID id = broadcast.getId();
        afterCommit.run(() -> dispatcher.dispatch(id));
        log.info("Broadcast {} ({}) queued by {}: {} letter(s)", id, req.segment(), adminEmail, targets.size());
        return Optional.of(summary(broadcast, Map.of(BroadcastRecipient.Status.PENDING, (long) targets.size())));
    }

    @Transactional(readOnly = true)
    public List<BroadcastSummary> history() {
        List<Broadcast> broadcasts = broadcastRepository.findTop50ByOrderByCreatedAtDesc();
        if (broadcasts.isEmpty()) {
            return List.of();
        }
        Map<UUID, Map<BroadcastRecipient.Status, Long>> counts = new HashMap<>();
        recipientRepository.countByBroadcastIdIn(broadcasts.stream().map(Broadcast::getId).toList())
                .forEach(c -> counts.computeIfAbsent(c.getBroadcastId(), k -> new EnumMap<>(BroadcastRecipient.Status.class))
                        .put(c.getStatus(), c.getCnt()));
        return broadcasts.stream().map(b -> summary(b, counts.getOrDefault(b.getId(), Map.of()))).toList();
    }

    /** A deploy in the middle of a send must not leave it hanging: pick up whatever is unfinished. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeUnfinished() {
        for (Broadcast b : broadcastRepository.findByFinishedAtIsNull()) {
            log.info("Resuming unfinished broadcast {}", b.getId());
            dispatcher.dispatch(b.getId());
        }
    }

    private static BroadcastSummary summary(Broadcast b, Map<BroadcastRecipient.Status, Long> c) {
        long sent = c.getOrDefault(BroadcastRecipient.Status.SENT, 0L);
        long failed = c.getOrDefault(BroadcastRecipient.Status.FAILED, 0L);
        long skipped = c.getOrDefault(BroadcastRecipient.Status.SKIPPED, 0L);
        long pending = c.getOrDefault(BroadcastRecipient.Status.PENDING, 0L);
        return new BroadcastSummary(b.getId(), b.getSegment(), b.getSegment().label(), b.getSubject(),
                b.getSentBy(), b.getCreatedAt(), b.getFinishedAt(),
                sent + failed + skipped + pending, sent, failed, skipped, pending);
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
