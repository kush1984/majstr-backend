package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outcome of committing an estimate import: the new estimate's id + total, and how
 * many catalog positions were created / price-updated / skipped as a side effect.
 */
public record EstimateImportCommitResponse(
        UUID estimateId,
        BigDecimal total,
        int catalogCreated,
        int catalogUpdated,
        int catalogSkipped
) {}
