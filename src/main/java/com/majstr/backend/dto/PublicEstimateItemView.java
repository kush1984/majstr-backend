package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;

public record PublicEstimateItemView(
        ItemType type,
        String name,
        Unit unit,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        int sortOrder,
        /**
         * The section this line sits in, or null for none. The client page groups by it, in the
         * order sortOrder puts the lines — the same arrangement the master dragged into place and
         * the same one the PDF shows, so the three never disagree.
         */
        String category
) {}
