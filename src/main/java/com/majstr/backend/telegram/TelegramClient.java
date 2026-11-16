package com.majstr.backend.telegram;

import com.majstr.backend.config.BotProperties;
import com.majstr.backend.service.inbox.VoiceAudio;
import com.majstr.backend.config.HttpClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The few Bot API methods majster-bot uses. Every call is a POST of JSON to
 * {@code https://api.telegram.org/bot<token>/<method>}; the token is never logged.
 */
@Slf4j
@Component
public class TelegramClient implements VoiceAudio {

    /** Above the long-poll wait, so a poll that legitimately waits is not cut off. */
    static final int POLL_TIMEOUT_SECONDS = 25;

    private final BotProperties props;
    private final RestClient http = HttpClients.withTimeouts(Duration.ofSeconds(POLL_TIMEOUT_SECONDS + 15));

    public TelegramClient(BotProperties props) {
        this.props = props;
    }

    /** One inline button: a callback or a link. */
    public record Button(String text, String callback, String url) {
        public static Button callback(String text, String data) {
            return new Button(text, data, null);
        }

        public static Button link(String text, String url) {
            return new Button(text, null, url);
        }
    }

    /** Send a message; returns its message_id, or null when Telegram refused it. */
    public Long sendMessage(long chatId, String text, List<List<Button>> keyboard) {
        Map<String, Object> body = message(text, keyboard);
        body.put("chat_id", chatId);
        JsonNode result = call("sendMessage", body);
        return result != null && result.has("message_id") ? result.get("message_id").asLong() : null;
    }

    /** False when Telegram refused the edit (a bad button, a message too old to edit). */
    public boolean editMessage(long chatId, long messageId, String text, List<List<Button>> keyboard) {
        Map<String, Object> body = message(text, keyboard);
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        return call("editMessageText", body) != null;
    }

    public void answerCallback(String callbackId, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("callback_query_id", callbackId);
        if (text != null) {
            body.put("text", text);
        }
        call("answerCallbackQuery", body);
    }

    /** Long polling (local dev). Returns the {@code result} array, or null on a failed call. */
    public JsonNode getUpdates(long offset) {
        return call("getUpdates", Map.of("offset", offset, "timeout", POLL_TIMEOUT_SECONDS,
                "allowed_updates", List.of("message", "callback_query")));
    }

    /** Telegram serves a bot files up to 20 MB; a minute of voice is about 0.5 MB, so this is generous. */
    static final int MAX_VOICE_BYTES = 5 * 1024 * 1024;

    /**
     * A voice note's audio: {@code getFile} names its path, then the file host serves it. Throws on any
     * failure — the inbox turns that into a FAILED entry the master can retry.
     */
    @Override
    public byte[] fetch(String fileId) {
        JsonNode file = call("getFile", Map.of("file_id", fileId));
        String path = file == null ? null : file.path("file_path").asString(null);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Telegram has no file for this voice note");
        }
        if (file.path("file_size").asLong(0) > MAX_VOICE_BYTES) {
            throw new IllegalStateException("Voice note too large");
        }
        try {
            byte[] audio = http.get()
                    .uri("https://api.telegram.org/file/bot" + props.telegramToken() + "/" + path)
                    .retrieve()
                    .body(byte[].class);
            if (audio == null || audio.length == 0 || audio.length > MAX_VOICE_BYTES) {
                throw new IllegalStateException("Voice note empty or too large");
            }
            return audio;
        } catch (RuntimeException e) {
            // The URL carries the token — the class name only.
            log.warn("Telegram file download failed: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("Telegram file download failed");
        }
    }

    /** False when Telegram refused it (a secret with characters it does not allow, an unreachable URL). */
    public boolean setWebhook(String url, String secret) {
        return call("setWebhook", Map.of("url", url, "secret_token", secret,
                "allowed_updates", List.of("message", "callback_query"))) != null;
    }

    public void deleteWebhook() {
        call("deleteWebhook", Map.of());
    }

    private static Map<String, Object> message(String text, List<List<Button>> keyboard) {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("link_preview_options", Map.of("is_disabled", true));
        if (keyboard != null && !keyboard.isEmpty()) {
            List<List<Map<String, String>>> rows = keyboard.stream().map(row -> row.stream().map(b -> b.url() != null
                    ? Map.of("text", b.text(), "url", b.url())
                    : Map.of("text", b.text(), "callback_data", b.callback())).toList()).toList();
            body.put("reply_markup", Map.of("inline_keyboard", rows));
        } else {
            body.put("reply_markup", Map.of("inline_keyboard", List.of()));
        }
        return body;
    }

    /** POST one method. Failures are logged and swallowed — a chat reply is never worth a 500. */
    private JsonNode call(String method, Map<String, Object> body) {
        if (!props.enabled()) {
            return null;
        }
        try {
            JsonNode response = http.post()
                    .uri("https://api.telegram.org/bot" + props.telegramToken() + "/" + method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return response != null && response.path("ok").asBoolean(false) ? response.get("result") : null;
        } catch (RuntimeException e) {
            // The message carries the URL, and the URL carries the token — log the method only.
            log.warn("Telegram {} failed: {}", method, e.getClass().getSimpleName());
            return null;
        }
    }
}
