package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line's stored layout, as the calculator used it — or did not.
 *
 * @param paused     the line's name now names another format than the layout was made for: the
 *                   calculator counts that line by area and the screen asks «перерахувати?»
 * @param tilesToBuy {@code maxTiles + spare} — what the laid part puts on the buying list
 */
public record TileLayoutView(
        UUID estimateItemId,
        int tileShortMm,
        int tileLongMm,
        BigDecimal laidArea,
        int minTiles,
        int maxTiles,
        int spare,
        int tilesToBuy,
        boolean paused,
        boolean measurementsDeclined,
        String params
) {}
