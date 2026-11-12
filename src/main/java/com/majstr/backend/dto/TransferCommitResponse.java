package com.majstr.backend.dto;

import java.util.List;
import java.util.UUID;

/** What «Перенести в Majstr» made: new objects, estimates, and the catalog's created/updated/skipped. */
public record TransferCommitResponse(
        int objectsCreated,
        int estimatesCreated,
        int catalogCreated,
        int catalogUpdated,
        int catalogSkipped,
        List<UUID> projectIds
) {}
