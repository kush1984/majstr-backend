package com.majstr.backend.service.ai;

import com.majstr.backend.config.HttpClients;
import com.majstr.backend.config.OpenAiProperties;
import com.majstr.backend.config.VoiceProperties;
import com.majstr.backend.exception.AiExtractionException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A voice note → its text (majster-bot voice). OpenAI's transcription endpoint, because Anthropic has no
 * speech-to-text; the audio is sent once and kept nowhere. The object names go in as the {@code prompt},
 * so «Куликів» or «Липки» come back spelled the way the master's list spells them.
 *
 * <p>One global hourly ceiling, like {@code AiFlowBudget} for the text flows: a burst of strangers cannot
 * run up the bill. A failure is an {@link AiExtractionException}; the inbox turns it into FAILED, and
 * «Спробувати ще раз» transcribes again while Telegram still holds the file.</p>
 */
@Slf4j
@Component
public class SpeechToText {

    private static final String URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final int CALLS_PER_HOUR = 200;
    /** The prompt is a hint, not a list to recite: a few hundred characters is what the model reads. */
    private static final int PROMPT_MAX = 600;
    private static final String PROMPT_LEAD = "Будівельні роботи на обʼєктах";
    /**
     * Brands a master names out loud, spelled as the shop spells them: without them «Церезіт CM 11» came back
     * «це резіт, це 111». A hint for the ear only — nothing here reaches the catalog, which names no brand.
     */
    private static final String MATERIALS = "Матеріали: Церезіт (Ceresit) CM 11, CM 17, CE 40, CT 17, Кнауф (Knauf), "
            + "Ротбанд, Атлас (Atlas), Крайзель (Kreisel), Полімін, Siltek, Мапей (Mapei), Baumit, Henkel, Sniezka.";

    private final OpenAiProperties openAi;
    private final VoiceProperties voice;
    private final RestClient http = HttpClients.withTimeouts(Duration.ofSeconds(60));
    private final Bucket budget = Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(CALLS_PER_HOUR)
                    .refillGreedy(CALLS_PER_HOUR, Duration.ofHours(1)).build())
            .build();

    public SpeechToText(OpenAiProperties openAi, VoiceProperties voice) {
        this.openAi = openAi;
        this.voice = voice;
    }

    /** False without an OpenAI key — the bot then answers a voice note with «text only», as before. */
    public boolean enabled() {
        return openAi.apiKey() != null && !openAi.apiKey().isBlank();
    }

    public String transcribe(byte[] audio, List<String> objectNames) {
        if (!enabled()) {
            throw new AiExtractionException("error.ai.unavailable");
        }
        if (!budget.tryConsume(1)) {
            log.error("Voice transcription reached its hourly budget");
            throw new AiExtractionException("error.ai.unavailable");
        }
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return "voice.ogg";
            }
        });
        form.add("model", voice.model());
        form.add("language", "uk");
        form.add("response_format", "json");
        String prompt = prompt(objectNames);
        if (!prompt.isEmpty()) {
            form.add("prompt", prompt);
        }
        try {
            Map<?, ?> answer = http.post()
                    .uri(URL)
                    .header("Authorization", "Bearer " + openAi.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            Object text = answer == null ? null : answer.get("text");
            return heard(text == null ? "" : text.toString().trim());
        } catch (RestClientException e) {
            // The URL carries no secret, but the message may echo the request: the class name is enough.
            log.warn("Voice transcription failed: {}", e.getClass().getSimpleName());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
    }

    /**
     * Given silence (a muted microphone, a note of breathing), the model answers with the PROMPT itself —
     * the list of objects, which the parse then reads as «nothing to file». That is «not heard», said as such.
     */
    static String heard(String text) {
        String folded = text.replace('\'', 'ʼ').replace('’', 'ʼ');
        return folded.startsWith(PROMPT_LEAD) ? "" : text;
    }

    static String prompt(List<String> objectNames) {
        // The lead always comes first, so a prompt echoed back on silence is recognised by heard().
        String objects = objectNames == null || objectNames.isEmpty() ? "" : String.join(", ", objectNames);
        String head = PROMPT_LEAD + ": " + objects + ". ";
        int room = PROMPT_MAX - MATERIALS.length();
        if (head.length() > room) {
            head = head.substring(0, Math.max(PROMPT_LEAD.length(), room - 2)) + ". ";
        }
        return head + MATERIALS;
    }
}
