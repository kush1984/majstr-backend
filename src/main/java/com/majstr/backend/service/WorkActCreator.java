package com.majstr.backend.service;

import com.majstr.backend.dto.WorkActCreateRequest;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.entity.ActNumberFormat;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.WorkActConflictException;
import com.majstr.backend.exception.WorkActOpenException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One attempt at creating a work act, in its OWN transaction — so a lost race on the
 * {@code UNIQUE(user_id, number)} constraint rolls back cleanly and {@link WorkActService#create}
 * can retry. It lives in a separate bean deliberately: the retry loop must sit OUTSIDE the
 * transaction (a constraint violation poisons the current one), and self-invocation would not cross
 * the proxy.
 */
@Component
@RequiredArgsConstructor
class WorkActCreator {

    private final WorkActRepository workActRepository;
    private final WorkActResponseFactory responseFactory;
    private final ProjectService projectService;
    private final FeatureGuard featureGuard;

    /**
     * One create attempt in its own transaction. Public so Spring's transaction proxy actually
     * applies (it silently no-ops on non-public methods). Builds the response HERE so the caller
     * (non-transactional {@link WorkActService#create}) never touches the act's lazy project.
     *
     * @return the created (or idempotently-existing) act's response.
     */
    @Transactional
    public WorkActResponse attempt(UUID projectId, WorkActCreateRequest req, UUID ownerId, UUID requestedId) {
        // Idempotency BEFORE any gate/limit check, like every other offline-replayable create.
        if (requestedId != null) {
            Optional<WorkAct> existing = workActRepository.findById(requestedId);
            if (existing.isPresent()) {
                WorkAct act = existing.get();
                // Owner FIRST (review fix): without it, a caller replaying someone else's UUID pair
                // would be handed that act's response — the replay path must be as owner-scoped as
                // the create it stands in for.
                if (!act.getProject().getOwner().getId().equals(ownerId)) {
                    throw new AccessDeniedException("Work act does not belong to the current user");
                }
                if (!act.getProject().getId().equals(projectId)) {
                    throw new AccessDeniedException("Work act belongs to a different project");
                }
                return responseFactory.build(act);
            }
        }

        Project project = projectService.loadOwned(projectId, ownerId);
        User owner = project.getOwner();
        featureGuard.requireFeature(owner, Feature.WORK_ACTS);

        List<WorkAct> acts = workActRepository.findByProjectIdOrderByIssuedAtDescCreatedAtDesc(projectId);
        acts.stream()
                .filter(a -> a.getStatus() == WorkActStatus.DRAFT || a.getStatus() == WorkActStatus.SENT)
                .findFirst()
                .ifPresent(open -> { throw new WorkActOpenException(open.getNumber()); });
        if (acts.stream().anyMatch(a -> a.getKind() == WorkActKind.FINAL)) {
            throw new WorkActConflictException("error.work-act.final-exists", "WORK_ACT_FINAL_EXISTS");
        }

        WorkAct act = WorkAct.builder()
                .id(requestedId)
                .userId(owner.getId())
                .project(project)
                .number(nextNumber(owner, req))
                .title(trim(req.title()))
                .kind(req.kind())
                .status(WorkActStatus.DRAFT)
                .issuedAt(req.issuedAt())
                .periodFrom(req.periodFrom())
                .periodTo(req.periodTo())
                .place(trim(req.place()))
                .contractRef(trim(req.contractRef()))
                .note(trim(req.note()))
                .showMaterials(req.showMaterials() == null || req.showMaterials())
                // Default OFF (acts-fix): the reference block only makes sense from the second act on,
                // and its old per-line shape duplicated the act table — the master opts in per act.
                .showCumulative(req.showCumulative() != null && req.showCumulative())
                .advanceOffset(req.advanceOffset())
                .build();
        // saveAndFlush so a UNIQUE(user_id, number) clash surfaces HERE (this attempt's tx),
        // not on commit — the caller then retries with a freshly recomputed number.
        return responseFactory.build(workActRepository.saveAndFlush(act));
    }

    /** Continuous per master (never reset per year), so «7» / «7/2026» stays unique under the
     *  constraint for both formats; the year for WITH_YEAR is the issue year. */
    private String nextNumber(User owner, WorkActCreateRequest req) {
        int seq = workActRepository.maxNumberSeqForUser(owner.getId()) + 1;
        ActNumberFormat format = owner.getActNumberFormat() == null
                ? ActNumberFormat.PLAIN : owner.getActNumberFormat();
        return switch (format) {
            case PLAIN -> String.valueOf(seq);
            case WITH_YEAR -> seq + "/" + req.issuedAt().getYear();
        };
    }

    private static String trim(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
