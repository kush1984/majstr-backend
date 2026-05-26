package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        Local local
) {
    public record Local(String directory) {}
}
