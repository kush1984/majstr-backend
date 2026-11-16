-- Voice notes in the bot (2026-11-16): a voice note is an inbox entry whose text comes from transcription.
-- voice_seconds marks it as one (and counts the FREE trial); voice_file_id is Telegram's handle on the audio
-- until it is transcribed, then cleared — we keep the text, never the audio.
ALTER TABLE inbox_entry ADD COLUMN voice_seconds INT;
ALTER TABLE inbox_entry ADD COLUMN voice_file_id VARCHAR(255);

CREATE INDEX idx_inbox_entry_owner_voice ON inbox_entry (owner_id) WHERE voice_seconds IS NOT NULL;

COMMENT ON COLUMN inbox_entry.voice_seconds IS 'A voice note of this many seconds (V213); NULL = typed.';
COMMENT ON COLUMN inbox_entry.voice_file_id IS 'Telegram file_id of a voice note not transcribed yet (V213); cleared once it is.';
