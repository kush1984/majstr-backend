package com.majstr.backend.dto;

import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One material to buy, with the arithmetic that produced it.
 *
 * @param baseQuantity the sum of the positions' contributions, before waste and before rounding
 * @param quantity     what to buy: base + waste, rounded UP to a whole package or a whole unit
 * @param packages     how many packages that is, or null when the material is sold loose
 * @param packageName  «лист», «мішок» — what the master asks for at the counter
 * @param sources      every position that contributed, so the screen can show the multiplication
 */
public record CalculatedMaterialLine(
        UUID materialId,
        String name,
        Unit unit,
        BigDecimal baseQuantity,
        BigDecimal quantity,
        BigDecimal wastePercent,
        BigDecimal packageSize,
        String packageName,
        Integer packages,
        List<MaterialSourceLine> sources
) {}
