package com.majstr.backend.service;

import com.majstr.backend.entity.User;
import com.majstr.backend.push.PushService;
import com.majstr.backend.telegram.TelegramNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The ONE door for telling a master something happened (iteration-master-contact): the web push as before,
 * and the same news in his Telegram chat when the bot is linked and «Сповіщення в Telegram» is on.
 *
 * <p>Everything leaves after the commit ({@link AfterCommit}): a signature that rolls back must not have
 * told him «підписано» on either channel. Both channels are asynchronous and fail-soft, so neither can slow
 * down or fail the operation that caused it.</p>
 */
@Component
@RequiredArgsConstructor
public class MasterNotifier {

    private final PushService pushService;
    private final TelegramNotifier telegramNotifier;
    private final AfterCommit afterCommit;

    /**
     * @param title    the push title
     * @param body     the push body
     * @param url      the PWA path both open ({@code /projects/…}, {@code /estimates/…}, {@code /profile})
     * @param telegram the bot's message — shorter, no client's words or contact details in it;
     *                 null keeps the notice to the push only
     */
    public record Notice(String title, String body, String url, String telegram) {}

    public void notify(User master, Notice notice) {
        UUID masterId = master.getId(); // read now: after the commit the entity is detached
        // Two separate after-commit actions: one channel throwing (a full pool) must not cost the other.
        afterCommit.run(() -> pushService.sendToUser(master, notice.title(), notice.body(), notice.url()));
        if (notice.telegram() != null) {
            afterCommit.run(() -> telegramNotifier.send(masterId, notice.telegram(), notice.url()));
        }
    }
}
