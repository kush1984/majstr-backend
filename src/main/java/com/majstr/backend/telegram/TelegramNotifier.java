package com.majstr.backend.telegram;

import com.majstr.backend.config.BackgroundErrors;
import com.majstr.backend.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The bot's outward half (iteration-master-contact): a short notification to the master's linked chat,
 * beside the web push. Only {@code MasterNotifier} calls it, and only after the commit that justifies it.
 *
 * <p>Fail-soft end to end: Telegram being down, slow or refusing never reaches the signature or the
 * payment that caused it. A 403 means the master blocked the bot (or left Telegram) — the chat is unlinked
 * so we stop knocking, and the app's «Telegram-бот» row shows «Підключити» again.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramNotifier {

    private final TelegramClient client;
    private final TelegramLinkService links;
    private final BotProperties props;

    /**
     * @param text the whole message — short, no figure the client would not see, no client's personal data
     * @param path where the «Відкрити» button leads in the PWA ({@code /projects/…}); may be null
     */
    @Async("telegramNotifyExecutor")
    public void send(UUID userId, String text, String path) {
        try {
            if (!props.enabled()) {
                return;
            }
            Optional<Long> chat = links.notifyChat(userId);
            if (chat.isEmpty()) {
                return;
            }
            TelegramClient.Delivery delivery = client.deliver(chat.get(), withLink(text, path), button(path));
            if (delivery == TelegramClient.Delivery.BLOCKED) {
                links.unlinkChat(chat.get());
                log.info("Telegram chat of user {} unlinked: the bot was blocked", userId);
            }
        } catch (RuntimeException e) {
            log.warn("Telegram notification to user {} failed: {}", userId, e.getMessage());
            BackgroundErrors.capture("telegram-notify", e);
        }
    }

    /** A link button needs an https URL — Telegram refuses the whole message over a localhost one. */
    private List<List<TelegramClient.Button>> button(String path) {
        String url = url(path);
        return url != null && url.startsWith("https://")
                ? List.of(List.of(TelegramClient.Button.link("Відкрити в Majstr", url)))
                : null;
    }

    /** Off https (a dev server) the link rides in the text instead, so it is still there to copy. */
    private String withLink(String text, String path) {
        String url = url(path);
        return url != null && !url.startsWith("https://") ? text + "\n" + url : text;
    }

    private String url(String path) {
        String base = props.appUrl();
        if (path == null || base == null || base.isBlank()) {
            return null;
        }
        return base.replaceAll("/+$", "") + path;
    }
}
