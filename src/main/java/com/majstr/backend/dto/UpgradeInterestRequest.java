package com.majstr.backend.dto;

import jakarta.validation.constraints.Size;

/** Body for POST /api/upgrade/interest — the painted-door "what do you need?" text. */
public record UpgradeInterestRequest(@Size(max = 4000) String reason) {}
