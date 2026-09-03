package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the master typed — or dictated with his own keyboard's microphone — into the estimate's
 * dictation sheet. Plain text on purpose: cut 0 records no audio and transcribes nothing, because
 * every phone keyboard already dictates Ukrainian into any text field for free. (Windows voice
 * typing does NOT — it has no Ukrainian at all; the phone is the target, so this cut is unchanged.)
 *
 * <p>The cap is generous enough for a whole flat read out loud in one go, and low enough that the
 * field cannot be used to push a novel through the model.</p>
 */
public record DictationParseRequest(
        @NotBlank @Size(max = 4000) String text
) {}
