package com.majstr.backend.config;

import com.majstr.backend.service.ai.AnthropicJsonExtractor;
import com.majstr.backend.service.ai.MisconfiguredJsonExtractor;
import com.majstr.backend.service.ai.OpenAiJsonExtractor;
import org.junit.jupiter.api.Test;

import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.service.ai.AiInput;
import com.majstr.backend.service.ai.JsonExtractor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which provider the app boots with.
 *
 * <p>The failure worth preventing is a silent one: a provider selected with no key would leave every
 * recognition reporting "unavailable", one master at a time, with nothing to connect it to a config
 * change. It is called out at boot instead — but recognition is the only thing that stops. An
 * earlier version threw here, which took the whole application down over a setting that estimates,
 * PDFs, the portal and billing never touch, and twice failed an entire CI suite.</p>
 */
class AiProviderConfigTest {

    private final AiProviderConfig config = new AiProviderConfig();
    private static final AnthropicProperties ANTHROPIC =
            new AnthropicProperties("sk-ant-x", "claude-opus-4-8", 8000);
    private static final OpenAiProperties WITH_KEY = new OpenAiProperties("sk-x", "gpt-5.6", 8000);
    private static final OpenAiProperties NO_KEY = new OpenAiProperties("", "gpt-5.6", 8000);

    @Test
    void defaultsToAnthropic() {
        assertThat(config.jsonExtractor(ANTHROPIC, NO_KEY, "anthropic"))
                .isInstanceOf(AnthropicJsonExtractor.class);
    }

    @Test
    void selectsOpenAiWhenAsked() {
        assertThat(config.jsonExtractor(ANTHROPIC, WITH_KEY, "openai"))
                .isInstanceOf(OpenAiJsonExtractor.class);
    }

    @Test
    void caseDoesNotMatter() {
        assertThat(config.jsonExtractor(ANTHROPIC, WITH_KEY, "OpenAI"))
                .isInstanceOf(OpenAiJsonExtractor.class);
    }

    @Test
    void openAiWithoutAKeyDisablesRecognitionAndNothingElse() {
        JsonExtractor extractor = config.jsonExtractor(ANTHROPIC, NO_KEY, "openai");

        assertThat(extractor).isInstanceOf(MisconfiguredJsonExtractor.class);
        // The reason travels with it: it is logged at boot, on every attempt, and readable here.
        assertThat(extractor.providerName()).contains("OPENAI_API_KEY");
        // A call still fails — loudly, with the ordinary user-facing code — rather than silently
        // going to a provider the operator did not choose.
        assertThatThrownBy(() -> extractor.requestJson(AiInput.text("x"), "system", Map.of()))
                .isInstanceOf(AiExtractionException.class)
                .hasMessage("error.ai.unavailable");
    }

    @Test
    void anAnthropicKeyIsNotREQUIRED_localDevRunsWithoutOne() {
        // Blank keys in dev were always normal; this only makes the consequence visible at boot.
        JsonExtractor extractor = config.jsonExtractor(
                new AnthropicProperties("", "claude-opus-4-8", 8000), NO_KEY, "anthropic");

        assertThat(extractor).isInstanceOf(MisconfiguredJsonExtractor.class);
        assertThat(extractor.providerName()).contains("ANTHROPIC_API_KEY");
    }

    @Test
    void anEmptyOrMissingValueIsTreatedAsUnset() {
        // The regression this exists for: `AI_PROVIDER=` with nothing after it is a property that
        // EXISTS, so @Value's `:anthropic` default never fires and an empty string reached the
        // unknown-provider check — failing the Spring context, and with it every integration test in
        // the suite, on a runner that had simply never configured a provider.
        assertThat(config.jsonExtractor(ANTHROPIC, NO_KEY, "")).isInstanceOf(AnthropicJsonExtractor.class);
        assertThat(config.jsonExtractor(ANTHROPIC, NO_KEY, "   ")).isInstanceOf(AnthropicJsonExtractor.class);
        assertThat(config.jsonExtractor(ANTHROPIC, NO_KEY, null)).isInstanceOf(AnthropicJsonExtractor.class);
    }

    @Test
    void surroundingWhitespaceDoesNotChangeTheChoice() {
        // A trailing space in a .env line is invisible in a diff and would otherwise read as a typo.
        assertThat(config.jsonExtractor(ANTHROPIC, WITH_KEY, " openai "))
                .isInstanceOf(OpenAiJsonExtractor.class);
    }

    @Test
    void aTypoInTheProviderNameStopsRecognition_neverFallsBackQuietly() {
        // Falling back to Anthropic would make a comparison run look like it used OpenAI, and the
        // results would be attributed to the wrong model — the one outcome that makes data lie.
        JsonExtractor extractor = config.jsonExtractor(ANTHROPIC, WITH_KEY, "opeanai");

        assertThat(extractor).isInstanceOf(MisconfiguredJsonExtractor.class);
        assertThat(extractor.providerName()).contains("opeanai");
    }
}
