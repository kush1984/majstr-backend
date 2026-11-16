package com.majstr.backend.telegram;

import com.majstr.backend.config.BotProperties;
import com.majstr.backend.config.PortalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import jakarta.annotation.PreDestroy;

/**
 * Connects the bot to Telegram once the app is up: {@code webhook} mode registers our URL and secret;
 * {@code polling} mode (local dev, no public URL) drops any webhook and long-polls on one daemon
 * thread. {@code off} or no token does nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramStartup {

    private final BotProperties props;
    private final PortalProperties portal;
    private final TelegramClient client;
    private final TelegramBot bot;

    private volatile boolean running;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (props.webhook()) {
            if (props.webhookSecret() == null || props.webhookSecret().isBlank()) {
                log.warn("Telegram webhook mode without TELEGRAM_WEBHOOK_SECRET — not registering");
                return;
            }
            boolean registered = client.setWebhook(
                    portal.publicBaseUrl().replaceAll("/+$", "") + TelegramWebhookController.PATH, props.webhookSecret());
            if (registered) {
                log.info("Telegram bot @{}: webhook registered", props.telegramUsername());
            } else {
                // The secret may use only A-Z, a-z, 0-9, _ and -; anything else and Telegram says no.
                log.warn("Telegram bot @{}: setWebhook was REFUSED — the bot receives nothing", props.telegramUsername());
            }
        } else if (props.polling()) {
            client.deleteWebhook();
            running = true;
            Thread poller = new Thread(this::poll, "telegram-poller");
            poller.setDaemon(true);
            poller.start();
            log.info("Telegram bot @{}: long polling", props.telegramUsername());
        } else {
            log.info("Telegram bot: off");
        }
    }

    @PreDestroy
    void stop() {
        running = false;
    }

    private void poll() {
        long offset = 0;
        while (running) {
            JsonNode updates = client.getUpdates(offset);
            if (updates == null) {
                sleep(); // a failed call: back off instead of spinning
                continue;
            }
            for (JsonNode update : updates) {
                offset = Math.max(offset, update.path("update_id").asLong() + 1);
                bot.handle(update);
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
