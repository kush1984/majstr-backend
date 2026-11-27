package com.majstr.backend.service.ai;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechToTextTest {

    @Test
    void aBotRowStartsWithACapital() {
        assertThat(com.majstr.backend.service.inbox.InboxService.capitalized("клей для плитки")).isEqualTo("Клей для плитки");
        assertThat(com.majstr.backend.service.inbox.InboxService.capitalized("ґрунт")).isEqualTo("Ґрунт");
        assertThat(com.majstr.backend.service.inbox.InboxService.capitalized("")).isEmpty();
    }

    @Test
    void silenceEchoesThePrompt_whichIsNotHeard() {
        assertThat(SpeechToText.heard("Будівельні роботи на об'єктах: Тест Б, Тест А.")).isEmpty();
        assertThat(SpeechToText.heard("Будівельні роботи на обʼєктах: Липки.")).isEmpty();
        assertThat(SpeechToText.heard("на липках докупити клей")).isEqualTo("на липках докупити клей");
    }

    @Test
    void thePeopleFollowTheObjects_andGiveWayFirst() {
        assertThat(SpeechToText.prompt(List.of("Липки"), List.of("Василь", "Петро")))
                .startsWith("Будівельні роботи на обʼєктах: Липки. Люди: Василь, Петро. Матеріали: Церезіт");
        String crowded = SpeechToText.prompt(List.of("Липки"), Collections.nCopies(200, "Костянтин"));
        assertThat(crowded.length()).isLessThanOrEqualTo(600);
        assertThat(crowded).contains("Люди: Костянтин").contains("Церезіт (Ceresit)");
        assertThat(SpeechToText.prompt(List.of("Липки"), List.of())).doesNotContain("Люди");
    }

    @Test
    void theObjectNamesAreTheHint_andAShortOne() {
        assertThat(SpeechToText.prompt(List.of("Будинок в Куликові", "Липки")))
                .startsWith("Будівельні роботи на обʼєктах: Будинок в Куликові, Липки. Матеріали: Церезіт");
        String crowded = SpeechToText.prompt(Collections.nCopies(200, "Довга назва обʼєкта"));
        assertThat(crowded.length()).isLessThanOrEqualTo(600);
        // The brands survive a long object list, and the lead stays first (the echo check reads it).
        assertThat(crowded).startsWith("Будівельні роботи на обʼєктах").contains("Церезіт (Ceresit)");
        assertThat(SpeechToText.heard(crowded)).isEmpty();
    }
}
