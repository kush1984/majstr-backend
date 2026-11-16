package com.majstr.backend.telegram;

import com.majstr.backend.config.BotProperties;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Telegram's webhook. Public (Telegram carries no JWT), so it trusts only the secret Telegram echoes
 * in {@code X-Telegram-Bot-Api-Secret-Token} — set by {@link TelegramStartup} at registration.
 * It answers 200 at once and hands the update off: Telegram redelivers on any non-2xx or a slow
 * answer, and the work behind one message is a model call.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class TelegramWebhookController {

    public static final String PATH = "/api/public/telegram/webhook";

    private final BotProperties props;
    private final TelegramBot bot;

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
            @RequestBody JsonNode update) {
        if (!props.webhook() || !matches(secret, props.webhookSecret())) {
            return ResponseEntity.notFound().build();
        }
        // Stored first, synchronously; only the parse (a model call) goes to the pool.
        if (update.hasNonNull("callback_query")) {
            bot.tapAsync(update);
        } else {
            bot.store(update).ifPresentOrElse(bot::parseAsync, () -> bot.handleAsync(update));
        }
        return ResponseEntity.ok().build();
    }

    private static boolean matches(String given, String expected) {
        if (given == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
