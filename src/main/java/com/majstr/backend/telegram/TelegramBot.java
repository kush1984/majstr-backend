package com.majstr.backend.telegram;

import com.majstr.backend.config.BackgroundErrors;
import com.majstr.backend.config.BotProperties;
import com.majstr.backend.entity.InboxEntry;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.service.inbox.InboxService;
import com.majstr.backend.service.inbox.VoiceAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One Telegram update in, the bot's answer out. Messages become inbox entries; buttons answer them.
 * Only private chats are served — a group would mix several people's objects into one master's
 * inbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramBot {

    private final TelegramClient client;
    private final TelegramLinkService links;
    private final InboxService inbox;
    private final BotProperties props;
    private final VoiceAccess voiceAccess;

    /** «Скасувати» / «Інший обʼєкт» under a message this old are refused: a stray tap a week on deletes nothing. */
    static final Duration ANSWER_WINDOW = Duration.ofHours(48);

    /** An ordinary message from a linked chat, stored and waiting for its parse. */
    public record Stored(UUID ownerId, UUID entryId, long chatId, boolean voice) {
        public Stored(UUID ownerId, UUID entryId, long chatId) {
            this(ownerId, entryId, chatId, false);
        }
    }

    /**
     * The webhook's first step, ON the request thread: an ordinary message from a linked private chat is
     * stored before Telegram hears «200» — a full queue or a restart must not lose it (review M-4). Empty for
     * everything else (commands, callbacks, strangers, a redelivery already stored), which
     * {@link #handleAsync} answers as before. A failure here is a 5xx, and Telegram delivers again.
     */
    public Optional<Stored> store(JsonNode update) {
        JsonNode message = update.get("message");
        if (message == null || message.isNull()) {
            return Optional.empty();
        }
        JsonNode chat = message.path("chat");
        if (!"private".equals(chat.path("type").asString(""))) {
            return Optional.empty();
        }
        String text = message.path("text").asString("").trim();
        long chatId = chat.path("id").asLong();
        if (message.hasNonNull("voice")) {
            // Admitted here, stored here; a refusal is answered by handleAsync, which asks again.
            Optional<UUID> owner = links.userForChat(chatId);
            JsonNode voice = message.get("voice");
            int seconds = voice.path("duration").asInt(0);
            if (owner.isEmpty() || voiceAccess.admit(owner.get(), seconds) != VoiceAccess.Verdict.OK) {
                return Optional.empty();
            }
            InboxService.Outcome received = inbox.receiveVoice(owner.get(), update.path("update_id").asLong(), chatId,
                    voice.path("file_id").asString(), seconds, isForwarded(message));
            return received.fresh()
                    ? Optional.of(new Stored(owner.get(), received.entry().id(), chatId, true))
                    : Optional.empty();
        }
        if (text.isEmpty() || isCommand(text)) {
            return Optional.empty();
        }
        Optional<UUID> user = links.userForChat(chatId);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        InboxService.Outcome received = inbox.receive(user.get(), InboxEntry.Source.TELEGRAM, text,
                update.path("update_id").asLong(), chatId, null, isForwarded(message));
        return received.fresh()
                ? Optional.of(new Stored(user.get(), received.entry().id(), chatId))
                : Optional.empty();
    }

    /** The parse of a message {@link #store} kept: the model call, off the webhook's thread. */
    @Async("botExecutor")
    public void parseAsync(Stored stored) {
        try {
            Long working = client.sendMessage(stored.chatId(),
                    stored.voice() ? TelegramReplies.LISTENING : TelegramReplies.WORKING, null);
            InboxService.Outcome done = inbox.process(stored.ownerId(), stored.entryId());
            show(stored.chatId(), working, done, stored.ownerId());
        } catch (RuntimeException e) {
            log.warn("Telegram parse of entry {} failed: {}", stored.entryId(), e.getMessage());
            BackgroundErrors.capture("telegram-parse", e);
        }
    }

    /** Everything that is not a message to store or a button tap: commands, strangers, redeliveries. */
    @Async("botExecutor")
    public void handleAsync(JsonNode update) {
        handle(update);
    }

    /**
     * A button tap: database work only, so it has its own pool and never queues behind a model call — a tap
     * that hangs gets tapped again (review S-12).
     */
    @Async("botTapExecutor")
    public void tapAsync(JsonNode update) {
        handle(update);
    }

    /** Someone else's words the master passed on (Bot API 7+: forward_origin; older: forward_*). */
    static boolean isForwarded(JsonNode message) {
        return message.hasNonNull("forward_origin") || message.hasNonNull("forward_from")
                || message.hasNonNull("forward_from_chat") || message.hasNonNull("forward_sender_name")
                || message.hasNonNull("forward_date");
    }

    private static boolean isCommand(String text) {
        return text.startsWith("/start") || text.equals("/help") || text.equals("/stop") || text.equals("/phone")
                // The contact keyboard's «Не зараз» is an answer to the bot, never a note for «Вхідні».
                || text.equals(TelegramReplies.SKIP_CONTACT_BUTTON);
    }

    private void askForContact(long chatId, String text) {
        client.askForContact(chatId, text, TelegramReplies.SHARE_CONTACT_BUTTON, TelegramReplies.SKIP_CONTACT_BUTTON,
                TelegramReplies.CONTACT_PLACEHOLDER);
    }

    public void handle(JsonNode update) {
        try {
            if (update.hasNonNull("message")) {
                onMessage(update.path("update_id").asLong(), update.get("message"));
            } else if (update.hasNonNull("callback_query")) {
                onCallback(update.get("callback_query"));
            }
        } catch (RuntimeException e) {
            log.warn("Telegram update {} failed: {}", update.path("update_id").asLong(), e.getMessage());
            BackgroundErrors.capture("telegram-update", e);
        }
    }

    private void onMessage(long updateId, JsonNode message) {
        JsonNode chat = message.path("chat");
        if (!"private".equals(chat.path("type").asString(""))) {
            return;
        }
        long chatId = chat.path("id").asLong();
        String text = message.path("text").asString("").trim();
        Optional<UUID> user = links.userForChat(chatId);

        if (text.startsWith("/start")) {
            String code = text.substring("/start".length()).trim();
            if (code.isEmpty()) {
                client.sendMessage(chatId, user.isPresent() ? TelegramReplies.LINKED : TelegramReplies.HELLO_UNLINKED, null);
                return;
            }
            String username = message.path("from").path("username").asString(null);
            boolean bound = links.bind(code, chatId, username).isPresent();
            client.sendMessage(chatId, bound ? TelegramReplies.LINKED : TelegramReplies.LINK_EXPIRED, null);
            if (bound) {
                askForContact(chatId, TelegramReplies.ASK_CONTACT);
            }
            return;
        }
        if (user.isEmpty()) {
            client.sendMessage(chatId, TelegramReplies.HELLO_UNLINKED, null);
            return;
        }
        if (message.hasNonNull("contact")) {
            onContact(chatId, user.get(), message);
            return;
        }
        if (text.equals("/phone")) {
            askForContact(chatId, TelegramReplies.ASK_CONTACT);
            return;
        }
        if (text.equals(TelegramReplies.SKIP_CONTACT_BUTTON)) {
            client.sendRemovingKeyboard(chatId, TelegramReplies.CONTACT_SKIPPED);
            return;
        }
        if (text.equals("/help")) {
            client.sendMessage(chatId, TelegramReplies.LINKED, null);
            return;
        }
        if (text.equals("/stop")) {
            links.unlinkChat(chatId);
            client.sendMessage(chatId, "Бота відключено. Підключити знову можна в застосунку: Профіль → «Telegram-бот».", null);
            return;
        }
        if (message.hasNonNull("voice")) {
            onVoice(updateId, chatId, user.get(), message);
            return;
        }
        if (text.isEmpty()) {
            client.sendMessage(chatId, TelegramReplies.ONLY_TEXT, null);
            return;
        }

        UUID ownerId = user.get();
        InboxService.Outcome received = inbox.receive(ownerId, InboxEntry.Source.TELEGRAM, text, updateId, chatId,
                null, isForwarded(message));
        if (!received.fresh()) {
            return; // a redelivery: the first delivery already answered
        }
        Long working = client.sendMessage(chatId, TelegramReplies.WORKING, null);
        InboxService.Outcome done = inbox.process(ownerId, received.entry().id());
        show(chatId, working, done, ownerId);
    }

    /**
     * «📱 Поділитися номером» (V226). Only his OWN number is taken: a contact card carries the {@code user_id} of
     * whose number it is, and a forwarded card of a client or a supplier must not become «the master's phone».
     */
    private void onContact(long chatId, UUID ownerId, JsonNode message) {
        JsonNode contact = message.get("contact");
        long from = message.path("from").path("id").asLong(0);
        long owner = contact.path("user_id").asLong(0);
        if (from == 0 || owner != from) {
            client.sendMessage(chatId, TelegramReplies.CONTACT_NOT_YOURS, null);
            return;
        }
        if (!links.saveContactPhone(ownerId, contact.path("phone_number").asString(""))) {
            askForContact(chatId, TelegramReplies.CONTACT_INVALID);
            return;
        }
        client.sendRemovingKeyboard(chatId, TelegramReplies.CONTACT_SAVED);
    }

    /** The polling path, and the webhook's refusals: the same verdict, now answered. */
    private void onVoice(long updateId, long chatId, UUID ownerId, JsonNode message) {
        JsonNode voice = message.get("voice");
        int seconds = voice.path("duration").asInt(0);
        VoiceAccess.Verdict verdict = voiceAccess.admit(ownerId, seconds);
        if (verdict != VoiceAccess.Verdict.OK) {
            TelegramReplies.Reply refusal = TelegramReplies.voiceRefused(verdict, voiceAccess.maxSeconds(), props.appUrl());
            client.sendMessage(chatId, refusal.text(), refusal.keyboard());
            return;
        }
        InboxService.Outcome received = inbox.receiveVoice(ownerId, updateId, chatId,
                voice.path("file_id").asString(), seconds, isForwarded(message));
        if (!received.fresh()) {
            return;
        }
        Long working = client.sendMessage(chatId, TelegramReplies.LISTENING, null);
        InboxService.Outcome done = inbox.process(ownerId, received.entry().id());
        show(chatId, working, done, ownerId);
    }

    private void onCallback(JsonNode callback) {
        String callbackId = callback.path("id").asString();
        long chatId = callback.path("message").path("chat").path("id").asLong();
        long messageId = callback.path("message").path("message_id").asLong();
        String data = callback.path("data").asString("");
        Optional<UUID> user = links.userForChat(chatId);
        if (user.isEmpty()) {
            client.answerCallback(callbackId, "Бот не підключено");
            return;
        }
        UUID ownerId = user.get();
        String[] parts = data.split(":");
        try {
            UUID entryId = TelegramReplies.unpack(parts[1]);
            InboxService.Outcome outcome = switch (parts[0]) {
                case "o" -> inbox.chooseObject(ownerId, entryId,
                        "-".equals(parts[2]) ? null : TelegramReplies.unpack(parts[2]));
                case "c" -> inbox.confirmMoney(ownerId, entryId);
                case "e" -> inbox.createDraft(ownerId, entryId);
                case "r" -> {
                    fresh(inbox.current(ownerId, entryId));
                    yield inbox.moveToObject(ownerId, entryId,
                            "-".equals(parts[2]) ? null : TelegramReplies.unpack(parts[2]));
                }
                case "v" -> inbox.current(ownerId, entryId);
                case "m" -> {
                    InboxService.Outcome now = fresh(inbox.current(ownerId, entryId));
                    TelegramReplies.Reply pick = TelegramReplies.pickObject(now, inbox.objects(ownerId));
                    client.editMessage(chatId, messageId, pick.text(), pick.keyboard());
                    yield null;
                }
                case "u" -> {
                    InboxService.Outcome now = fresh(inbox.current(ownerId, entryId));
                    if (TelegramReplies.filedMoney(now)) {
                        // Money is deleted only on a second, explicit tap.
                        TelegramReplies.Reply ask = TelegramReplies.confirmUndo(now);
                        client.editMessage(chatId, messageId, ask.text(), ask.keyboard());
                        yield null;
                    }
                    yield inbox.undo(ownerId, entryId);
                }
                case "U" -> {
                    fresh(inbox.current(ownerId, entryId));
                    yield inbox.undo(ownerId, entryId);
                }
                default -> null;
            };
            if (outcome == null) {
                client.answerCallback(callbackId, null);
                return;
            }
            client.answerCallback(callbackId, null);
            show(chatId, messageId, outcome, ownerId);
        } catch (ResourceNotFoundException | IllegalArgumentException | ArrayIndexOutOfBoundsException
                 | StaleAnswer e) {
            client.answerCallback(callbackId, "Вже неактуально");
        }
    }

    /** A tap on «Скасувати» / «Інший обʼєкт» older than {@link #ANSWER_WINDOW}. */
    private static final class StaleAnswer extends RuntimeException {
        StaleAnswer() {
            super("stale", null, false, false);
        }
    }

    private static InboxService.Outcome fresh(InboxService.Outcome outcome) {
        if (outcome.entry().createdAt().plus(ANSWER_WINDOW).isBefore(Instant.now())) {
            throw new StaleAnswer();
        }
        return outcome;
    }

    private void show(long chatId, Long messageId, InboxService.Outcome outcome, UUID ownerId) {
        int trialLeft = outcome.entry().voice() && outcome.fresh() ? voiceAccess.trialLeft(ownerId) : -1;
        TelegramReplies.Reply reply = TelegramReplies.outcome(outcome, inbox.objects(ownerId), props.appUrl(), trialLeft);
        if (messageId != null) {
            // A refused edit would leave «Записую…» hanging: say it again, plainly, as a new message.
            if (!client.editMessage(chatId, messageId, reply.text(), reply.keyboard())) {
                client.sendMessage(chatId, reply.text(), null);
            }
        } else {
            client.sendMessage(chatId, reply.text(), reply.keyboard());
        }
    }
}
