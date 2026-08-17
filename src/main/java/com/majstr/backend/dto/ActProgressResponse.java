package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a new act can still close: every line of the object's SIGNED estimates, with how much has
 * already been done across SIGNED acts and what remains. Drives the act-creation screen (Prompt 4)
 * and the green «закрито» chips in the estimate board. Owner-only, ungated.
 */
public record ActProgressResponse(List<Line> lines) {
    public record Line(
            UUID estimateId,
            String estimateName,
            Instant estimateCreatedAt,
            UUID estimateItemId,
            ItemType type,
            String name,
            String category,
            Unit unit,
            BigDecimal unitPrice,
            BigDecimal estimateQuantity,
            BigDecimal done,
            BigDecimal remaining
    ) {}
}
