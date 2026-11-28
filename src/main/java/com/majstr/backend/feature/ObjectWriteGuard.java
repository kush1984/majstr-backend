package com.majstr.backend.feature;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ObjectReadOnlyException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Which objects the master may still CHANGE (iteration-read-only-objects). The FREE object cap
 * ({@link Limit#MAX_PROJECTS}) used to guard only the create, so a lapsed PRO kept every object he
 * made — renamed for the next client, three fresh estimates each — and never paid again.
 *
 * <p>The rule, computed from the rows, nothing stored: on a plan with no object cap everything is
 * writable; otherwise an object is writable when it is the training sample, a one-off paid object
 * (V214), or one of the first {@code MAX_PROJECTS} objects he ever made
 * ({@link ProjectRepository#findCappedIdsOldestFirst}). A transferred object (V201) is outside the cap
 * and therefore never writable on FREE. Everything else is read-only: opened, shared, printed, its
 * money recorded, its client signing — but not changed.</p>
 *
 * <p>Callers are the master's write doors on an object's content (estimates and their lines, acts,
 * photos, measurements, notes, tasks, the shopping list, the calculator's answers, the object's own
 * fields). Money, sharing, messages and everything the client does are deliberately NOT behind it.</p>
 */
@Component
@RequiredArgsConstructor
public class ObjectWriteGuard {

    private final ProjectRepository projectRepository;

    public boolean isWritable(Project project) {
        return writableIn(project.getOwner()).test(project);
    }

    public void requireWritable(Project project) {
        if (!isWritable(project)) {
            throw new ObjectReadOnlyException(project.getId(),
                    PlanConfig.limit(project.getOwner().getPlan(), Limit.MAX_PROJECTS));
        }
    }

    public void requireWritable(UUID projectId) {
        requireWritable(projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId)));
    }

    /** The rule for every object of one owner — one query, for a list of his objects. */
    public Predicate<Project> writableIn(User owner) {
        Plan plan = owner.getPlan();
        int max = PlanConfig.limit(plan, Limit.MAX_PROJECTS);
        if (max < 0) {
            return p -> true;
        }
        Set<UUID> firstMade = new HashSet<>(
                projectRepository.findCappedIdsOldestFirst(owner.getId(), PageRequest.of(0, Math.max(max, 1))));
        if (max == 0) {
            firstMade.clear();
        }
        return p -> p.isSample() || p.getPaidAt() != null || firstMade.contains(p.getId());
    }
}
