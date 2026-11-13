package com.majstr.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * The master's price list as the picker needs it (V205).
 *
 * @param url              the live client link, or null before the first share / after «Закрити посилання»
 * @param configured       false until the master has shared or previewed once: {@code itemIds} is then our suggestion
 *                         (every priced work except those still at the library's price), not his choice
 * @param itemIds          the ticked positions
 * @param libraryPricedIds positions still at the price our library ships — the picker marks them
 */
public record PriceListResponse(
        String url,
        boolean configured,
        List<UUID> itemIds,
        List<UUID> libraryPricedIds
) {}
