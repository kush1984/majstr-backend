package com.majstr.backend.service;

import com.majstr.backend.service.EmailPolicyService.MailAnswer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The email policy with what a DNS would say, minus the network: {@code gmal.com} exists and takes no mail,
 * {@code gmail.con} does not exist, everything else accepts. For a test that drives registration over HTTP
 * and must see both typo answers, which the test profile's {@code dns-check: false} never gives.
 */
@TestConfiguration
public class FakeDnsEmailPolicyConfig {

    @Bean
    @Primary
    EmailPolicyService fakeDnsEmailPolicy() {
        return new EmailPolicyService(domain -> switch (domain) {
            case "gmal.com" -> MailAnswer.NO_MAIL;
            case "gmail.con" -> MailAnswer.NO_DOMAIN;
            default -> MailAnswer.ACCEPTS;
        });
    }
}
