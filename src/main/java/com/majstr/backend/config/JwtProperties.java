package com.majstr.backend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "app.jwt.secret must be at least 32 bytes (256 bits) for HS256") String secret,
        @Positive long accessTokenExpirationMinutes,
        @Positive long refreshTokenExpirationDays,
        /**
         * How long a just-rotated refresh token stays redeemable (seconds).
         *
         * <p>Covers the case where the rotation response never reached the client — a lost
         * reply on a bad mobile connection, or a second tab racing the first. Without it that
         * client holds a token the server already killed, and the next call logs the master
         * out and takes their unsynced offline queue with it.
         *
         * <p>Keep it small: within this window a stolen OLD token is still usable once. Zero
         * disables the grace entirely and restores strict single-use rotation.
         */
        @PositiveOrZero long refreshRotationGraceSeconds,
        @NotBlank String issuer
) {}
