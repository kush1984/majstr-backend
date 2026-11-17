package com.majstr.backend.integration;

import com.majstr.backend.dto.CheckoutResponse;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.BillingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-object checkout end to end on Postgres, with no merchant token and dev simulation on — the
 * way a local run pays: the row is written and the credit lands in the same transaction.
 */
@SpringBootTest(properties = "app.billing.allow-dev-simulation=true")
class OneOffObjectCheckoutIntegrationTest extends IntegrationTestBase {

    @Autowired BillingService billingService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aSimulatedObjectPurchaseWritesThePaymentAndTheCredit() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        User master = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").fullName("Разовий").emailVerified(true)
                .plan(Plan.FREE).role(Role.USER).referralCode(unique.substring(0, 10))
                .lifetimeProjectCount(2)
                .build());

        CheckoutResponse resp = billingService.checkoutObject(master.getId());

        assertThat(resp.pageUrl()).endsWith("for=object");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT kind, status, amount, plan FROM payments WHERE user_id = ?", master.getId());
        assertThat(row).containsEntry("kind", "OBJECT").containsEntry("status", "SUCCESS").containsEntry("plan", "FREE");
        assertThat(jdbc.queryForObject("SELECT object_credits FROM users WHERE id = ?", Integer.class, master.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT plan FROM users WHERE id = ?", String.class, master.getId()))
                .isEqualTo("FREE");
    }
}
