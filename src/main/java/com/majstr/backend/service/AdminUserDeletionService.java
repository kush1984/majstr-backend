package com.majstr.backend.service;

import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.AdminUserDeleteRefusedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CashEntryRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.ShoppingListItemRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The admin's whole-account delete: the user row goes and every FK cascades from it (clients,
 * objects, estimates, acts, catalog, money, sessions). Unlike the master's own object delete this
 * deliberately walks past {@code ProjectDeleteGuard} — wiping signed papers is the point here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserDeletionService {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final CashEntryRepository cashEntryRepository;
    private final WorkActRepository workActRepository;
    private final StorageCleanup cleanup;

    @Transactional
    public void delete(UUID userId, UUID adminId) {
        if (userId.equals(adminId)) {
            throw new AdminUserDeleteRefusedException();
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (user.getRole() == Role.ADMIN) {
            throw new AdminUserDeleteRefusedException();
        }

        // Keys first: the cascade takes the only pointer to every file (B-26).
        List<String> blobKeys = new ArrayList<>();
        for (Project project : projectRepository.findByOwnerIdOrderByCreatedAtDesc(userId)) {
            blobKeys.addAll(projectService.blobKeysOf(project.getId()));
            // The sibling-cascade trap ProjectService#delete documents, once per object.
            shoppingListItemRepository.deleteByProjectId(project.getId());
        }
        blobKeys.addAll(cashEntryRepository.findStorageKeysByOwnerId(userId));
        blobKeys.add(user.getLogoUrl());

        workActRepository.deleteByUserId(userId);
        userRepository.deleteCascading(userId);
        cleanup.afterCommit(blobKeys);
        log.info("Admin {} deleted user {} ({} stored files queued for removal)",
                adminId, userId, blobKeys.size());
    }
}
