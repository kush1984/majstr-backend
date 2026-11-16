package com.majstr.backend.dto;

import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.InboxItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.UUID;

/** One thing found in an inbox message, as the owner sees it. {@code issue} says why it waits. */
public record InboxItemView(
        UUID id,
        InboxItem.Kind kind,
        InboxItem.Status status,
        UUID projectId,
        String projectName,
        UUID estimateId,
        String text,
        BigDecimal quantity,
        Unit unit,
        BigDecimal unitPrice,
        ItemType itemType,
        BigDecimal amount,
        CashCategory category,
        InboxItem.Issue issue
) {
    public static InboxItemView from(InboxItem i, String projectName) {
        return new InboxItemView(i.getId(), i.getKind(), i.getStatus(), i.getProjectId(), projectName,
                i.getEstimateId(), i.getText(), i.getQuantity(), i.getUnit(), i.getUnitPrice(),
                i.getItemType(), i.getAmount(), i.getCategory(), i.getIssue());
    }
}
