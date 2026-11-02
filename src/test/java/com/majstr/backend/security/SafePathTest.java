package com.majstr.backend.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SafePathTest {

    @ParameterizedTest
    @CsvSource({
            "/api/public/portal/SECRET/estimates/1/sign, /api/public/portal/***/estimates/1/sign",
            "/api/public/estimates/SECRET, /api/public/estimates/***",
            "/api/public/economy/SECRET/photos/1/file, /api/public/economy/***/photos/1/file",
            "/api/public/act/SECRET/pdf, /api/public/act/***/pdf",
            "/api/public/unsubscribe/SECRET, /api/public/unsubscribe/***",
            "/api/public/materials/calc, /api/public/materials/calc",
            "/api/public/monitoring, /api/public/monitoring",
            "/api/projects/1/photos, /api/projects/1/photos",
            "No static resource /api/public/portal/SECRET/x., No static resource /api/public/portal/***/x.",
    })
    void aPublicTokenNeverSurvives(String input, String expected) {
        assertThat(SafePath.of(input)).isEqualTo(expected);
    }
}
