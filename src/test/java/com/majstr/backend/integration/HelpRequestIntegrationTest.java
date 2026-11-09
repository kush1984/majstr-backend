package com.majstr.backend.integration;

import com.majstr.backend.dto.HelpRequestLead;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.HelpRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin's setup-call list (V191, V193) and V195 taking the card's number off the documents. */
class HelpRequestIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository users;
    @Autowired HelpRequestService helpRequests;
    @Autowired JdbcTemplate jdbc;

    /** Review: the list was the newest 200 and the browser lifted the open ones — an old open call fell off. */
    @Test
    void openCallsComeFirst_inTheQueryItself() {
        Instant now = Instant.now();
        User oldOpen = user("+380500000001", now.minus(30, ChronoUnit.DAYS), null);
        User newHandled = user("+380500000002", now.minus(1, ChronoUnit.HOURS), now);
        User newOpen = user("+380500000003", now.minus(2, ChronoUnit.HOURS), null);
        Set<UUID> mine = Set.of(oldOpen.getId(), newHandled.getId(), newOpen.getId());

        List<UUID> order = helpRequests.list().stream()
                .map(HelpRequestLead::userId).filter(mine::contains).toList();

        assertThat(order).containsExactly(newOpen.getId(), oldOpen.getId(), newHandled.getId());
    }

    @Test
    void markingACallDone_andUndoingIt() {
        User u = user("+380500000004", Instant.now(), null);

        assertThat(helpRequests.markHandled(u.getId(), true).handledAt()).isNotNull();
        assertThat(users.findById(u.getId()).orElseThrow().getHelpHandledAt()).isNotNull();
        assertThat(helpRequests.markHandled(u.getId(), false).handledAt()).isNull();
    }

    @Test
    void v195_clearsTheCardsNumberFromTheDocuments_andNothingElse() throws Exception {
        Instant beforeV193 = Instant.parse("2026-11-07T12:00:00Z");
        User fromTheCard = user("+380501112233", beforeV193, null);
        withProfilePhone(fromTheCard, "+380501112233");
        User ownPhoneDiffers = user("+380671112233", beforeV193, null);
        withProfilePhone(ownPhoneDiffers, "+380509998877");
        User askedAfterV193 = user("+380631112233", Instant.parse("2099-01-01T00:00:00Z"), null);
        withProfilePhone(askedAfterV193, "+380631112233");

        String migration = new String(getClass().getResourceAsStream(
                "/db/migration/V195__help_phone_off_the_documents.sql").readAllBytes(), StandardCharsets.UTF_8);
        jdbc.update(migration.substring(migration.indexOf("UPDATE users")));

        assertThat(users.findById(fromTheCard.getId()).orElseThrow().getPhone()).isNull();
        assertThat(users.findById(fromTheCard.getId()).orElseThrow().getHelpPhone()).isEqualTo("+380501112233");
        assertThat(users.findById(ownPhoneDiffers.getId()).orElseThrow().getPhone()).isEqualTo("+380509998877");
        assertThat(users.findById(askedAfterV193.getId()).orElseThrow().getPhone()).isEqualTo("+380631112233");
    }

    private void withProfilePhone(User u, String phone) {
        jdbc.update("UPDATE users SET phone = ? WHERE id = ?", phone, u.getId());
    }

    private User user(String helpPhone, Instant requestedAt, Instant handledAt) {
        String unique = UUID.randomUUID().toString();
        return users.save(User.builder()
                .email(unique + "@majstr.test")
                .emailCanonical(unique + "@majstr.test")
                .passwordHash("x")
                .fullName("Майстер")
                .plan(Plan.FREE)
                .role(Role.USER)
                .referralCode(unique.substring(0, 10))
                .helpPhone(helpPhone)
                .helpRequestedAt(requestedAt)
                .helpHandledAt(handledAt)
                .build());
    }
}
