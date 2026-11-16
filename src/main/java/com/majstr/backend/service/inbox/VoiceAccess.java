package com.majstr.backend.service.inbox;

import com.majstr.backend.config.VoiceProperties;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.InboxEntryRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.BucketRegistry;
import com.majstr.backend.service.ai.SpeechToText;
import io.github.bucket4j.Bandwidth;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Who may send the bot a voice note (owner's decision, 2026-11-16): PRO; FREE with a verified email gets
 * {@code app.voice.free-trial} notes to feel it, then «Голосові — у PRO». Every plan has a daily budget of
 * transcribed seconds, and a note longer than {@code max-seconds} is refused before anything is paid.
 * Database work only — it answers on the webhook's thread, before the 200.
 */
@Component
public class VoiceAccess {

    public enum Verdict { OK, OFF, TOO_LONG, VERIFY_EMAIL, TRIAL_OVER, DAILY_OVER }

    private final VoiceProperties props;
    private final SpeechToText speech;
    private final FeatureGuard featureGuard;
    private final UserRepository userRepository;
    private final InboxEntryRepository entryRepository;
    private final BucketRegistry<UUID> seconds;

    public VoiceAccess(VoiceProperties props, SpeechToText speech, FeatureGuard featureGuard,
                       UserRepository userRepository, InboxEntryRepository entryRepository) {
        this.props = props;
        this.speech = speech;
        this.featureGuard = featureGuard;
        this.userRepository = userRepository;
        this.entryRepository = entryRepository;
        int perDay = Math.max(1, props.secondsPerDay());
        this.seconds = new BucketRegistry<>(Bandwidth.builder()
                .capacity(perDay)
                .refillIntervally(perDay, Duration.ofDays(1))
                .build(), Duration.ofDays(1));
    }

    /** Decides and, on OK, spends the note's seconds from today's budget. */
    public Verdict admit(UUID ownerId, int durationSeconds) {
        if (!speech.enabled()) {
            return Verdict.OFF;
        }
        if (durationSeconds > props.maxSeconds()) {
            return Verdict.TOO_LONG;
        }
        User user = userRepository.findById(ownerId).orElse(null);
        if (user == null) {
            return Verdict.OFF;
        }
        if (!featureGuard.isEnabled(user, Feature.VOICE_NOTES)) {
            if (user.getPlan() == Plan.FREE && !user.isEmailVerified()) {
                return Verdict.VERIFY_EMAIL;
            }
            if (entryRepository.countByOwnerIdAndVoiceSecondsNotNull(ownerId) >= props.freeTrial()) {
                return Verdict.TRIAL_OVER;
            }
        }
        return seconds.get(ownerId).tryConsume(Math.max(1, durationSeconds)) ? Verdict.OK : Verdict.DAILY_OVER;
    }

    /** How many trial notes a FREE account has left after this one (for «ще N на пробу»); -1 = not on trial. */
    public int trialLeft(UUID ownerId) {
        User user = userRepository.findById(ownerId).orElse(null);
        if (user == null || featureGuard.isEnabled(user, Feature.VOICE_NOTES)) {
            return -1;
        }
        return (int) Math.max(0, props.freeTrial() - entryRepository.countByOwnerIdAndVoiceSecondsNotNull(ownerId));
    }

    public int maxSeconds() {
        return props.maxSeconds();
    }
}
