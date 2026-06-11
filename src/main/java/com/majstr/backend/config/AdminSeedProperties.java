package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the first-admin auto-seed ({@code AdminSeeder}). Both come
 * from the environment only ({@code ADMIN_EMAIL} / {@code ADMIN_PASSWORD}) and
 * are blank in dev, so the seed is a no-op locally. Used to bootstrap the admin
 * panel on a fresh production DB without hand-editing the database.
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminSeedProperties(
        String email,
        String password
) {
    public boolean isConfigured() {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }
}
