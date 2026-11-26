package com.majstr.backend.telegram;

import com.majstr.backend.config.BotProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The bot's notifications (iteration-master-contact): who gets one, and what a blocked bot does. */
class TelegramNotifierTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramLinkService links = mock(TelegramLinkService.class);
    private final UUID master = UUID.randomUUID();

    private TelegramNotifier notifier(String appUrl) {
        return new TelegramNotifier(client, links,
                new BotProperties("token", "majstr_bot", "secret", "webhook", 60, appUrl));
    }

    @Test
    void aLinkedChatWithNotificationsOn_getsTheMessage_withAnOpenButton() {
        given(links.notifyChat(master)).willReturn(Optional.of(42L));
        given(client.deliver(anyLong(), anyString(), any())).willReturn(TelegramClient.Delivery.SENT);

        notifier("https://app.majstr.pro/").send(master, "✍️ Клієнт підписав кошторис", "/estimates/1");

        verify(client).deliver(eq(42L), eq("✍️ Клієнт підписав кошторис"),
                eq(List.of(List.of(TelegramClient.Button.link("Відкрити в Majstr", "https://app.majstr.pro/estimates/1")))));
        verify(links, never()).unlinkChat(anyLong());
    }

    @Test
    void noChat_orNotificationsOff_sendsNothing() {
        // notifyChat is empty both when there is no chat and when the master switched notifications off.
        given(links.notifyChat(master)).willReturn(Optional.empty());

        notifier("https://app.majstr.pro").send(master, "текст", "/projects/1");

        verify(client, never()).deliver(anyLong(), anyString(), any());
    }

    @Test
    void aBlockedBot_unlinksTheChat() {
        given(links.notifyChat(master)).willReturn(Optional.of(7L));
        given(client.deliver(anyLong(), anyString(), any())).willReturn(TelegramClient.Delivery.BLOCKED);

        notifier("https://app.majstr.pro").send(master, "текст", "/projects/1");

        verify(links).unlinkChat(7L);
    }

    @Test
    void anOrdinaryFailure_keepsTheChat_andNothingIsThrown() {
        given(links.notifyChat(master)).willReturn(Optional.of(7L));
        given(client.deliver(anyLong(), anyString(), any())).willThrow(new IllegalStateException("telegram down"));

        assertThatCode(() -> notifier("https://app.majstr.pro").send(master, "текст", "/projects/1"))
                .doesNotThrowAnyException();
        verify(links, never()).unlinkChat(anyLong());
    }

    @Test
    void offHttps_theLinkRidesInTheText_becauseTelegramRefusesALocalhostButton() {
        given(links.notifyChat(master)).willReturn(Optional.of(42L));
        given(client.deliver(anyLong(), anyString(), any())).willReturn(TelegramClient.Delivery.SENT);

        notifier("http://localhost:5173").send(master, "текст", "/projects/1");

        verify(client).deliver(eq(42L), contains("http://localhost:5173/projects/1"), isNull());
    }

    @Test
    void aBotSwitchedOff_isNeverAsked() {
        new TelegramNotifier(client, links, new BotProperties(null, null, null, "off", 60, null))
                .send(master, "текст", "/projects/1");

        verify(links, never()).notifyChat(any());
    }
}
