package com.majstr.backend.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the production hardening lives in application-prod.yml. Pure file load
 * via {@link YamlPropertySourceLoader} — no Spring context, no DB. Placeholders
 * are NOT resolved here, so a raw {@code ${VAR}} value (no {@code :default}
 * segment) proves the value is env-required and fails fast when unset.
 */
class ProdProfileConfigTest {

    private static PropertySource<?> prod;
    private static PropertySource<?> base;

    @BeforeAll
    static void load() throws IOException {
        prod = loadYaml("application-prod.yml");
        base = loadYaml("application.yml");
    }

    private static PropertySource<?> loadYaml(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(file, new ClassPathResource(file));
        return sources.get(0);
    }

    private static String prodValue(String key) {
        Object v = prod.getProperty(key);
        return v == null ? null : String.valueOf(v);
    }

    @Test
    void swaggerAndApiDocsDisabledInProd() {
        assertThat(prodValue("springdoc.swagger-ui.enabled")).isEqualTo("false");
        assertThat(prodValue("springdoc.api-docs.enabled")).isEqualTo("false");
    }

    @Test
    void forwardHeadersStrategyIsFramework() {
        assertThat(prodValue("server.forward-headers-strategy")).isEqualTo("framework");
    }

    @Test
    void corsComesFromEnvWithNoLocalhostOrWildcardDefault() {
        String cors = prodValue("app.cors.allowed-origins");
        assertThat(cors).isEqualTo("${APP_CORS_ORIGINS}");
        assertThat(cors).doesNotContain("localhost").doesNotContain("*").doesNotContain(":");
    }

    @Test
    void secretsAndPublicUrlsHaveNoDefaultsInProd() {
        // A ":" inside the placeholder would mean a baked-in default — there must be none.
        assertThat(prodValue("spring.datasource.url")).isEqualTo("${DB_URL}");
        assertThat(prodValue("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
        assertThat(prodValue("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
        assertThat(prodValue("app.email.app-url")).isEqualTo("${APP_URL}");
        assertThat(prodValue("app.portal.public-base-url")).isEqualTo("${PORTAL_BASE_URL}");
    }

    @Test
    void jwtSecretIsAlreadyEnvRequiredInBaseSoItAppliesToProd() {
        // JWT secret has no default in the base file → fail-fast in every profile.
        assertThat(String.valueOf(base.getProperty("app.jwt.secret"))).isEqualTo("${JWT_SECRET}");
    }

    @Test
    void actuatorStaysHealthOnlyWithoutDetails() {
        // Confirmed inherited from the base file (prod doesn't loosen it).
        assertThat(String.valueOf(base.getProperty("management.endpoints.web.exposure.include")))
                .isEqualTo("health");
        assertThat(String.valueOf(base.getProperty("management.endpoint.health.show-details")))
                .isEqualTo("never");
    }
}
