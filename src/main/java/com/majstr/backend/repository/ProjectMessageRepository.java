package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectMessageRepository extends JpaRepository<ProjectMessage, UUID> {

    List<ProjectMessage> findByEstimateIdOrderByCreatedAtAsc(UUID estimateId);

    /**
     * Every message on an object, newest first.
     *
     * <p>LEFT join on the estimate, not an inner one: a message sent through the master's link has no
     * estimate, and an inner join would hide exactly the messages this feature exists for. Fetched
     * rather than lazy because {@link com.majstr.backend.dto.MessageView} reads its name.</p>
     */
    @Query("""
            SELECT m FROM ProjectMessage m
            LEFT JOIN FETCH m.estimate
            WHERE m.project.id = :projectId
            ORDER BY m.createdAt DESC
            """)
    List<ProjectMessage> findByProjectIdOrderByCreatedAtDesc(@Param("projectId") UUID projectId);

    /** Unread on a single object — the badge on its row. */
    long countByProjectIdAndReadFalse(UUID projectId);

    /** All of the owner's unread, across every object — the header bell. */
    long countByProjectOwnerIdAndReadFalse(UUID ownerId);

    /**
     * Unread count per object for a set of objects. One grouped query (no N+1); an object with
     * nothing unread is simply absent from the result.
     * Returns rows of {@code [project_id (UUID), count (Long)]}.
     */
    @Query("""
            SELECT m.project.id, COUNT(m)
            FROM ProjectMessage m
            WHERE m.project.id IN :projectIds AND m.read = false
            GROUP BY m.project.id
            """)
    List<Object[]> countUnreadByProjectIds(@Param("projectIds") Collection<UUID> projectIds);
}
