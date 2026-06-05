package com.majstr.backend.service;

import com.majstr.backend.dto.QuestionView;
import com.majstr.backend.entity.EstimateQuestion;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Contractor-facing view of the questions clients leave on the public portal.
 * Read-only inbox: the contractor sees questions and marks them read, then
 * follows up through their own channel — no in-app reply thread for now.
 * Every operation is scoped to a project the caller owns.
 */
@Service
@RequiredArgsConstructor
public class QuestionService {

    private final EstimateQuestionRepository questionRepository;
    private final ProjectService projectService;

    @Transactional(readOnly = true)
    public List<QuestionView> listForProject(UUID projectId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId); // 404 unknown / 403 foreign
        return questionRepository.findByEstimateProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(QuestionView::from)
                .toList();
    }

    @Transactional
    public QuestionView markRead(UUID projectId, UUID questionId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId); // 404 unknown / 403 foreign
        EstimateQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question not found: " + questionId));
        // The question must belong to the named project; otherwise hide its existence.
        if (!question.getEstimate().getProject().getId().equals(projectId)) {
            throw new ResourceNotFoundException("Question not found: " + questionId);
        }
        question.setRead(true);
        return QuestionView.from(question);
    }
}
