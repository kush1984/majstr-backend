package com.majstr.backend.config;

import com.majstr.backend.service.ai.OpenAiJsonExtractor;
import com.majstr.backend.service.importer.ClaudeEstimateExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which provider the app boots with.
 *
 * <p>The failure worth preventing is a silent one: selecting openai with no key would leave every
 * recognition reporting "unavailable" at runtime, one master at a time, with nothing to connect it to
 * a config change. Failing at boot turns that into a deploy that does not start.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiProviderConfigTest {

    @Mock ClaudeEstimateExtractor anthropic;

    private final AiProviderConfig config = new AiProviderConfig();
    private static final OpenAiProperties WITH_KEY = new OpenAiProperties("sk-x", "gpt-5.6", 8000);
    private static final OpenAiProperties NO_KEY = new OpenAiProperties("", "gpt-5.6", 8000);

    @Test
    void defaultsToAnthropic() {
        assertThat(config.jsonExtractor(anthropic, NO_KEY, "anthropic")).isSameAs(anthropic);
    }

    @Test
    void selectsOpenAiWhenAsked() {
        assertThat(config.jsonExtractor(anthropic, WITH_KEY, "openai"))
                .isInstanceOf(OpenAiJsonExtractor.class);
    }

    @Test
    void caseDoesNotMatter() {
        assertThat(config.jsonExtractor(anthropic, WITH_KEY, "OpenAI"))
                .isInstanceOf(OpenAiJsonExtractor.class);
    }

    @Test
    void openAiWithoutAKeyRefusesToBoot() {
        assertThatThrownBy(() -> config.jsonExtractor(anthropic, NO_KEY, "openai"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void anEmptyOrMissingValueIsTreatedAsUnset() {
        // The regression this exists for: `AI_PROVIDER=` with nothing after it is a property that
        // EXISTS, so @Value's `:anthropic` default never fires and an empty string reached the
        // unknown-provider check — failing the Spring context, and with it every integration test in
        // the suite, on a runner that had simply never configured a provider.
        assertThat(config.jsonExtractor(anthropic, NO_KEY, "")).isSameAs(anthropic);
        assertThat(config.jsonExtractor(anthropic, NO_KEY, "   ")).isSameAs(anthropic);
        assertThat(config.jsonExtractor(anthropic, NO_KEY, null)).isSameAs(anthropic);
    }

    @Test
    void surroundingWhitespaceDoesNotChangeTheChoice() {
        // A trailing space in a .env line is invisible in a diff and would otherwise read as a typo.
        assertThat(config.jsonExtractor(anthropic, WITH_KEY, " openai "))
                .isInstanceOf(OpenAiJsonExtractor.class);
    }

    @Test
    void aTypoInTheProviderNameRefusesToBoot() {
        // Silently falling back to Anthropic would make a comparison run look like it used OpenAI —
        // the results would be attributed to the wrong model.
        assertThatThrownBy(() -> config.jsonExtractor(anthropic, WITH_KEY, "opeanai"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown app.ai.provider");
    }
}
