package com.majstr.backend.dto;

import com.majstr.backend.entity.UserTrade;

import java.util.UUID;

public record UserTradeResponse(
        UUID id,
        String name,
        int sortOrder
) {
    public static UserTradeResponse from(UserTrade trade) {
        return new UserTradeResponse(trade.getId(), trade.getName(), trade.getSortOrder());
    }
}
