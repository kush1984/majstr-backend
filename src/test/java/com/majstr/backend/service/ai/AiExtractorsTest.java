package com.majstr.backend.service.ai;

import com.majstr.backend.config.AiFlowsProperties;
import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.config.OpenAiProperties;
import com.majstr.backend.exception.AiExtractionException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Routing a job to the model that should read it.
 *
 * <p>The property this class must never lose is the boring one: <b>with nothing configured, nothing
 * changes.</b> Everything else here is about not lying — a typo'd vendor must not quietly become the
 * default, because a reading attributed to a model nobody chose is worse than no reading.</p>
 */
class AiExtractorsTest {

    private static final AnthropicProperties ANTHROPIC =
            new AnthropicProperties("sk-ant-x", "claude-opus-4-8", 8000);
    private static final OpenAiProperties OPENAI = new OpenAiProperties("sk-x", "gpt-5.6", 8000, null);

    /** The default extractor the app already had; identity is what the tests assert against. */
    private static final JsonExtractor DEFAULT = new JsonExtractor() {
        @Override
        public String requestJson(List<AiInput> input, String prompt, Map<String, Object> schema) {
            return "{}";
        }

        @Override
        public String providerName() {
            return "the-default";
        }
    };

    private static AiExtractors extractors(Map<String, String> flows) {
        return new AiExtractors(new AiFlowsProperties("anthropic", null, flows),
                ANTHROPIC, OPENAI, DEFAULT);
    }

    @Test
    void withNothingConfiguredEveryFlowGetsTheDefault() {
        AiExtractors extractors = extractors(null);

        for (AiFlow flow : AiFlow.values()) {
            assertThat(extractors.forFlow(flow)).isSameAs(DEFAULT);
        }
    }

    @Test
    void aBlankOverrideIsNoOverride() {
        // The `${AI_FLOW_RECEIPT:}` placeholder shape: the key exists, the value is empty. This is
        // the same trap that once turned an unset provider into 25 failed integration tests.
        AiExtractors extractors = extractors(Map.of("receipt", "", "sketch", "   "));

        assertThat(extractors.forFlow(AiFlow.RECEIPT)).isSameAs(DEFAULT);
        assertThat(extractors.forFlow(AiFlow.SKETCH)).isSameAs(DEFAULT);
    }

    @Test
    void aFlowCanNameItsOwnVendorAndModel() {
        // The point of the exercise: the frequent, cheap job on a cheap model, the heavy one on the
        // strongest — while every other flow stays exactly where it was.
        AiExtractors extractors = extractors(Map.of(
                "receipt", "anthropic:claude-sonnet-5",
                "project-docs", "openai:gpt-5.6"));

        assertThat(extractors.forFlow(AiFlow.RECEIPT).providerName()).isEqualTo("anthropic:claude-sonnet-5");
        assertThat(extractors.forFlow(AiFlow.PROJECT_DOCS).providerName()).isEqualTo("openai:gpt-5.6");
        assertThat(extractors.forFlow(AiFlow.ESTIMATE)).isSameAs(DEFAULT);
    }

    @Test
    void aBareModelKeepsTheDefaultVendor_andABareVendorKeepsItsOwnModel() {
        // Both are things a person actually writes, and both should mean what they look like.
        AiExtractors extractors = extractors(Map.of(
                "receipt", "claude-sonnet-5",   // model only → the configured vendor
                "sketch", "openai"));           // vendor only → that vendor's own model

        assertThat(extractors.forFlow(AiFlow.RECEIPT).providerName()).isEqualTo("anthropic:claude-sonnet-5");
        assertThat(extractors.forFlow(AiFlow.SKETCH).providerName()).isEqualTo("openai:gpt-5.6");
    }

    @Test
    void twoFlowsOnTheSameModelShareOneClient() {
        AiExtractors extractors = extractors(Map.of(
                "receipt", "anthropic:claude-sonnet-5",
                "estimate", "anthropic:claude-sonnet-5"));

        assertThat(extractors.forFlow(AiFlow.RECEIPT))
                .isSameAs(extractors.forFlow(AiFlow.ESTIMATE));
    }

    @Test
    void aTypoDisablesThatFlowAlone_andSaysWhat() {
        AiExtractors extractors = extractors(Map.of("receipt", "antropic:claude-sonnet-5"));

        JsonExtractor broken = extractors.forFlow(AiFlow.RECEIPT);
        assertThat(broken).isInstanceOf(MisconfiguredJsonExtractor.class);
        assertThat(broken.providerName()).contains("antropic");
        assertThatThrownBy(() -> broken.requestJson(AiInput.text("x"), "system", Map.of()))
                .isInstanceOf(AiExtractionException.class);
        // The other flows are untouched — one wrong line in config is not an outage.
        assertThat(extractors.forFlow(AiFlow.ESTIMATE)).isSameAs(DEFAULT);
    }

    @Test
    void aFlowPointedAtAVendorWithNoKeyIsDisabled_notSilentlyRerouted() {
        AiExtractors extractors = new AiExtractors(
                new AiFlowsProperties("anthropic", null, Map.of("electrical", "openai:gpt-5.6")),
                ANTHROPIC, new OpenAiProperties("", "gpt-5.6", 8000, null), DEFAULT);

        JsonExtractor electrical = extractors.forFlow(AiFlow.ELECTRICAL);
        assertThat(electrical).isInstanceOf(MisconfiguredJsonExtractor.class);
        assertThat(electrical.providerName()).contains("OPENAI_API_KEY");
    }

    @Test
    void theFlowKeyIsTheConfigKey() {
        // PROJECT_DOCS ↔ project-docs: the enum and the yaml have to agree, and nothing else
        // enforces it.
        assertThat(AiFlow.PROJECT_DOCS.key()).isEqualTo("project-docs");
        assertThat(AiFlow.RECEIPT.key()).isEqualTo("receipt");
    }
}
