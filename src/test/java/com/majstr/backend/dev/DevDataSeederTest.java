package com.majstr.backend.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DevDataSeederTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432/majstr",
            "jdbc:postgresql://LOCALHOST/majstr",
            "jdbc:postgresql://127.0.0.1:55432/majstr?sslmode=disable",
            "jdbc:postgresql://[::1]:5432/majstr"
    })
    void aLocalDatabaseIsAccepted(String url) {
        assertThat(DevDataSeeder.isLocalDatabase(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db.example.com:5432/majstr",
            "jdbc:postgresql://localhost.example.com/majstr",
            "jdbc:postgresql://localhost:5432,db.example.com:5432/majstr",
            "jdbc:postgresql://127.0.0.1.example.com/majstr",
            "jdbc:postgresql:majstr",
            "jdbc:postgresql:///majstr",
            "postgresql://localhost/majstr",
            "jdbc:postgresql://bad host/majstr"
    })
    void anythingElseIsRefused(String url) {
        assertThat(DevDataSeeder.isLocalDatabase(url)).isFalse();
    }

    @Test
    void noUrlIsRefused() {
        assertThat(DevDataSeeder.isLocalDatabase(null)).isFalse();
    }
}
