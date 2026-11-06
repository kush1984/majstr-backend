package com.majstr.backend.service;

import com.majstr.backend.exception.EmailDomainNotAllowedException;
import com.majstr.backend.exception.EmailDomainNotAllowedException.Reason;
import com.majstr.backend.service.EmailPolicyService.MailAnswer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the anti-abuse email policy. DNS is a lookup handed in, so every answer it can
 * give is exercised without touching the network.
 */
class EmailPolicyServiceTest {

    private final EmailPolicyService service = new EmailPolicyService(domain -> MailAnswer.ACCEPTS);

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
    void aDisposableDomainIsRefusedAsDisposable_beforeAnyDns() {
        AtomicInteger lookups = new AtomicInteger();
        EmailPolicyService policy = new EmailPolicyService(domain -> {
            lookups.incrementAndGet();
            return MailAnswer.ACCEPTS;
        });

        assertThatThrownBy(() -> policy.assertAcceptable("throwaway@mailinator.com"))
                .isInstanceOfSatisfying(EmailDomainNotAllowedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.DISPOSABLE));
        assertThat(lookups).hasValue(0);
    }

    /** gmal.com exists and takes no mail: a typo of gmail.com, not a throwaway. */
    @Test
    void aDomainWithoutMailIsRefusedAsATypo() {
        EmailPolicyService policy = new EmailPolicyService(domain -> MailAnswer.NO_MAIL);

        assertThatThrownBy(() -> policy.assertAcceptable("ivan@gmal.com"))
                .isInstanceOfSatisfying(EmailDomainNotAllowedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NO_MAIL));
    }

    /** gmail.con does not exist: the account would never see its verification letter. */
    @Test
    void aDomainThatDoesNotExistIsRefusedAsATypo() {
        EmailPolicyService policy = new EmailPolicyService(domain -> MailAnswer.NO_DOMAIN);

        assertThatThrownBy(() -> policy.assertAcceptable("ivan@gmail.con"))
                .isInstanceOfSatisfying(EmailDomainNotAllowedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NO_MAIL));
    }

    /** A timeout or a resolver that is not there must never block a real person. */
    @Test
    void anUnansweredLookupLetsTheAddressThrough() {
        EmailPolicyService policy = new EmailPolicyService(domain -> MailAnswer.UNKNOWN);

        assertThatCode(() -> policy.assertAcceptable("ivan@ukr.net")).doesNotThrowAnyException();
    }

    @Test
    void aDomainIsLookedUpOnceAndCached() {
        AtomicInteger lookups = new AtomicInteger();
        EmailPolicyService policy = new EmailPolicyService(domain -> {
            lookups.incrementAndGet();
            return MailAnswer.ACCEPTS;
        });

        policy.assertAcceptable("a@ukr.net");
        policy.assertAcceptable("b@UKR.NET");

        assertThat(lookups).hasValue(1);
    }

    @Test
    void withDnsOff_aMadeUpDomainPasses_butAThrowawayStillDoesNot() {
        EmailPolicyService policy = new EmailPolicyService(false);

        assertThatCode(() -> policy.assertAcceptable("e2e@majstr.test")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertAcceptable("x@yopmail.com"))
                .isInstanceOf(EmailDomainNotAllowedException.class);
    }

    @Test
    void assertAcceptable_malformed_doesNotThrow() {
        // Format is owned by @Email on the request; policy leaves malformed input alone.
        assertThatCode(() -> service.assertAcceptable("not-an-email")).doesNotThrowAnyException();
    }
}
