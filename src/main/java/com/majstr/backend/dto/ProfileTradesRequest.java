package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * The system trades the master works in — the «Чим займаєтесь?» screen after registration (V190).
 * Replaces the set; an empty set is legal (he may skip). A trade that was not there before brings
 * its starter catalog with it. Custom trades keep their own endpoints.
 */
public record ProfileTradesRequest(
        @NotNull Set<@NotNull Trade> trades
) {}
