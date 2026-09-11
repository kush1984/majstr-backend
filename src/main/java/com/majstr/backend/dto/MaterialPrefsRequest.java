package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Upsert of the master's parameters. An entry whose value is blank DELETES the preference — the
 * master forgetting a habit must be expressible, and a blank string is not a habit.
 */
public record MaterialPrefsRequest(@NotNull Map<String, String> prefs) {}
