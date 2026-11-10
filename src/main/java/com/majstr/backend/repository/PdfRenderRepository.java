package com.majstr.backend.repository;

import com.majstr.backend.dto.OwnerSource;
import com.majstr.backend.entity.PdfRender;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PdfRenderRepository extends JpaRepository<PdfRender, UUID> {

    /**
     * Masters with at least one PDF of the given reader, each with his source — the same row shape as
     * {@link EstimateRepository#findOwnersWithEstimateIn}, so the funnel can UNION the two id sets and
     * the by-source report fold the same ids.
     */
    @Query("""
            SELECT DISTINCT u.id AS ownerId, u.referralSource AS source
            FROM PdfRender r, com.majstr.backend.entity.User u
            WHERE u.id = r.ownerId AND r.reader = :reader
              AND u.role = com.majstr.backend.entity.Role.USER
            """)
    List<OwnerSource> findOwnersWithPdf(@Param("reader") PdfRender.Reader reader);

    @Query("SELECT COUNT(r) FROM PdfRender r WHERE r.reader = :reader")
    long countByReader(@Param("reader") PdfRender.Reader reader);

    /** Of these masters, the ones who made a PDF themselves — the admin list's «PDF є, поділитися — ні». */
    @Query("""
            SELECT DISTINCT r.ownerId FROM PdfRender r
            WHERE r.ownerId IN :ownerIds AND r.reader = com.majstr.backend.entity.PdfRender.Reader.MASTER
            """)
    List<UUID> findMasterPdfOwnersIn(@Param("ownerIds") Collection<UUID> ownerIds);
}
