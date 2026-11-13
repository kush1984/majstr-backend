package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the client's price-list page shows (V205): the master, the date and his ticked works, grouped by
 * trade and category in the order the work goes. Names are already Ukrainian; nothing internal rides along.
 */
public record PublicPriceListView(
        PublicEstimateView.Contractor contractor,
        LocalDate date,
        List<Trade> trades
) {
    public record Trade(String name, List<Section> sections) {}

    public record Section(String category, List<Item> items) {}

    public record Item(String name, String description, String unit, BigDecimal price) {}
}
