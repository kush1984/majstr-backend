package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The one line the home screen shows (V135): «Цей тиждень: +42 000 −18 500».
 *
 * <p>Deliberately a strip and not a card. The dashboard already carries a greeting, trade chips,
 * three metric tiles, the shopping card, recent objects and quick actions — another full card and
 * the bottom of that screen stops being read at all.</p>
 *
 * <p><b>The WEEK, and it must stay the same period the screen opens on</b> (master's call). A strip
 * showing a month and a screen opening on a week means tapping «+42 000» lands on 8 000 — two
 * surfaces describing the same money and disagreeing, which is the one thing a money screen may not
 * do. {@code from}/{@code to} ride along so neither side has to re-derive the window and get it
 * subtly different.</p>
 *
 * @param hasEntries false = nothing moved this week, and the strip does not render. Same rule as
 *                   the shopping card, which disappears when there is nothing left to buy
 */
public record CashSummaryResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal earned,
        boolean hasEntries
) {}
