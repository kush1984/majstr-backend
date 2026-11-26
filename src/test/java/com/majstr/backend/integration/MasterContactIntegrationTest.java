package com.majstr.backend.integration;

import com.majstr.backend.dto.HelpOfferResponse;
import com.majstr.backend.dto.HelpRequest;
import com.majstr.backend.entity.HelpReason;
import com.majstr.backend.entity.TelegramLink;
import com.majstr.backend.repository.TelegramLinkRepository;
import com.majstr.backend.service.HelpOfferService;
import com.majstr.backend.service.ProfileService;
import com.majstr.backend.telegram.TelegramBot;
import com.majstr.backend.telegram.TelegramLinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * iteration-master-contact on a real database: the «Сповіщення в Telegram» switch touches only the caller's row,
 * the bot takes only the master's OWN number, and the setup-help card stands for the two reasons V226 names.
 */
class MasterContactIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired TelegramLinkService links;
    @Autowired TelegramLinkRepository linkRepository;
    @Autowired TelegramBot bot;
    @Autowired HelpOfferService helpOffers;
    @Autowired ProfileService profileService;
    @Autowired ObjectMapper json;

    private UUID master;
    private UUID other;
    private long chat;
    private static final long TELEGRAM_USER = 555_000_111L;

    @BeforeEach
    void seed() {
        master = user("MC1");
        other = user("MC2");
        chat = Math.abs(UUID.randomUUID().getLeastSignificantBits());
        linkRepository.save(TelegramLink.builder().userId(master).chatId(chat).linkedAt(Instant.now()).build());
        linkRepository.save(TelegramLink.builder().userId(other).chatId(chat + 1).linkedAt(Instant.now()).build());
    }

    @AfterEach
    void cleanUp() {
        for (UUID u : new UUID[]{master, other}) {
            jdbc.update("DELETE FROM telegram_link WHERE user_id = ?", u);
            jdbc.update("DELETE FROM pdf_render WHERE owner_id = ?", u);
            jdbc.update("DELETE FROM estimates WHERE project_id IN (SELECT id FROM projects WHERE owner_id = ?)", u);
            jdbc.update("DELETE FROM projects WHERE owner_id = ?", u);
            jdbc.update("DELETE FROM users WHERE id = ?", u);
        }
    }

    private UUID user(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?, ?, ?, 'x', 'Майстер', NULL, 'ФОП', ?)
                """, id, id + "@test.ua", id + "@test.ua", code + id.toString().substring(0, 8));
        return id;
    }

    private void registeredDaysAgo(UUID userId, int days) {
        jdbc.update("UPDATE users SET created_at = now() - make_interval(days => ?) WHERE id = ?", days, userId);
    }

    private UUID object(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO projects (id, owner_id, name, address, status) VALUES (?, ?, 'Квартира', 'вул. 1', 'DRAFT')",
                id, owner);
        return id;
    }

    private void estimate(UUID project, String status) {
        jdbc.update("INSERT INTO estimates (id, project_id, name, status) VALUES (?, ?, 'Кошторис', ?)",
                UUID.randomUUID(), project, status);
    }

    private String column(String column, UUID userId) {
        return jdbc.queryForObject("SELECT " + column + " FROM users WHERE id = ?", String.class, userId);
    }

    private void contact(long fromId, long contactUserId, String phone) {
        bot.handle(json.readTree("""
                {"update_id": 1, "message": {"message_id": 1, "chat": {"id": %d, "type": "private"},
                 "from": {"id": %d}, "contact": {"user_id": %d, "phone_number": "%s"}}}
                """.formatted(chat, fromId, contactUserId, phone)));
    }

    // ---- 1. notifications switch ----------------------------------------------------------------

    @Test
    void theSwitchTurnsOffTheCallersOwnNotifications_andNobodyElses() {
        assertThat(links.notifyChat(master)).contains(chat); // on by default

        assertThat(links.setNotify(master, false).notifications()).isFalse();

        assertThat(links.notifyChat(master)).isEmpty();
        assertThat(links.notifyChat(other)).contains(chat + 1);
        assertThat(links.status(other).notifications()).isTrue();
    }

    @Test
    void noChat_noNotification_evenWithTheSwitchOn() {
        links.unlink(master);
        assertThat(links.notifyChat(master)).isEmpty();
    }

    // ---- 2. the number through the bot ----------------------------------------------------------

    @Test
    void hisOwnContact_isSavedNormalised_andASecondOneOverwrites() {
        contact(TELEGRAM_USER, TELEGRAM_USER, "380501234567");
        assertThat(column("contact_phone", master)).isEqualTo("+380501234567");
        assertThat(column("contact_phone_at", master)).isNotNull();

        contact(TELEGRAM_USER, TELEGRAM_USER, "+380671112233");
        assertThat(column("contact_phone", master)).isEqualTo("+380671112233");
        // Never the printed phone: that one takes his tap.
        assertThat(column("phone", master)).isNull();
    }

    @Test
    void someoneElsesContactCard_isRefused() {
        contact(TELEGRAM_USER, 999_999L, "380501234567");
        assertThat(column("contact_phone", master)).isNull();
    }

    @Test
    void thePhoneFromTelegramReachesTheProfileOnlyByHisTap_andOnlyWhileItHasNone() {
        contact(TELEGRAM_USER, TELEGRAM_USER, "380501234567");

        assertThat(profileService.usePhoneFromTelegram(master).phone()).isEqualTo("+380501234567");

        jdbc.update("UPDATE users SET phone = '+380990000000' WHERE id = ?", master);
        assertThat(profileService.usePhoneFromTelegram(master).phone()).isEqualTo("+380990000000");
    }

    /** «Не зараз» under the always-open contact keyboard is an answer to the bot, never a note for «Вхідні». */
    @Test
    void notNow_andPhone_areNeverStoredAsInboxNotes() {
        for (String text : new String[]{"Не зараз", "/phone"}) {
            var update = json.readTree("""
                    {"update_id": 2, "message": {"message_id": 2, "chat": {"id": %d, "type": "private"},
                     "from": {"id": %d}, "text": "%s"}}
                    """.formatted(chat, TELEGRAM_USER, text));
            assertThat(bot.store(update)).as(text).isEmpty();
            bot.handle(update);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_entry WHERE owner_id = ?", Integer.class, master))
                .isZero();
    }

    // ---- 4. the setup-help card ------------------------------------------------------------------

    @Test
    void aDayInWithNoObject_isNoObject_andTheFieldStartsWithHisTelegramNumber() {
        registeredDaysAgo(master, 2);
        jdbc.update("UPDATE users SET contact_phone = '+380501234567' WHERE id = ?", master);

        assertThat(helpOffers.offer(master)).contains(new HelpOfferResponse(HelpReason.NO_OBJECT, "+380501234567"));
    }

    @Test
    void aWeekInWithAnObjectAndNothingHandedOver_isNotSent_untilSomethingReachesAClient() {
        registeredDaysAgo(master, 8);
        UUID project = object(master);
        estimate(project, "DRAFT");

        assertThat(helpOffers.offer(master)).map(HelpOfferResponse::reason).contains(HelpReason.NOT_SENT);

        estimate(project, "SENT");
        assertThat(helpOffers.offer(master)).isEmpty();
    }

    @Test
    void aPdfMadeInTheAppCountsAsHandedOver() {
        registeredDaysAgo(master, 8);
        object(master);
        jdbc.update("INSERT INTO pdf_render (id, owner_id, document, reader, created_at) VALUES (?, ?, 'ESTIMATE', 'MASTER', now())",
                UUID.randomUUID(), master);

        assertThat(helpOffers.offer(master)).isEmpty();
    }

    @Test
    void notYetAWeek_orAlreadyAsked_noCard() {
        registeredDaysAgo(master, 3);
        object(master);
        assertThat(helpOffers.offer(master)).isEmpty();

        registeredDaysAgo(master, 8);
        profileService.requestHelp(master, new HelpRequest("+380501234567"));
        assertThat(column("help_reason", master)).isEqualTo("NOT_SENT");
        assertThat(helpOffers.offer(master)).isEmpty();
    }
}
