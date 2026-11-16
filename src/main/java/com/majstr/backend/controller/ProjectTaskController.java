package com.majstr.backend.controller;

import com.majstr.backend.dto.TaskRequest;
import com.majstr.backend.dto.TaskResponse;
import com.majstr.backend.dto.TaskUpdateRequest;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProjectTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Object tasks («Що треба зробити»). Owner-scoped in the service; no plan gate. */
@RestController
@RequestMapping("/api/projects/{id}/tasks")
@RequiredArgsConstructor
@Tag(name = "Object tasks", description = "Per-object to-do lines with a done tick")
@SecurityRequirement(name = "bearer-jwt")
public class ProjectTaskController {

    private final ProjectTaskService taskService;

    @Operation(summary = "List the object's tasks (open first)")
    @GetMapping
    public List<TaskResponse> list(@PathVariable UUID id,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return taskService.list(id, principal.id());
    }

    @Operation(summary = "Add a task to the object")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse add(@PathVariable UUID id,
                            @Valid @RequestBody TaskRequest req,
                            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
                            @AuthenticationPrincipal UserPrincipal principal) {
        return taskService.add(id, principal.id(), req, entityId);
    }

    @Operation(summary = "Edit a task or tick it done")
    @PatchMapping("/{taskId}")
    public TaskResponse update(@PathVariable UUID id,
                               @PathVariable UUID taskId,
                               @Valid @RequestBody TaskUpdateRequest req,
                               @AuthenticationPrincipal UserPrincipal principal) {
        return taskService.update(id, taskId, principal.id(), req);
    }

    @Operation(summary = "Delete a task")
    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @PathVariable UUID taskId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        taskService.delete(id, taskId, principal.id());
        return ResponseEntity.noContent().build();
    }
}
