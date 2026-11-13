package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;

/** A library position that buys materials — what a master's own wording can be counted as (V204). */
public record NormPosition(String key, String name, Unit unit, Trade trade) {}
