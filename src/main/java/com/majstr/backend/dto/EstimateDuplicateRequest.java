package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Copy an estimate and mark the chosen lines up — the бригадир's two-price workflow.
 *
 * @param name          the copy's name; null → the source's name with the markup appended
 * @param markupPercent how much to add. Only ADDING for now (min 0) — a foreman marks up, and a
 *                      negative here would quietly turn the object economy's earnings negative
 *                      with nothing on screen explaining why
 * @param itemIds       which lines get the markup. <b>null means every WORK line</b> — the default
 *                      the picker shows, and the one that matters: materials are bought at cost and
 *                      passed through, so marking them up by default would inflate a client's
 *                      estimate in a way the master never asked for. A foreman who does want to
 *                      mark a material up ticks it himself, and then it arrives in this list.
 */
public record EstimateDuplicateRequest(
        @Size(max = 255) String name,
        @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal markupPercent,
        List<UUID> itemIds
) {}
