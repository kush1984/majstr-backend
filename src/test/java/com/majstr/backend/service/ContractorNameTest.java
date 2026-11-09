package com.majstr.backend.service;

import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.SenderNameMissingException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The one rule for what a document says about the master, and the bar before one leaves (V190). */
class ContractorNameTest {

    private static User master(String fullName, String company, String phone, boolean verified) {
        return User.builder().email("m@example.com").passwordHash("x")
                .fullName(fullName).companyName(company).phone(phone).emailVerified(verified).build();
    }

    @Test
    void display_isTheCompany_elseTheName_trimmed() {
        assertThat(ContractorName.display(master(" Іван ", " ФОП Іван ", null, true))).isEqualTo("ФОП Іван");
        assertThat(ContractorName.display(master(" Іван ", null, null, true))).isEqualTo("Іван");
        assertThat(ContractorName.display(master("Іван", "   ", null, true)))
                .as("a whitespace company must not hide the name").isEqualTo("Іван");
        assertThat(ContractorName.display(master(null, null, null, true))).isEmpty();
    }

    @Test
    void signer_isTheName_elseTheCompany() {
        assertThat(ContractorName.signer(master("Іван", "ФОП Іван", null, true))).isEqualTo("Іван");
        assertThat(ContractorName.signer(master(" ", "ФОП Іван", null, true))).isEqualTo("ФОП Іван");
        assertThat(ContractorName.signer(master(null, null, null, true))).isEmpty();
    }

    @Test
    void actDisplay_isTheCompany_elseTheLegalName_elseTheName() {
        User u = master("Іван", null, null, true);
        assertThat(ContractorName.actDisplay(u)).isEqualTo("Іван");
        u.setLegalName(" ФОП Іваненко І. ");
        assertThat(ContractorName.actDisplay(u)).isEqualTo("ФОП Іваненко І.");
        u.setCompanyName("Ремонти Івана");
        assertThat(ContractorName.actDisplay(u)).isEqualTo("Ремонти Івана");
    }

    @Test
    void phone_blankIsNull() {
        assertThat(ContractorName.phone(master("Іван", null, " +380 50 ", true))).isEqualTo("+380 50");
        assertThat(ContractorName.phone(master("Іван", null, "  ", true))).isNull();
        assertThat(ContractorName.phone(master("Іван", null, null, true))).isNull();
    }

    @Test
    void weSendTheLetter_asksForTheEmailFirst_thenTheName() {
        assertThatThrownBy(() -> SendReadiness.weSendTheLetter(master(null, null, null, false)))
                .isInstanceOf(EmailNotVerifiedException.class);
        assertThatThrownBy(() -> SendReadiness.weSendTheLetter(master("  ", "ФОП", "+380", true)))
                .as("a company is not a name to sign with").isInstanceOf(SenderNameMissingException.class);
        assertThatCode(() -> SendReadiness.weSendTheLetter(master("Іван", null, null, true)))
                .as("phone and company stay optional").doesNotThrowAnyException();
    }

    @Test
    void masterSendsItself_ignoresTheEmail() {
        assertThatCode(() -> SendReadiness.masterSendsItself(master("Іван", null, null, false)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SendReadiness.masterSendsItself(master(null, null, null, true)))
                .isInstanceOf(SenderNameMissingException.class);
    }
}
