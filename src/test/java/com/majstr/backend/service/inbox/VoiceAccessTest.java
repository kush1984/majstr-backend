package com.majstr.backend.service.inbox;

import com.majstr.backend.config.OpenAiProperties;
import com.majstr.backend.config.VoiceProperties;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.DefaultFeatureGuard;
import com.majstr.backend.repository.InboxEntryRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ai.SpeechToText;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Voice notes: PRO, a FREE trial behind a verified email, a length cap and a daily budget for everyone. */
class VoiceAccessTest {

    private final UUID owner = UUID.randomUUID();
    private final UserRepository users = mock(UserRepository.class);
    private final InboxEntryRepository entries = mock(InboxEntryRepository.class);

    private VoiceAccess access(String openAiKey, int secondsPerDay) {
        VoiceProperties props = new VoiceProperties("gpt-4o-mini-transcribe", 60, 10, secondsPerDay);
        SpeechToText speech = new SpeechToText(new OpenAiProperties(openAiKey, "m", 1, ""), props);
        return new VoiceAccess(props, speech, new DefaultFeatureGuard(), users, entries);
    }

    private void master(Plan plan, boolean verified, long voiceNotesSoFar) {
        User user = User.builder().id(owner).plan(plan).emailVerified(verified).build();
        when(users.findById(owner)).thenReturn(Optional.of(user));
        when(entries.countByOwnerIdAndVoiceSecondsNotNull(owner)).thenReturn(voiceNotesSoFar);
    }

    @Test
    void proSendsVoice_whateverTheCount() {
        master(Plan.PRO, false, 500);
        assertThat(access("key", 900).admit(owner, 20)).isEqualTo(VoiceAccess.Verdict.OK);
        assertThat(access("key", 900).trialLeft(owner)).isEqualTo(-1);
    }

    @Test
    void freeTriesTen_thenItIsPro() {
        master(Plan.FREE, true, 9);
        VoiceAccess access = access("key", 900);
        assertThat(access.admit(owner, 20)).isEqualTo(VoiceAccess.Verdict.OK);
        assertThat(access.trialLeft(owner)).isEqualTo(1);

        master(Plan.FREE, true, 10);
        assertThat(access.admit(owner, 20)).isEqualTo(VoiceAccess.Verdict.TRIAL_OVER);
    }

    @Test
    void freeWithAnUnverifiedEmailIsAskedToVerifyFirst() {
        master(Plan.FREE, false, 0);
        assertThat(access("key", 900).admit(owner, 20)).isEqualTo(VoiceAccess.Verdict.VERIFY_EMAIL);
    }

    @Test
    void aNoteOverAMinuteIsRefusedBeforeAnythingIsPaid() {
        master(Plan.PRO, true, 0);
        assertThat(access("key", 900).admit(owner, 61)).isEqualTo(VoiceAccess.Verdict.TOO_LONG);
    }

    @Test
    void theDailyBudgetOfSecondsHoldsForEveryPlan() {
        master(Plan.PRO, true, 0);
        VoiceAccess access = access("key", 50);
        assertThat(access.admit(owner, 30)).isEqualTo(VoiceAccess.Verdict.OK);
        assertThat(access.admit(owner, 30)).isEqualTo(VoiceAccess.Verdict.DAILY_OVER);
    }

    @Test
    void withoutAnOpenAiKeyVoiceIsOff() {
        master(Plan.PRO, true, 0);
        assertThat(access("", 900).admit(owner, 5)).isEqualTo(VoiceAccess.Verdict.OFF);
    }
}
