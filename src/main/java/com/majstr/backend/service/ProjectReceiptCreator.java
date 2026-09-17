package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectReceiptResponse;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ProjectReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional halves of {@link ProjectReceiptService#add}, in their OWN bean — so a lost race
 * on the client-supplied primary key rolls back cleanly and the caller can answer with the receipt
 * that won instead of a 500. Same shape and same reason as {@link WorkActReceiptCreator}: the
 * recovery must re-read the row AFTER the failed insert, which the poisoned transaction cannot do.
 */
@Component
@RequiredArgsConstructor
class ProjectReceiptCreator {

    private final ProjectReceiptRepository receiptRepository;
    private final ProjectService projectService;

    /**
     * Owner check plus the replay lookup: the receipt this create already landed, if any. A replay
     * naming a foreign object is a 404, so the idempotent path is as owner-scoped as the create.
     */
    @Transactional(readOnly = true)
    public Optional<ProjectReceiptResponse> prepare(UUID projectId, UUID ownerId, UUID requestedId) {
        projectService.loadOwned(projectId, ownerId);
        return find(requestedId, projectId);
    }

    /** Re-read a replayed create's receipt in a FRESH transaction, after a duplicate-key failure. */
    @Transactional(readOnly = true)
    public Optional<ProjectReceiptResponse> replay(UUID requestedId, UUID projectId) {
        return find(requestedId, projectId);
    }

    /**
     * One create attempt in its own transaction. {@code saveAndFlush} so a duplicate primary key
     * surfaces HERE rather than on commit, where the caller could no longer tell it apart.
     */
    @Transactional
    public ProjectReceiptResponse attempt(UUID projectId, UUID requestedId, String label,
                                         BigDecimal amount, LocalDate issuedAt, String storageKey,
                                         int sortOrder) {
        ProjectReceipt receipt = ProjectReceipt.builder()
                .id(requestedId)
                .projectId(projectId)
                .label(label)
                .amount(amount)
                .issuedAt(issuedAt)
                .storageKey(storageKey)
                .sortOrder(sortOrder)
                .build();
        // A fresh receipt is reimbursable, owns no expense and carries NO fiscal identity yet — the
        // photo is saved before anything is read off it — so it cannot be a duplicate warning's
        // subject. The warning is a read-path fact computed across both receipt tables (B-04).
        return ProjectReceiptResponse.from(receiptRepository.saveAndFlush(receipt));
    }

    private Optional<ProjectReceiptResponse> find(UUID requestedId, UUID projectId) {
        if (requestedId == null) {
            return Optional.empty();
        }
        return receiptRepository.findById(requestedId).map(r -> {
            if (!r.getProjectId().equals(projectId)) {
                throw new ResourceNotFoundException("Receipt not found: " + requestedId);
            }
            // A replay answers with the row as it stands; the list refetch behind it carries the
            // cross-table warning and the act number, which need a project-wide read this has not done.
            return ProjectReceiptResponse.from(r);
        });
    }
}
