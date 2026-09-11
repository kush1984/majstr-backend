package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The norm the write actually landed on. The {@code id} is NOT always the one in the URL: correcting
 * a shipped norm forks it, exactly as a template default forks (V113), so the caller must key its
 * cache under the id that comes back.
 */
public record MaterialNormResponse(
        UUID id,
        UUID materialId,
        BigDecimal qtyPerUnit,
        boolean ownNorm
) {}
