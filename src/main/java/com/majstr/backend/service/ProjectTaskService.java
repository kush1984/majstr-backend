package com.majstr.backend.service;

import com.majstr.backend.dto.TaskRequest;
import com.majstr.backend.dto.TaskResponse;
import com.majstr.backend.dto.TaskUpdateRequest;
import com.majstr.backend.entity.ProjectTask;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ProjectTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Object tasks (V181) — «що треба зробити». Owner-scoped through the object, no plan gate, same shape
 * as {@link ProjectNoteService}. Open tasks come first in the order they were written; done ones
 * follow, the most recently ticked first.
 */
@Service
@RequiredArgsConstructor
public class ProjectTaskService {

    private static final Comparator<ProjectTask> ORDER = Comparator
            .comparing((ProjectTask t) -> t.getDoneAt() != null)
            .thenComparing(t -> t.getDoneAt() == null ? Instant.EPOCH : t.getDoneAt(), Comparator.reverseOrder())
            .thenComparing(ProjectTask::getCreatedAt);

    private final ProjectTaskRepository taskRepository;
    private final ProjectService projectService;

    @Transactional(readOnly = true)
    public List<TaskResponse> list(UUID objectId, UUID ownerId) {
        projectService.loadOwned(objectId, ownerId);
        return taskRepository.findByProjectIdOrderByCreatedAtAsc(objectId).stream()
                .sorted(ORDER)
                .map(TaskResponse::from)
                .toList();
    }

    /** Optionally under a CLIENT-PROVIDED id, so a replayed offline create returns the first row. */
    @Transactional
    public TaskResponse add(UUID objectId, UUID ownerId, TaskRequest req, UUID requestedId) {
        projectService.loadOwned(objectId, ownerId);
        if (requestedId != null) {
            var existing = taskRepository.findById(requestedId);
            if (existing.isPresent()) {
                if (!existing.get().getProjectId().equals(objectId)) {
                    // A foreign id is «not found», never «forbidden» (IDOR convention).
                    throw new ResourceNotFoundException("Task not found: " + requestedId);
                }
                return TaskResponse.from(existing.get());
            }
        }
        return TaskResponse.from(taskRepository.save(ProjectTask.builder()
                .id(requestedId)
                .projectId(objectId)
                .body(req.body().trim())
                .build()));
    }

    @Transactional
    public TaskResponse update(UUID objectId, UUID taskId, UUID ownerId, TaskUpdateRequest req) {
        projectService.loadOwned(objectId, ownerId);
        ProjectTask task = taskRepository.findByIdAndProjectId(taskId, objectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        if (req.body() != null && !req.body().isBlank()) {
            task.setBody(req.body().trim());
        }
        if (req.done() != null && req.done() != (task.getDoneAt() != null)) {
            task.setDoneAt(req.done() ? Instant.now() : null);
        }
        return TaskResponse.from(task);
    }

    /** Idempotent, like a note's: a replayed delete of a task already gone is not an error. */
    @Transactional
    public void delete(UUID objectId, UUID taskId, UUID ownerId) {
        projectService.loadOwned(objectId, ownerId);
        taskRepository.findByIdAndProjectId(taskId, objectId).ifPresent(taskRepository::delete);
    }
}
