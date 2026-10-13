package com.majstr.backend.repository;

import com.majstr.backend.entity.SignedDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SignedDocumentRepository extends JpaRepository<SignedDocument, UUID> {

    /** Every blob an estimate's signatures left in storage — collected before a delete cascades. */
    @Query(value = """
            SELECT k FROM (
                SELECT pdf_key AS k FROM signed_document WHERE estimate_id = :estimateId
                UNION ALL
                SELECT logo_key FROM signed_document WHERE estimate_id = :estimateId
            ) keys WHERE k IS NOT NULL
            """, nativeQuery = true)
    List<String> findStorageKeysByEstimateId(@Param("estimateId") UUID estimateId);

    /** The same for a whole object, its acts' signatures included. */
    @Query(value = """
            WITH docs AS (
                SELECT d.pdf_key, d.logo_key
                  FROM signed_document d
                  JOIN estimates e ON e.id = d.estimate_id
                 WHERE e.project_id = :projectId
                UNION ALL
                SELECT d.pdf_key, d.logo_key
                  FROM signed_document d
                  JOIN work_act a ON a.id = d.work_act_id
                 WHERE a.project_id = :projectId
            )
            SELECT pdf_key FROM docs WHERE pdf_key IS NOT NULL
            UNION ALL
            SELECT logo_key FROM docs WHERE logo_key IS NOT NULL
            """, nativeQuery = true)
    List<String> findStorageKeysByProjectId(@Param("projectId") UUID projectId);
}
