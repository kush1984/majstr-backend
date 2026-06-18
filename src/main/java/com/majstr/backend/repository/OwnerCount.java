package com.majstr.backend.repository;

import java.util.UUID;

/**
 * Aggregate row {@code (owner_id, count)} for the admin activity columns — one
 * grouped query per entity over the page's user ids, folded into the list (no
 * N+1, same pattern as the project-list unread-question count).
 */
public interface OwnerCount {
    UUID getOwnerId();
    long getCnt();
}
