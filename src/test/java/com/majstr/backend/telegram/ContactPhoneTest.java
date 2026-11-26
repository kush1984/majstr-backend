package com.majstr.backend.telegram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContactPhoneTest {

    @Test
    void aUkrainianNumberEndsUpAsPlus380_howeverTelegramWroteIt() {
        assertThat(ContactPhone.normalize("380501234567")).isEqualTo("+380501234567");
        assertThat(ContactPhone.normalize("+380501234567")).isEqualTo("+380501234567");
        assertThat(ContactPhone.normalize("0501234567")).isEqualTo("+380501234567");
        assertThat(ContactPhone.normalize("+38 (050) 123-45-67")).isEqualTo("+380501234567");
    }

    @Test
    void anotherCountryKeepsItsDigits_andGarbageIsNoPhone() {
        assertThat(ContactPhone.normalize("48601234567")).isEqualTo("+48601234567");
        assertThat(ContactPhone.normalize("12345")).isNull();
        assertThat(ContactPhone.normalize("")).isNull();
        assertThat(ContactPhone.normalize(null)).isNull();
    }
}
