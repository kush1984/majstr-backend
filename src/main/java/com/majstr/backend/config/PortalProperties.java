package com.majstr.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.portal")
public record PortalProperties(
        @NotBlank String publicBaseUrl
) {}
