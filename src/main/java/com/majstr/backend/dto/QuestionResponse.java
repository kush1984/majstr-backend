package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectMessage;

import java.time.Instant;
import java.util.UUID;

public record QuestionResponse(
        UUID id,
        Instant createdAt
) {
    public static QuestionResponse from(ProjectMessage question) {
        return new QuestionResponse(question.getId(), question.getCreatedAt());
    }
}
