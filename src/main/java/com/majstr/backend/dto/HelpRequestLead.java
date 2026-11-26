package com.majstr.backend.dto;

import com.majstr.backend.entity.HelpReason;

import java.time.Instant;
import java.util.UUID;

/** One master who asked for a setup call (V191) — the admin's «Просять допомогти» list; `handledAt` set
 *  once the admin marked the call done (V193). `reason` — why the card stood when he asked (V226, null for
 *  older calls); `contactPhone` — the number he shared with the Telegram bot, shown with its source. */
public record HelpRequestLead(UUID userId, String email, String fullName, String phone, Instant requestedAt,
                              Instant handledAt, HelpReason reason, String contactPhone) {}
