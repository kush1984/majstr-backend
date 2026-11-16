package com.majstr.backend.dto;

import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.InboxItem;
import com.majstr.backend.entity.Unit;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Correct a waiting inbox item before applying it. A PATCH: an absent field keeps its value.
 * {@code projectId} cannot say «none» by being null, so {@code clearProject} does — money with no
 * object goes to «Мої гроші».
 */
public record InboxItemUpdateRequest(
        InboxItem.Kind kind,
        UUID projectId,
        Boolean clearProject,
        /** Which open estimate an estimate line goes into, when the object has several. */
        UUID estimateId,
        @Size(max = 2000) String text,
        @DecimalMin("0.001") @Digits(integer = 12, fraction = 3) BigDecimal quantity,
        Unit unit,
        @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal unitPrice,
        @DecimalMin("0.01") @DecimalMax("100000000") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        CashCategory category
) {}
