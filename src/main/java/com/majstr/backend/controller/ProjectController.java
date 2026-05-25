package com.majstr.backend.controller;

import com.majstr.backend.dto.ProjectRequest;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.dto.ProjectStatusUpdateRequest;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProjectService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Construction projects / sites")
@SecurityRequirement(name = "bearer-jwt")
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "Create a project")
    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectRequest req,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(req, principal.id()));
    }

    @Operation(summary = "List my projects, optionally filtered by status")
    @GetMapping
    public List<ProjectResponse> list(@RequestParam(required = false) ProjectStatus status,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return projectService.listForOwner(principal.id(), status);
    }

    @Operation(summary = "Get a project by id")
    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable UUID id,
                               @AuthenticationPrincipal UserPrincipal principal) {
        return projectService.get(id, principal.id());
    }

    @Operation(summary = "Update a project")
    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody ProjectRequest req,
                                  @AuthenticationPrincipal UserPrincipal principal) {
        return projectService.update(id, req, principal.id());
    }

    @Operation(summary = "Change project status")
    @PatchMapping("/{id}/status")
    public ProjectResponse updateStatus(@PathVariable UUID id,
                                        @Valid @RequestBody ProjectStatusUpdateRequest req,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return projectService.updateStatus(id, req.status(), principal.id());
    }

    @Operation(summary = "Delete a project (cascades to estimates and their items)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        projectService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }
}
