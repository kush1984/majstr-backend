package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;

import java.util.UUID;

/** Re-file a template under a trade; {@code null} = general (shown under every trade).
 *  {@code customTradeId} only takes effect on the caller's OWN template — re-filing a
 *  system default always goes through {@code TemplateTradeOverride}, which has no place
 *  for a custom trade by design, so it is silently ignored there. */
public record TemplateTradeRequest(
        Trade trade,
        UUID customTradeId
) {}
