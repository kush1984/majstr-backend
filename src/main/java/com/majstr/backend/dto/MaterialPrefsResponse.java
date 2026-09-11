package com.majstr.backend.dto;

import java.util.Map;

/** The master's remembered answers, keyed by {@code MaterialPrefKey} name. */
public record MaterialPrefsResponse(Map<String, String> prefs) {}
