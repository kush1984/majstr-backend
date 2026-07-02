package com.majstr.backend.dto;

import jakarta.validation.constraints.Size;

/** Admin manual override of a master's referral source (blank → DIRECT). */
public record ReferralSourceUpdateRequest(@Size(max = 40) String source) {}
