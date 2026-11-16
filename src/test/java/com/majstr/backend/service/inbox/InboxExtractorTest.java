package com.majstr.backend.service.inbox;

import com.majstr.backend.exception.AiExtractionException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboxExtractorTest {

    private final InboxExtractor extractor = new InboxExtractor(null, JsonMapper.builder().build());

    @Test
    void aSumAboveWhatAnyMoneyFormTakesIsAMisreading() {
        List<InboxExtractor.Parsed> parsed = extractor.parse("""
                {"items":[{"kind":"income","object":0,"objectMentioned":false,"text":"оплата","quantity":0,
                  "unit":"","unitPrice":0,"amount":1000000000,"category":""},
                 {"kind":"income","object":0,"objectMentioned":false,"text":"оплата","quantity":0,
                  "unit":"","unitPrice":0,"amount":100000000,"category":""}]}""", 0);

        assertThat(parsed.get(0).amount()).isNull();
        assertThat(parsed.get(1).amount()).isEqualByComparingTo(new BigDecimal("100000000"));
    }

    @Test
    void readsItemsAndTurnsSentinelsIntoAbsence() {
        List<InboxExtractor.Parsed> parsed = extractor.parse("""
                {"items":[{"kind":"shopping","object":2,"objectMentioned":true,"text":"клей","quantity":3,
                  "unit":"шт","unitPrice":0,"amount":0,"category":""}]}""", 2);

        InboxExtractor.Parsed p = parsed.get(0);
        assertThat(p.kind()).isEqualTo("SHOPPING");
        assertThat(p.object()).isEqualTo(2);
        assertThat(p.objectMentioned()).isTrue();
        assertThat(p.quantity()).isEqualByComparingTo(BigDecimal.valueOf(3));
        assertThat(p.unitPrice()).isNull();
        assertThat(p.amount()).isNull();
        assertThat(p.category()).isNull();
    }

    @Test
    void anObjectNumberOutsideTheListNamesNothing_andCannotBeMentioned() {
        List<InboxExtractor.Parsed> parsed = extractor.parse("""
                {"items":[{"kind":"TASK","object":7,"objectMentioned":true,"text":"двері","quantity":0,
                  "unit":"","unitPrice":0,"amount":0,"category":""}]}""", 2);

        assertThat(parsed.get(0).object()).isZero();
        assertThat(parsed.get(0).objectMentioned()).isFalse();
    }

    @Test
    void theModelSeesTheObjectsNumbered() {
        String input = InboxExtractor.input("на бандери клей", List.of(
                new InboxExtractor.ObjectRef(1, "Бандери 12", "вул. Бандери 12", "Ігор")));

        assertThat(input).contains("1. Бандери 12 — адреса: вул. Бандери 12 — клієнт: Ігор")
                .endsWith("MESSAGE:\nна бандери клей");
    }

    @Test
    void unusableJsonIsAnAiFailure() {
        assertThatThrownBy(() -> extractor.parse("not json", 0)).isInstanceOf(AiExtractionException.class);
    }
}
