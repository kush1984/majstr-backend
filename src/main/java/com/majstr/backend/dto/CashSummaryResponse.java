package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The one line the home screen shows (V135): «Цей місяць: +42 000 −18 500».
 *
 * <p>Deliberately a strip and not a card. The dashboard already carries a greeting, trade chips,
 * three metric tiles, the shopping card, recent objects and quick actions — another full card and
 * the bottom of that screen stops being read at all.</p>
 *
 * <p><b>The MONTH, and the tap must land on exactly this window</b> (master's call — a week on the
 * home screen was too small to be worth a glance). Two surfaces describing the same money and
 * disagreeing is the one thing a money screen may not do, so the strip's tap opens Мої кошти
 * already switched to the month; every other door (the Профіль row, a cold reload) keeps the week
 * the screen is built around. {@code from}/{@code to} ride along so neither side has to re-derive
 * the window and get it subtly different.</p>
 *
 * @param hasEntries false = nothing moved this month, and the strip does not render. Same rule as
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
