package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Body of "save the current estimate as a template" — the master's chosen name
 *  and the trade to file it under (null = general, shown under every trade).
 *  {@code customTradeId} (a master-invented trade) is ignored (forced to OTHER)
 *  alongside {@code trade} when set — same rule as a catalog item. */
public record SaveAsTemplateRequest(
        @NotBlank @Size(max = 255) String name,
        com.majstr.backend.entity.Trade trade,
        UUID customTradeId
) {}
