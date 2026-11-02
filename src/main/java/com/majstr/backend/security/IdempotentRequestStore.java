package com.majstr.backend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * One row per submission of a write that has no id of its own (review P-78). Plain JDBC with each statement
 * committing by itself: the claim has to be visible to the retry while the first attempt is still running.
 */
@Component
@RequiredArgsConstructor
public class IdempotentRequestStore {

    /** Longer than any of these requests takes. A row older than this was left by a crash. */
    private static final String STALE = "now() - interval '5 minutes'";

    private final JdbcTemplate jdbc;

    public sealed interface Claim {
        record New() implements Claim { }

        record InFlight() implements Claim { }

        record Done(int status, String contentType, byte[] body) implements Claim { }
    }

    public Claim claim(UUID userId, UUID key, String path) {
        int inserted = jdbc.update("""
                INSERT INTO idempotent_request (user_id, request_key, path) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, userId, key, path);
        if (inserted == 1) {
            return new Claim.New();
        }
        Claim.Done done = done(userId, key, path);
        if (done != null) {
            return done;
        }
        int takenOver = jdbc.update("UPDATE idempotent_request SET created_at = now() "
                + "WHERE user_id = ? AND request_key = ? AND path = ? AND status IS NULL AND created_at < " + STALE,
                userId, key, path);
        return takenOver == 1 ? new Claim.New() : new Claim.InFlight();
    }

    private Claim.Done done(UUID userId, UUID key, String path) {
        try {
            return jdbc.queryForObject("""
                    SELECT status, content_type, body FROM idempotent_request
                    WHERE user_id = ? AND request_key = ? AND path = ? AND status IS NOT NULL
                    """, (rs, row) -> new Claim.Done(rs.getInt(1), rs.getString(2), rs.getBytes(3)), userId, key, path);
        } catch (EmptyResultDataAccessException running) {
            return null;
        }
    }

    public void complete(UUID userId, UUID key, String path, int status, String contentType, byte[] body) {
        jdbc.update("UPDATE idempotent_request SET status = ?, content_type = ?, body = ? "
                + "WHERE user_id = ? AND request_key = ? AND path = ?", status, contentType, body, userId, key, path);
    }

    /** The attempt did not succeed, so a retry must really run. */
    public void release(UUID userId, UUID key, String path) {
        jdbc.update("DELETE FROM idempotent_request WHERE user_id = ? AND request_key = ? AND path = ?",
                userId, key, path);
    }

    /** A retry comes within minutes, and the stored answers hold a master's data. */
    @Scheduled(cron = "${app.cleanup.idempotent-requests-cron:0 15 3 * * *}")
    public void sweep() {
        jdbc.update("DELETE FROM idempotent_request WHERE created_at < now() - interval '1 day'");
    }
}
