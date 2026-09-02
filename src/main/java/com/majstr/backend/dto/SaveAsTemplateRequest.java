package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Body of "save the current estimate as a template" — the master's chosen name
 *  and the trade to file it under (null = general, shown under every trade).
 *  {@code customTradeId} (a master-invented trade) is ignored (forced to OTHER)
 *  alongside {@code trade} when set — same rule as a catalog item.
 *
 *  <p>Also the body of {@code PATCH /api/estimate-templates/{id}} (the metadata update), where
 *  {@code trade}/{@code customTradeId} are ignored — the trade has its own endpoint. There
 *  {@code description} is <b>null = «leave it as it is»</b> and a blank string = «clear it»:
 *  the request is otherwise a full replace, so a rename that simply omitted the field would
 *  silently drop a paragraph the client reads (same rule the act receipt's {@code itemized}
 *  flag used to carry).</p> */
public record SaveAsTemplateRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1000) String description,
        com.majstr.backend.entity.Trade trade,
        UUID customTradeId
) {}
