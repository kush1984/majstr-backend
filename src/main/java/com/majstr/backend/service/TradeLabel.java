package com.majstr.backend.service;

import com.majstr.backend.entity.Trade;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Ukrainian trade names for server-side renderers — the same words the PWA ({@code trades.*} in
 * {@code uk.json}) and the portal page ({@code TRADE_LABELS}) show. The REST API itself returns codes.
 */
public final class TradeLabel {

    private static final Map<Trade, String> UA = Map.ofEntries(
            entry(Trade.ELECTRICAL, "Електрика"),
            entry(Trade.PLUMBING, "Сантехніка"),
            entry(Trade.TILING, "Плитка"),
            entry(Trade.BUILDER, "Будівельник"),
            entry(Trade.PAINTER, "Малярні роботи"),
            entry(Trade.DRYWALL, "Гіпсокартон"),
            entry(Trade.FLOORING, "Підлоги"),
            entry(Trade.DEMOLITION, "Демонтаж"),
            entry(Trade.METAL, "Металоконструкції"),
            entry(Trade.GENERAL, "Загальні роботи"),
            entry(Trade.OTHER, "Інше")
    );

    private TradeLabel() {}

    public static String ua(Trade trade) {
        return trade == null ? UA.get(Trade.OTHER) : UA.getOrDefault(trade, trade.name());
    }
}
