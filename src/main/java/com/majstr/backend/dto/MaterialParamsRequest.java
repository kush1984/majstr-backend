package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The master's answer to one of the calculator's three questions (V142).
 *
 * <p><b>A PATCH, one question at a time.</b> Each card on the screen has its own «Порахувати», so a
 * request carries the kind that button owns and leaves the other two alone — an omitted field
 * changes nothing. Sending all three would store the THICKNESS suggestions still sitting pre-filled
 * and unconfirmed in their fields (V137: pre-filled visibly, never applied silently) the moment he
 * answered the perimeter.</p>
 *
 * <p><b>Zero clears.</b> A blank field is how the master says «I have no answer», and the
 * calculation already reads a non-positive parameter as missing — so 0 deletes the stored row and
 * the card asks again, rather than storing a second spelling of «unanswered». That is also why the
 * perimeter needs no three-valued dance: null leaves it, 0 forgets it, a figure sets it.</p>
 *
 * <p>Bounds are the service's own, and there is one PER QUESTION since review B-49: a розгортка is
 * metres and a thickness is MILLIMETRES, so a shared 1000 bounded neither — a screed typed as 400
 * instead of 40 passed and put 800 kg/m² of dry mix on the shopping list. A key naming a position
 * that is not in this estimate is IGNORED, never refused — a line deleted between the tap and the
 * request is the ordinary way that happens.</p>
 */
public record MaterialParamsRequest(
        // @Digits(4, 3) is the column's own numeric(12,3) precision seen from here (review B-112):
        // 0.0004 rounded to 0 there and failed `CHECK (value > 0)` as a 500.
        @DecimalMin("0") @DecimalMax("1000") @Digits(integer = 4, fraction = 3) BigDecimal perimeter,
        Map<UUID, @NotNull @DecimalMin("0") @DecimalMax("5") @Digits(integer = 4, fraction = 3) BigDecimal> sections,
        Map<UUID, @NotNull @DecimalMin("0") @DecimalMax("150") @Digits(integer = 4, fraction = 3) BigDecimal> thicknesses
) {}
