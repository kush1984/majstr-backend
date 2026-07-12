package com.majstr.backend.dto;

import com.majstr.backend.entity.MeasurementType;
import com.majstr.backend.entity.Unit;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * An object's whole measurement tree: rooms → elements, with per-room and object totals
 * split by unit (m² vs м.пог). Owner-only — never part of any client/portal/PDF response.
 */
public record MeasurementsResponse(
        List<Room> rooms,
        BigDecimal areaTotal,
        BigDecimal linearTotal
) {
    public record Room(
            java.util.UUID id,
            String name,
            int sortOrder,
            List<Item> items,
            BigDecimal areaTotal,
            BigDecimal linearTotal
    ) {}

    public record Item(
            java.util.UUID id,
            String name,
            MeasurementType type,
            Unit unit,
            BigDecimal result,
            JsonNode payload,
            int sortOrder
    ) {}
}
