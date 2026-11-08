package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;

import java.util.UUID;

/** File the caller's OWN template under a trade; {@code null} = general (shown under every trade).
 *  A system default is never re-filed (V194). */
public record TemplateTradeRequest(
        Trade trade,
        UUID customTradeId
) {}
