package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateQuestion;

import java.time.Instant;
import java.util.UUID;

/**
 * A client's question as the contractor sees it (full text + read state).
 * Distinct from {@link QuestionResponse}, which is the minimal acknowledgement
 * returned to the client who submitted the question on the public portal.
 */
public record QuestionView(
        UUID id,
        String authorName,
        String authorPhone,
        String message,
        boolean isRead,
        Instant createdAt
) {
    public static QuestionView from(EstimateQuestion q) {
        return new QuestionView(
                q.getId(),
                q.getAuthorName(),
                q.getAuthorPhone(),
                q.getMessage(),
                q.isRead(),
                q.getCreatedAt());
    }
}
