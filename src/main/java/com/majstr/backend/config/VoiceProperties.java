package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Voice notes in the bot (majster-bot voice): Telegram's audio is transcribed by OpenAI and then parsed
 * exactly like a typed message. Off without {@code OPENAI_API_KEY} — the bot then keeps saying «text only».
 *
 * @param model          the transcription model ({@code gpt-4o-mini-transcribe})
 * @param maxSeconds     longest note accepted; a longer one is refused before anything is paid
 * @param freeTrial      voice notes a FREE account may try before «Голосові — у PRO»
 * @param secondsPerDay  per-account daily budget of transcribed seconds, every plan
 */
@ConfigurationProperties(prefix = "app.voice")
public record VoiceProperties(String model, int maxSeconds, int freeTrial, int secondsPerDay) {}
