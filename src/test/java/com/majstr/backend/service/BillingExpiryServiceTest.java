package com.majstr.backend.service;

import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class BillingExpiryServiceTest {

    @Mock UserRepository userRepository;

    private BillingExpiryService service() {
        BillingProperties props = new BillingProperties("", "https://api.monobank.ua",
                new BigDecimal("299"), 30, 3, "http://ret", "http://hook", true, 3);
        return new BillingExpiryService(userRepository, props);
    }

    @Test
    void downgradeExpired_flipsLapsedProToFreeAndClearsExpiry() {
        User lapsed = User.builder()
                .id(UUID.randomUUID()).email("m@x").plan(Plan.PRO)
                .planExpiresAt(Instant.now().minus(5, ChronoUnit.DAYS))
                .build();
        given(userRepository.findExpiredSubscriptions(any())).willReturn(List.of(lapsed));

        service().downgradeExpired();

        assertThat(lapsed.getPlan()).isEqualTo(Plan.FREE);
        assertThat(lapsed.getPlanExpiresAt()).isNull();
    }

    @Test
    void downgradeExpired_noExpiredSubscriptionsIsANoOp() {
        given(userRepository.findExpiredSubscriptions(any())).willReturn(List.of());

        service().downgradeExpired(); // must not throw
    }
}
