package com.majstr.backend.dto;

import java.time.Instant;
import java.util.UUID;

/** One master who asked for a setup call (V191) — the admin's «Просять допомогти» list. */
public record HelpRequestLead(UUID userId, String email, String fullName, String phone, Instant requestedAt) {}
