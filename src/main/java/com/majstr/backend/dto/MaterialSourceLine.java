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
 * <p>{@code param} is the third factor of the two per-position bases and null everywhere else, and
 * {@code basis} says which question it answered and therefore what unit it is in: METRES for a
 * {@link NormBasis#SECTION} («12 м.п. × переріз 0,4 м × 2,2 = 10,56 м²»), MILLIMETRES for a
 * {@link NormBasis#THICKNESS} («20 м² × 15 мм × 0,95 = 285 кг», V137). One field rather than two,
 * because no row is ever both — but it is NOT called {@code section} any more: a field named after
 * one of its two meanings is how a millimetre gets read as a metre. It is carried separately rather
 * than folded into {@code qtyPerUnit} because the master may correct the coefficient on this very
 * row, and a coefficient premultiplied by his own figure is not the norm he would be editing.</p>
 *
 * <p>{@code qtyPerUnit} is the coefficient <b>as actually used</b> — the shipped figure already
 * rescaled by the master's paint or joint habit (V137). The arithmetic on screen must multiply out
 * to the quantity beside it, or the one widget whose job is trust is showing its work wrong.</p>
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
        BigDecimal param,
        BigDecimal amount
) {}
