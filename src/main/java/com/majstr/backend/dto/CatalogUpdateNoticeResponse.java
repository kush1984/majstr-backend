package com.majstr.backend.dto;

import com.majstr.backend.entity.CatalogUpdateNoticeKind;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One pending "your catalog was updated" notice. The endpoint returns a LIST of these — a master
 * can have several at once (a migration's count notice, and/or several community price-drift
 * notices for different positions) — an empty list is the normal "nothing pending" answer, not
 * a sentinel value.
 *
 * <p>{@code kind} tells the two shapes apart: {@code COUNT} carries {@code added}/{@code removed}
 * ({@code positionName}/{@code oldPrice}/{@code newPrice} null); {@code PRICE_DRIFT} carries the
 * other three ({@code added}/{@code removed} both 0).</p>
 */
public record CatalogUpdateNoticeResponse(
        UUID id, CatalogUpdateNoticeKind kind, int added, int removed,
        String positionName, BigDecimal oldPrice, BigDecimal newPrice) {
}
