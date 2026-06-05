package com.majstr.backend.controller;

import com.majstr.backend.dto.QuestionView;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.QuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/questions")
@RequiredArgsConstructor
@Tag(name = "Client questions", description = "Questions clients leave on the public portal")
@SecurityRequirement(name = "bearer-jwt")
public class ProjectQuestionController {

    private final QuestionService questionService;

    @Operation(summary = "List a project's client questions (newest first)")
    @GetMapping
    public List<QuestionView> list(@PathVariable UUID projectId,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return questionService.listForProject(projectId, principal.id());
    }

    @Operation(summary = "Mark a question as read")
    @PatchMapping("/{questionId}/read")
    public QuestionView markRead(@PathVariable UUID projectId,
                                 @PathVariable UUID questionId,
                                 @AuthenticationPrincipal UserPrincipal principal) {
        return questionService.markRead(projectId, questionId, principal.id());
    }
}
