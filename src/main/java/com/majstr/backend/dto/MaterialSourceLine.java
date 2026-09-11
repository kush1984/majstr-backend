package com.majstr.backend.dto;

import com.majstr.backend.entity.NormBasis;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One position's contribution to one material: «Монтаж гіпсокартону на стіни · 22 м² × 1,0 = 22 м²».
 *
 * <p>{@code unit} is the POSITION's unit, and it is the same unit the norm was written in — the
 * calculator never converts one into another. A perimeter-based row carries the perimeter as its
 * {@code quantity} and {@link NormBasis#PERIMETER} as its basis, so the screen can say where the
 * number came from instead of showing an unexplained figure.</p>
 *
 * <p>{@code normId} is what makes the coefficient editable: the master corrects the «× 0,12» on
 * this row, and the write forks the shipped norm into one of his own. {@code ownNorm} says which of
 * the two he is looking at, so the screen offers «стандартна» only when there is one to go back
 * to.</p>
 */
public record MaterialSourceLine(
        UUID estimateItemId,
        String name,
        Unit unit,
        BigDecimal quantity,
        BigDecimal qtyPerUnit,
        UUID normId,
        boolean ownNorm,
        NormBasis basis,
        BigDecimal amount
) {}
