package com.majstr.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveProfileGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ActiveProfileGuard.class);

    @Test
    void noProfileRefusesToStart() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("SPRING_PROFILES_ACTIVE");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "default", "Prod"})
    void aProfileThatCarriesNoHardeningRefusesToStart(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev", "test"})
    void aKnownProfileStarts(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
