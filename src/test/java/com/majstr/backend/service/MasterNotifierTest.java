package com.majstr.backend.service;

import com.majstr.backend.entity.User;
import com.majstr.backend.push.PushService;
import com.majstr.backend.telegram.TelegramNotifier;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The one door for telling a master something (iteration-master-contact). */
class MasterNotifierTest {

    private final PushService push = mock(PushService.class);
    private final TelegramNotifier telegram = mock(TelegramNotifier.class);
    private final MasterNotifier notifier = new MasterNotifier(push, telegram, new AfterCommit());
    private final User master = User.builder().id(UUID.randomUUID()).build();

    @Test
    void bothChannelsCarryTheNews_eachInItsOwnWords() {
        notifier.notify(master, new MasterNotifier.Notice("Підписано", "Олена · 12 000 ₴", "/estimates/1",
                "✍️ Клієнт підписав кошторис"));

        verify(push).sendToUser(master, "Підписано", "Олена · 12 000 ₴", "/estimates/1");
        verify(telegram).send(master.getId(), "✍️ Клієнт підписав кошторис", "/estimates/1");
    }

    @Test
    void aNoticeWithNoTelegramLine_isPushOnly() {
        notifier.notify(master, new MasterNotifier.Notice("Файли будуть видалені", "…", "/projects/1", null));

        verify(push).sendToUser(any(), anyString(), anyString(), anyString());
        verify(telegram, never()).send(any(), anyString(), anyString());
    }

    @Test
    void oneChannelFailing_neitherThrowsNorCostsTheOther() {
        willThrow(new IllegalStateException("push pool full")).given(push).sendToUser(any(), any(), any(), any());
        willThrow(new IllegalStateException("telegram pool full")).given(telegram).send(any(), any(), any());

        assertThatCode(() -> notifier.notify(master,
                new MasterNotifier.Notice("t", "b", "/projects/1", "tg"))).doesNotThrowAnyException();
        verify(telegram).send(master.getId(), "tg", "/projects/1");
    }
}
