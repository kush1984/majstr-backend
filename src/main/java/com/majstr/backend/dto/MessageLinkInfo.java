package com.majstr.backend.dto;

/**
 * Everything the public form is told, and deliberately nothing more: which object it is writing about
 * and who receives it, so whoever opened the link can see they have the right person.
 *
 * <p>No prices, no client, no estimates — that is the whole reason this link is not the portal one.</p>
 */
public record MessageLinkInfo(
        String projectName,
        String contractorName
) {}
