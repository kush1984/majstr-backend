package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Admin create/update of a default catalog position ({@code catalog_templates}).
 * {@code suggestedPrice} may be 0 (the master then sets their own). The server
 * owns {@code addedInVersion} (a create stamps the next version) — never supplied
 * here.
 */
public record AdminCatalogTemplateRequest(
        @NotNull Trade trade,
        @Size(max = 100) String category,
        @NotBlank @Size(max = 255) String name,
        @NotNull ItemType type,
        @NotNull Unit unit,
        @NotNull @DecimalMin(value = "0.00") BigDecimal suggestedPrice
) {}
