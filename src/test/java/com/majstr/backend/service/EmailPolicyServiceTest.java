package com.majstr.backend.service;

import com.majstr.backend.exception.EmailDomainNotAllowedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pure unit tests for the anti-abuse email policy. Canonicalization is fully
 * deterministic; the disposable-domain check short-circuits before any DNS, so no
 * network is touched here. The MX fail-open path is intentionally not unit-tested
 * (it performs real DNS and always allows on failure).
 */
class EmailPolicyServiceTest {

    private final EmailPolicyService service = new EmailPolicyService();

    @Test
    void canonicalize_gmail_dropsDotsAndPlusTag() {
        assertThat(service.canonicalize("J.o.hn+promo@Gmail.com")).isEqualTo("john@gmail.com");
    }

    @Test
    void canonicalize_googlemail_normalizesToGmailDomain() {
        assertThat(service.canonicalize("a.b@googlemail.com")).isEqualTo("ab@gmail.com");
    }

    @Test
    void canonicalize_outlook_dropsPlusTagButKeepsDots() {
        // Non-Google plus-aware provider: strip the +tag, keep dots (dots are significant there).
        assertThat(service.canonicalize("john.doe+x@outlook.com")).isEqualTo("john.doe@outlook.com");
    }

    @Test
    void canonicalize_customDomain_isJustLowercased() {
        assertThat(service.canonicalize("Boss@Company.UA")).isEqualTo("boss@company.ua");
    }

    @Test
    void assertAcceptable_disposableDomain_throws() {
        assertThatThrownBy(() -> service.assertAcceptable("throwaway@mailinator.com"))
                .isInstanceOf(EmailDomainNotAllowedException.class);
    }

    @Test
    void assertAcceptable_malformed_doesNotThrow() {
        // Format is owned by @Email on the request; policy leaves malformed input alone.
        assertThatCode(() -> service.assertAcceptable("not-an-email")).doesNotThrowAnyException();
    }
}
