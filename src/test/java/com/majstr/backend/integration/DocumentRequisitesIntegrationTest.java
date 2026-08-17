package com.majstr.backend.integration;

import com.majstr.backend.entity.ActNumberFormat;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.ClientType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.ClientRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V103 (acts iteration, Prompt 2) — the document-requisite columns on {@code users}/{@code clients}
 * against real Postgres: the enum-backed defaults apply, the new fields round-trip, and the CHECK
 * constraints exist (an {@code @Enumerated(STRING)} value that Hibernate writes is always valid, so
 * this proves the migration + entity mapping agree end to end, which no Mockito test can).
 */
class DocumentRequisitesIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired ClientRepository clientRepository;

    @Test
    void userRequisitesRoundTrip_andDefaultsApply() {
        String unique = UUID.randomUUID().toString();
        User saved = userRepository.saveAndFlush(User.builder()
                .email(unique + "@majstr.test")
                .emailCanonical(unique + "@majstr.test")
                .passwordHash("x")
                .fullName("Майстер")
                .phone("+380000000000")
                .companyName("ФОП")
                .plan(Plan.FREE)
                .referralCode(unique.substring(0, 10))
                // requisites
                .legalName("ФОП Іваненко Іван Іванович")
                .taxId("1234567890")
                .iban("UA903052992990004149123456789")
                .bankName("ПриватБанк")
                .vatPayer(true)
                .vatId("123456789012")
                .taxGroup((short) 3)
                .taxRate(new BigDecimal("5.00"))
                .docCity("Львів")
                .actNumberFormat(ActNumberFormat.WITH_YEAR)
                .build());

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getLegalName()).isEqualTo("ФОП Іваненко Іван Іванович");
        assertThat(reloaded.getTaxId()).isEqualTo("1234567890");
        assertThat(reloaded.isVatPayer()).isTrue();
        assertThat(reloaded.getTaxGroup()).isEqualTo((short) 3);
        assertThat(reloaded.getTaxRate()).isEqualByComparingTo("5.00");
        assertThat(reloaded.getActNumberFormat()).isEqualTo(ActNumberFormat.WITH_YEAR);

        // A user built with NO requisites gets the column defaults (vat_payer=false, PLAIN).
        String u2 = UUID.randomUUID().toString();
        User plain = userRepository.saveAndFlush(User.builder()
                .email(u2 + "@majstr.test").emailCanonical(u2 + "@majstr.test").passwordHash("x")
                .fullName("Простий").phone("+380000000001").companyName("ФОП")
                .plan(Plan.FREE).referralCode(u2.substring(0, 10))
                .build());
        User plainReloaded = userRepository.findById(plain.getId()).orElseThrow();
        assertThat(plainReloaded.isVatPayer()).isFalse();
        assertThat(plainReloaded.getActNumberFormat()).isEqualTo(ActNumberFormat.PLAIN);
        assertThat(plainReloaded.getLegalName()).isNull();
    }

    @Test
    void clientRequisitesRoundTrip_andDefaultsToPerson() {
        String unique = UUID.randomUUID().toString();
        User owner = userRepository.saveAndFlush(User.builder()
                .email(unique + "@majstr.test").emailCanonical(unique + "@majstr.test").passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.FREE).referralCode(unique.substring(0, 10)).build());

        Client company = clientRepository.saveAndFlush(Client.builder()
                .owner(owner).fullName("ТОВ Ромашка").phone("+380441234567")
                .clientType(ClientType.COMPANY).taxId("12345678")
                .legalName("Товариство з обмеженою відповідальністю «Ромашка»")
                .legalAddress("Київ, вул. Хрещатик 1")
                .signatoryTitle("Директор").signatoryName("Петренко П.П.")
                .build());

        Client reloaded = clientRepository.findById(company.getId()).orElseThrow();
        assertThat(reloaded.getClientType()).isEqualTo(ClientType.COMPANY);
        assertThat(reloaded.getTaxId()).isEqualTo("12345678");
        assertThat(reloaded.getSignatoryTitle()).isEqualTo("Директор");

        // A plain client (no type set) defaults to PERSON.
        Client person = clientRepository.saveAndFlush(Client.builder()
                .owner(owner).fullName("Іван Клієнт").phone("+380671112233").build());
        assertThat(clientRepository.findById(person.getId()).orElseThrow().getClientType())
                .isEqualTo(ClientType.PERSON);
    }
}
