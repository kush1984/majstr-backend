package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateQuestion;

import java.time.Instant;
import java.util.UUID;

public record QuestionResponse(
        UUID id,
        Instant createdAt
) {
    public static QuestionResponse from(EstimateQuestion question) {
        return new QuestionResponse(question.getId(), question.getCreatedAt());
    }
}
