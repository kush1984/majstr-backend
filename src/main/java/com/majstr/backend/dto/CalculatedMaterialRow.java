package com.majstr.backend.dto;

import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One material the calculator derived from an estimate, before it meets the list. The engine that
 * produces these lands with the DRYWALL norms; this record is the contract between it and
 * {@code ShoppingListService.applyCalculated}, which owns every rule about what happens next.
 *
 * <p>{@code packageSize}/{@code packageName} are the package the calculator rounded to — the sheet
 * already resolved against the master's GKL_SHEET habit — both null or both set (V174).</p>
 */
public record CalculatedMaterialRow(
        UUID materialId,
        String name,
        Unit unit,
        BigDecimal quantity,
        UUID estimateItemId,
        BigDecimal packageSize,
        String packageName
) {}
