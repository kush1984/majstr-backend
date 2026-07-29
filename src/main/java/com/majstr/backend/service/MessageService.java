package com.majstr.backend.service;

import com.majstr.backend.dto.MessageView;
import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ProjectMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
public class MessageService {

    private final ProjectMessageRepository messageRepository;
    private final ProjectService projectService;
    private final MessageFileService messageFileService;

    /**
     * Mirrors the retention service's grace period. Read here because the view turns a warning into the
     * date shown in the app, and the client must not have to know the server's schedule.
     */
    @Value("${app.message-files.grace-days:14}")
    private int graceDays;

    @Transactional(readOnly = true)
    public List<MessageView> listForProject(UUID projectId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId); // 404 unknown / 403 foreign
        return messageRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(m -> MessageView.from(m, graceDays))
                .toList();
    }

    @Transactional
    public MessageView markRead(UUID projectId, UUID questionId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId); // 404 unknown / 403 foreign
        ProjectMessage question = messageRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question not found: " + questionId));
        // The message must belong to the named object; otherwise hide its existence.
        if (!question.getProject().getId().equals(projectId)) {
            throw new ResourceNotFoundException("Question not found: " + questionId);
        }
        question.setRead(true);
        return MessageView.from(question, graceDays);
    }

    /**
     * Remove a message from an object. Idempotent: a message already gone is not a 404, so a
     * double-tap on a slow connection does not turn into an error the master has to read.
     *
     * <p>Files attached to it go with it — that arrives in a later step, through the FK.</p>
     */
    /**
     * Read one attachment. Ownership of the OBJECT is what grants access; the file is then resolved
     * only through its message, so nothing here can reach another master's upload.
     */
    @Transactional
    public MessageFileService.MessageFileContent openFile(UUID projectId, UUID messageId, UUID fileId,
                                                         UUID ownerId) {
        projectService.loadOwned(projectId, ownerId); // 404 unknown / 403 foreign
        ProjectMessage message = messageRepository.findById(messageId)
                .filter(m -> m.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + messageId));
        return messageFileService.open(message.getId(), fileId);
    }

    @Transactional
    public void delete(UUID projectId, UUID messageId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId); // 404 unknown / 403 foreign
        messageRepository.findById(messageId)
                .filter(m -> m.getProject().getId().equals(projectId))
                .ifPresent(m -> {
                    // The rows CASCADE with the message; the stored bytes do not, and nothing else
                    // would ever come back for them.
                    messageFileService.deleteStoredFiles(m);
                    messageRepository.delete(m);
                });
    }
}
