package com.majstr.backend.service.ai;

import com.majstr.backend.config.AiFlowsProperties;
import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.config.OpenAiProperties;
import com.majstr.backend.exception.AiExtractionException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiFlowBudgetTest {

    private final AtomicInteger modelCalls = new AtomicInteger();

    private final JsonExtractor counting = new JsonExtractor() {
        @Override
        public String requestJson(List<AiInput> input, String systemPrompt, Map<String, Object> schema) {
            modelCalls.incrementAndGet();
            return "{}";
        }

        @Override
        public String providerName() {
            return "counting";
        }
    };

    private AiExtractors extractors(int callsPerHour) {
        return new AiExtractors(new AiFlowsProperties(null, null, null),
                new AnthropicProperties("", "m", 1), new OpenAiProperties("", "m", 1, null), counting,
                new AiFlowBudget(callsPerHour));
    }

    @Test
    void pastItsHourlyBudgetAFlowFailsClosed_andTheModelIsNeverCalled() {
        AiExtractors extractors = extractors(2);

        extractors.forFlow(AiFlow.RECEIPT).requestJson(List.of(), "", Map.of());
        extractors.forFlow(AiFlow.RECEIPT).requestJson(List.of(), "", Map.of());

        assertThatThrownBy(() -> extractors.forFlow(AiFlow.RECEIPT).requestJson(List.of(), "", Map.of()))
                .isInstanceOf(AiExtractionException.class);
        assertThat(modelCalls).hasValue(2);
    }

    @Test
    void oneFlowSpendingItsBudgetLeavesTheOthersAlone() {
        AiExtractors extractors = extractors(1);

        extractors.forFlow(AiFlow.RECEIPT).requestJson(List.of(), "", Map.of());
        assertThatThrownBy(() -> extractors.forFlow(AiFlow.RECEIPT).requestJson(List.of(), "", Map.of()))
                .isInstanceOf(AiExtractionException.class);

        assertThat(extractors.forFlow(AiFlow.DICTATION).requestJson(List.of(), "", Map.of())).isEqualTo("{}");
    }

    @Test
    void askingWhichProviderAnswersSpendsNothing() {
        AiExtractors extractors = extractors(1);

        assertThat(extractors.forFlow(AiFlow.SKETCH).providerName()).isEqualTo("counting");
        assertThat(extractors.forFlow(AiFlow.SKETCH).providerName()).isEqualTo("counting");
        assertThat(extractors.forFlow(AiFlow.SKETCH).requestJson(List.of(), "", Map.of())).isEqualTo("{}");
    }
}
