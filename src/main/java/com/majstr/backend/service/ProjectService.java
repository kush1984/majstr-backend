package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectRequest;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Limit;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ClientService clientService;
    private final LimitService limitService;

    @Transactional
    public ProjectResponse create(ProjectRequest req, UUID ownerId) {
        limitService.requireWithinLimit(ownerId, Limit.MAX_PROJECTS);
        User owner = userRepository.getReferenceById(ownerId);
        Client client = req.clientId() == null ? null : clientService.loadOwned(req.clientId(), ownerId);
        Project project = Project.builder()
                .owner(owner)
                .client(client)
                .name(req.name().trim())
                .address(req.address().trim())
                .description(normalize(req.description()))
                .status(ProjectStatus.DRAFT)
                .build();
        return ProjectResponse.from(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForOwner(UUID ownerId, ProjectStatus status) {
        List<Project> projects = status == null
                ? projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
                : projectRepository.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, status);
        return projects.stream().map(ProjectResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID id, UUID ownerId) {
        return ProjectResponse.from(loadOwned(id, ownerId));
    }

    @Transactional
    public ProjectResponse update(UUID id, ProjectRequest req, UUID ownerId) {
        Project project = loadOwned(id, ownerId);
        project.setName(req.name().trim());
        project.setAddress(req.address().trim());
        project.setDescription(normalize(req.description()));
        project.setClient(req.clientId() == null ? null : clientService.loadOwned(req.clientId(), ownerId));
        return ProjectResponse.from(project);
    }

    @Transactional
    public ProjectResponse updateStatus(UUID id, ProjectStatus status, UUID ownerId) {
        Project project = loadOwned(id, ownerId);
        project.setStatus(status);
        return ProjectResponse.from(project);
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        Project project = loadOwned(id, ownerId);
        projectRepository.delete(project);
        // Estimates and items are cascaded by the FK ON DELETE CASCADE.
    }

    Project loadOwned(UUID id, UUID ownerId) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id));
        if (!project.getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Project does not belong to the current user");
        }
        return project;
    }

    private static String normalize(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
