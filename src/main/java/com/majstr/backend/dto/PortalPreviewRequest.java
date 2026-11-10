package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * «Подивитися, як побачить клієнт» from the object's share sheet: the estimates ticked RIGHT NOW,
 * before anything is published. Nothing is written — the published set stays what it was.
 */
public record PortalPreviewRequest(
        @NotNull @Size(max = 100) List<UUID> estimateIds
) {}
