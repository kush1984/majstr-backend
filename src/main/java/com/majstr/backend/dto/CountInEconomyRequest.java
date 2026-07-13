package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;

/** Toggle whether an estimate counts toward the object's economy (income). */
public record CountInEconomyRequest(
        @NotNull Boolean countInEconomy
) {}
