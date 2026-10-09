package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Publishing the object's ECONOMY portal. Only the payments card is the master's choice — which
 * estimates it shows is not (every SIGNED and counted one, review B-103), so there is no id list.
 */
public record EconomyUpdateRequest(
        @NotNull Boolean paymentsVisible
) {}
