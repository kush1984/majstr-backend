package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Add a position to my own estimate template — name + type + unit (no quantity
 *  or price; those are resolved when the template is applied). */
public record TemplateItemRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull ItemType type,
        @NotNull Unit unit
) {}
