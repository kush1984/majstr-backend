package com.majstr.backend.dto;

import jakarta.validation.constraints.Size;

/** Body for POST /api/upgrade/click — where the click came from (OBJECT_LIMIT / …). */
public record UpgradeClickRequest(@Size(max = 40) String trigger) {}
