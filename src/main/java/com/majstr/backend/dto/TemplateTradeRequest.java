package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;

/** Re-file a template under a trade; {@code null} = general (shown under every trade). */
public record TemplateTradeRequest(
        Trade trade
) {}
