package com.majstr.backend.telegram;

import com.majstr.backend.config.BotProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TelegramWebhookControllerTest {

    private final JsonNode update = JsonMapper.builder().build().readTree("{\"update_id\":1}");
    private final TelegramBot bot = mock(TelegramBot.class);

    @Test
    void theRightSecretIsHandedOn() {
        TelegramWebhookController controller = new TelegramWebhookController(props("webhook"), bot);

        assertThat(controller.receive("s3cret", update).getStatusCode().value()).isEqualTo(200);
        verify(bot).handleAsync(update);
    }

    @Test
    void anOrdinaryMessageIsStoredBeforeTheAnswer_andOnlyItsParseGoesToThePool() {
        TelegramBot.Stored stored = new TelegramBot.Stored(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 7L);
        org.mockito.Mockito.when(bot.store(update)).thenReturn(java.util.Optional.of(stored));
        TelegramWebhookController controller = new TelegramWebhookController(props("webhook"), bot);

        assertThat(controller.receive("s3cret", update).getStatusCode().value()).isEqualTo(200);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bot);
        order.verify(bot).store(update);
        order.verify(bot).parseAsync(stored);
        verify(bot, never()).handleAsync(any());
    }

    @Test
    void aButtonTapGoesToItsOwnPool_neverBehindAParse() {
        JsonNode tap = JsonMapper.builder().build().readTree("{\"update_id\":2,\"callback_query\":{\"id\":\"q\"}}");
        TelegramWebhookController controller = new TelegramWebhookController(props("webhook"), bot);

        assertThat(controller.receive("s3cret", tap).getStatusCode().value()).isEqualTo(200);
        verify(bot).tapAsync(tap);
        verify(bot, never()).store(any());
        verify(bot, never()).handleAsync(any());
    }

    @Test
    void aForwardedMessageIsRecognised_inBothShapesTelegramSends() {
        var json = JsonMapper.builder().build();
        assertThat(TelegramBot.isForwarded(json.readTree("{\"text\":\"a\",\"forward_origin\":{\"type\":\"user\"}}"))).isTrue();
        assertThat(TelegramBot.isForwarded(json.readTree("{\"text\":\"a\",\"forward_date\":1}"))).isTrue();
        assertThat(TelegramBot.isForwarded(json.readTree("{\"text\":\"a\"}"))).isFalse();
    }

    @Test
    void aWrongOrMissingSecretIsNotFound() {
        TelegramWebhookController controller = new TelegramWebhookController(props("webhook"), bot);

        assertThat(controller.receive("guess", update).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.receive(null, update).getStatusCode().value()).isEqualTo(404);
        verify(bot, never()).handleAsync(any());
    }

    @Test
    void outsideWebhookModeNothingIsServed() {
        TelegramWebhookController controller = new TelegramWebhookController(props("polling"), bot);

        assertThat(controller.receive("s3cret", update).getStatusCode().value()).isEqualTo(404);
        verify(bot, never()).handleAsync(any());
    }

    private static BotProperties props(String mode) {
        return new BotProperties("token", "majstr_bot", "s3cret", mode, 60, "https://majstr.pro");
    }
}
