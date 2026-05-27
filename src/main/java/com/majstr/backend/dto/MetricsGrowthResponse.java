package com.majstr.backend.dto;

import java.time.LocalDate;
import java.util.List;

public record MetricsGrowthResponse(
        LocalDate from,
        LocalDate to,
        List<Point> points
) {
    public record Point(LocalDate day, long count) {}
}
