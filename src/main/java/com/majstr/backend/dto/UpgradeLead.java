package com.majstr.backend.dto;

import java.time.Instant;
import java.util.UUID;

/** A warm lead — a master who submitted PRO interest. "Who to call." */
public record UpgradeLead(UUID userId, String email, String fullName, String reason, Instant createdAt) {}
