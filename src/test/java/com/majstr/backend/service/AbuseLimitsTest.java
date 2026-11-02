package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import com.majstr.backend.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbuseLimitsTest {

    private final AbuseLimits limits = limits();

    private static AbuseLimits limits() {
        RateLimitProperties props = mock(RateLimitProperties.class);
        when(props.aiImport()).thenReturn(new RateLimitProperties.AiImport(2));
        when(props.loginGuard()).thenReturn(new RateLimitProperties.LoginGuard(2, 2));
        when(props.forgotGuard()).thenReturn(new RateLimitProperties.ForgotGuard(2, 3));
        when(props.anonymous()).thenReturn(new RateLimitProperties.Anonymous(2));
        when(props.emailChange()).thenReturn(new RateLimitProperties.EmailChange(1));
        return new AbuseLimits(props);
    }

    @Test
    void anAddressFoldsToOneKey_whateverCharactersTheDatabaseWouldTreatAsTheSame() {
        assertThat(AbuseLimits.foldEmail(" Master@Example.com ")).isEqualTo("master@example.com");
        assertThat(AbuseLimits.foldEmail("maſter@example.com")).isEqualTo("master@example.com");
        assertThat(AbuseLimits.foldEmail("ıvan@example.com")).isEqualTo("ivan@example.com");
    }

    @Test
    void theImportLimitIsPerAccount() {
        UUID busy = UUID.randomUUID();
        limits.requireAiImport(busy);
        limits.requireAiImport(busy);

        assertThatThrownBy(() -> limits.requireAiImport(busy))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isPositive());
        assertThatCode(() -> limits.requireAiImport(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void onlyFailuresSpendTheAccountLimit_andCheckingItSpendsNothing() {
        for (int i = 0; i < 5; i++) {
            limits.requireLoginNotBlocked("account");
        }
        limits.recordLoginFailure("account");
        limits.requireLoginNotBlocked("account");
        limits.recordLoginFailure("account");

        assertThatThrownBy(() -> limits.requireLoginNotBlocked("account"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void theIpCeilingCountsAttemptsAcrossAddresses() {
        limits.requireLoginFromIp("203.0.113.1");
        limits.requireLoginFromIp("203.0.113.1");

        assertThatThrownBy(() -> limits.requireLoginFromIp("203.0.113.1"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limits.requireLoginFromIp("203.0.113.2")).doesNotThrowAnyException();
    }

    @Test
    void resetMailToOneAddressIsLimitedFromAnyIp() {
        limits.requireForgot("203.0.113.1", "victim@example.com");
        limits.requireForgot("203.0.113.2", "VICTIM@example.com");

        assertThatThrownBy(() -> limits.requireForgot("203.0.113.3", "victim@example.com"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void oneIpCannotChurnManyAddresses() {
        limits.requireForgot("203.0.113.9", "a@example.com");
        limits.requireForgot("203.0.113.9", "b@example.com");
        limits.requireForgot("203.0.113.9", "c@example.com");

        assertThatThrownBy(() -> limits.requireForgot("203.0.113.9", "d@example.com"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void emailChangesArePerAccount() {
        UUID account = UUID.randomUUID();
        limits.requireEmailChange(account);

        assertThatThrownBy(() -> limits.requireEmailChange(account))
                .hasMessage("error.rate.email-change");
    }
}
