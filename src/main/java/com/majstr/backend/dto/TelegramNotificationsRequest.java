package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;

/** «Сповіщення в Telegram» on or off (V226) — the master's own switch. */
public record TelegramNotificationsRequest(@NotNull Boolean enabled) {}
