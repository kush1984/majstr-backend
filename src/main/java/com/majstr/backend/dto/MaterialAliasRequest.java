package com.majstr.backend.dto;

import com.majstr.backend.entity.Unit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * «Рахувати як» (V204): the master's wording of a position, counted as a library position from now on.
 *
 * @param asNameKey the library position's key as {@code NormPosition.key} gave it; null = «не потребує
 *                  матеріалів» — the line is no longer asked about
 */
public record MaterialAliasRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull Unit unit,
        @Size(max = 255) String asNameKey
) {}
