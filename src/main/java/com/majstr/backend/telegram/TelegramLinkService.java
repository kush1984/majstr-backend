package com.majstr.backend.telegram;

import com.majstr.backend.config.BotProperties;
import com.majstr.backend.entity.TelegramLink;
import com.majstr.backend.entity.TelegramLinkCode;
import com.majstr.backend.repository.TelegramLinkCodeRepository;
import com.majstr.backend.repository.TelegramLinkRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Binding a Telegram chat to a master. The PWA asks for a deep link carrying a one-time code; the
 * master opens it, Telegram sends {@code /start <code>}, and the chat is his. One chat per master and
 * one master per chat — a second bind moves the chat, it never shares it.
 */
@Service
@RequiredArgsConstructor
public class TelegramLinkService {

    static final Duration CODE_TTL = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TelegramLinkRepository linkRepository;
    private final TelegramLinkCodeRepository codeRepository;
    private final BotProperties props;
    private final UserRepository userRepository;

    /** {@code notifications}: the app's notifications also go to the chat (V226) — the PWA's switch. */
    public record Status(boolean available, boolean linked, String username, String botUsername,
                         boolean notifications) {}

    @Transactional(readOnly = true)
    public Status status(UUID userId) {
        Optional<TelegramLink> link = linkRepository.findById(userId);
        boolean notify = userRepository.findById(userId).map(User::isTelegramNotify).orElse(false);
        return new Status(available(), link.isPresent(), link.map(TelegramLink::getUsername).orElse(null),
                props.telegramUsername(), notify);
    }

    /** The «Сповіщення в Telegram» switch — the caller's own row only. */
    @Transactional
    public Status setNotify(UUID userId, boolean enabled) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setTelegramNotify(enabled);
        return status(userId);
    }

    /** Where a notification for this master goes: his chat, when he has one AND keeps notifications on. */
    @Transactional(readOnly = true)
    public Optional<Long> notifyChat(UUID userId) {
        boolean on = userRepository.findById(userId).map(User::isTelegramNotify).orElse(false);
        return on ? chatFor(userId) : Optional.empty();
    }

    /** A fresh deep link; null when the bot is not configured here. Earlier codes stop working. */
    @Transactional
    public String createLink(UUID userId) {
        if (!available()) {
            return null;
        }
        codeRepository.deleteForUserOrExpired(userId, Instant.now());
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        codeRepository.save(TelegramLinkCode.builder()
                .code(code).userId(userId).expiresAt(Instant.now().plus(CODE_TTL)).build());
        return "https://t.me/" + props.telegramUsername() + "?start=" + code;
    }

    /** {@code /start <code>}: the master behind the code, if the code is live. The code is spent. */
    @Transactional
    public Optional<UUID> bind(String code, long chatId, String username) {
        Optional<TelegramLinkCode> found = codeRepository.findById(code);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        codeRepository.delete(found.get());
        if (found.get().getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        UUID userId = found.get().getUserId();
        linkRepository.findByChatId(chatId).ifPresent(linkRepository::delete);
        linkRepository.findById(userId).ifPresent(linkRepository::delete);
        linkRepository.flush();
        linkRepository.save(TelegramLink.builder()
                .userId(userId).chatId(chatId).username(username).linkedAt(Instant.now()).build());
        return Optional.of(userId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> userForChat(long chatId) {
        return linkRepository.findByChatId(chatId).map(TelegramLink::getUserId);
    }

    @Transactional(readOnly = true)
    public Optional<Long> chatFor(UUID userId) {
        return linkRepository.findById(userId).map(TelegramLink::getChatId);
    }

    /**
     * «📱 Поділитися номером» (V226): the master's own number, kept apart from the profile's phone — it is for
     * us to reach him, never printed. A second share overwrites the first. False when it is not a phone.
     */
    @Transactional
    public boolean saveContactPhone(UUID userId, String raw) {
        String phone = ContactPhone.normalize(raw);
        if (phone == null) {
            return false;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setContactPhone(phone);
        user.setContactPhoneAt(Instant.now());
        return true;
    }

    @Transactional
    public void unlink(UUID userId) {
        linkRepository.findById(userId).ifPresent(linkRepository::delete);
    }

    @Transactional
    public void unlinkChat(long chatId) {
        linkRepository.findByChatId(chatId).ifPresent(linkRepository::delete);
    }

    private boolean available() {
        return props.enabled() && props.telegramUsername() != null && !props.telegramUsername().isBlank();
    }
}
