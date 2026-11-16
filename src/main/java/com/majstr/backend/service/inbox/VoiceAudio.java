package com.majstr.backend.service.inbox;

/** Fetches a voice note's audio by the handle its messenger gave it — Telegram's {@code file_id}. */
public interface VoiceAudio {

    byte[] fetch(String fileId);
}
