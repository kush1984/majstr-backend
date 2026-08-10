package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/** {@code contractedTotal} is shown alongside the rows so the master sees what the split is
 *  computed against ("разом за кошторисами об'єкта") before confirming. */
public record PaymentSplitPreviewResponse(BigDecimal contractedTotal, List<PaymentSplitRow> rows) {}
