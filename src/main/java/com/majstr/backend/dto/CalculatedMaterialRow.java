package com.majstr.backend.dto;

import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One material the calculator derived from an estimate, before it meets the list. The engine that
 * produces these lands with the DRYWALL norms; this record is the contract between it and
 * {@code ShoppingListService.applyCalculated}, which owns every rule about what happens next.
 */
public record CalculatedMaterialRow(
        UUID materialId,
        String name,
        Unit unit,
        BigDecimal quantity,
        UUID estimateItemId
) {}
