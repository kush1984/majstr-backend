package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.AppReviewAdminView;
import com.majstr.backend.dto.AppReviewPrompt;
import com.majstr.backend.dto.AppReviewRequest;
import com.majstr.backend.dto.PublicAppReview;
import com.majstr.backend.entity.AppReview;
import com.majstr.backend.entity.AppReviewSource;
import com.majstr.backend.entity.AppReviewStatus;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.AppReviewNotPublishableException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.AppReviewRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.OwnerCount;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Masters rate Majstr itself (V218): feedback for us, and — at 4–5 stars, with his consent and the
 * admin's nod — proof on majstr.pro for the next master.
 *
 * <p><b>When the home screen asks.</b> Only after a success (an estimate of a real object that left
 * the app or came back signed), never in his first {@link #QUIET_START}, and no sooner than
 * {@link #PROMPT_EVERY} after he was last asked — answered or «Не зараз». A master who rated from the
 * profile meanwhile is not asked for {@link #PROMPT_EVERY} either. Admins are never asked.</p>
 */
@Service
@RequiredArgsConstructor
public class AppReviewService {

    static final Duration QUIET_START = Duration.ofDays(7);
    static final Duration PROMPT_EVERY = Duration.ofDays(90);
    static final int ADMIN_LIST_LIMIT = 300;
    static final int PUBLIC_LIMIT = 12;

    private final AppReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final EstimateRepository estimateRepository;

    @Transactional(readOnly = true)
    public AppReviewPrompt prompt(UUID userId) {
        User user = load(userId);
        Instant now = Instant.now();
        boolean ask = user.getRole() == Role.USER
                && user.getCreatedAt() != null && user.getCreatedAt().isBefore(now.minus(QUIET_START))
                && (user.getReviewPromptAt() == null || user.getReviewPromptAt().isBefore(now.minus(PROMPT_EVERY)))
                && !reviewRepository.existsByUserIdAndCreatedAtAfter(userId, now.minus(PROMPT_EVERY))
                && reviewRepository.hasSentOrSignedEstimate(userId);
        return new AppReviewPrompt(ask);
    }

    /** «Не зараз» on the home screen: not asked again for {@link #PROMPT_EVERY}. */
    @Transactional
    public void dismissPrompt(UUID userId) {
        load(userId).setReviewPromptAt(Instant.now());
    }

    @Transactional
    public void submit(UUID userId, AppReviewRequest req) {
        User user = load(userId);
        String text = req.text() == null || req.text().isBlank() ? null : req.text().trim();
        reviewRepository.save(AppReview.builder()
                .user(user)
                .rating(req.rating().shortValue())
                .text(text)
                .source(req.source() == null ? AppReviewSource.PROFILE : req.source())
                // A low rating is what to fix — it is never put on the landing page, whatever was ticked.
                .publishConsent(req.publishConsent() && req.rating() >= 4)
                .build());
        user.setReviewPromptAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public List<AppReviewAdminView> adminList() {
        return reviewRepository.findForAdmin(PageRequest.of(0, ADMIN_LIST_LIMIT)).stream()
                .map(r -> new AppReviewAdminView(r.getId(), r.getUser().getId(), r.getUser().getEmail(),
                        r.getUser().getFullName(), r.getUser().getPhone(), r.getRating(), r.getText(),
                        r.getSource(), r.isPublishConsent(), r.getStatus(), r.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return reviewRepository.countByStatus(AppReviewStatus.NEW);
    }

    /** Publish, hide or mark unread. Publishing without his consent is refused — the CHECK agrees. */
    @Transactional
    public void setStatus(UUID reviewId, AppReviewStatus status) {
        AppReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + reviewId));
        if (status == AppReviewStatus.PUBLISHED && !review.isPublishConsent()) {
            throw new AppReviewNotPublishableException("error.app-review.no-consent");
        }
        review.setStatus(status);
        review.setPublishedAt(status == AppReviewStatus.PUBLISHED ? Instant.now() : null);
    }

    @Transactional(readOnly = true)
    public List<PublicAppReview> published() {
        List<AppReview> reviews = reviewRepository.findByStatusWithUser(AppReviewStatus.PUBLISHED,
                PageRequest.of(0, PUBLIC_LIMIT));
        if (reviews.isEmpty()) {
            return List.of();
        }
        Set<UUID> authors = reviews.stream().map(r -> r.getUser().getId()).collect(Collectors.toSet());
        Map<UUID, Long> estimates = byOwner(estimateRepository.countByProjectOwnerIdIn(authors));
        Map<UUID, Long> signed = byOwner(estimateRepository.countByProjectOwnerIdInAndStatus(authors,
                EstimateStatus.SIGNED));
        LocalDate today = LocalDate.now(LocalizationConfig.ZONE);
        return reviews.stream().map(r -> {
            User u = r.getUser();
            LocalDate joined = u.getCreatedAt().atZone(LocalizationConfig.ZONE).toLocalDate();
            return new PublicAppReview(
                    publicName(u.getFullName()),
                    u.getTrades().stream().sorted().map(TradeLabel::ua).toList(),
                    r.getRating(),
                    r.getText(),
                    Math.max(1, (int) Period.between(joined, today).toTotalMonths()),
                    estimates.getOrDefault(u.getId(), 0L),
                    signed.getOrDefault(u.getId(), 0L),
                    r.getPublishedAt());
        }).toList();
    }

    /** «Олена Коваль» → «Олена К.»; a single word stays as it is; none → «Майстер». */
    static String publicName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "Майстер";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[0] + " " + parts[1].substring(0, 1).toUpperCase(Locale.ROOT) + ".";
    }

    private static Map<UUID, Long> byOwner(List<OwnerCount> counts) {
        return counts.stream().collect(Collectors.toMap(OwnerCount::getOwnerId, OwnerCount::getCnt));
    }

    private User load(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }
}
