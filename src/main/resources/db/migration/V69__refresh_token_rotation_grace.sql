-- Rotation grace window: remembers WHEN a refresh token was rotated away, so a token replayed
-- moments later can be honoured instead of killing the session.
--
-- The bug it fixes: rotation revokes the old token the instant it is used, and the client only
-- stores the new one AFTER the response arrives. On a flaky mobile network the request reaches
-- the server (old revoked, new issued) and the response is lost — the master is now holding a
-- token the server considers dead. The next attempt 401s, the PWA treats that as "auth is
-- gone", and logs them out AND wipes their unsynced offline queue. Masters work in basements,
-- lifts and half-built flats; this is a normal Tuesday, not an edge case.
--
-- A separate column rather than reusing `revoked`, because the grace must NOT apply to logout:
-- an explicit logout has to kill the token immediately. Only rotation stamps rotated_at, so
-- only rotation is forgiving.
ALTER TABLE refresh_tokens ADD COLUMN rotated_at TIMESTAMPTZ;

COMMENT ON COLUMN refresh_tokens.rotated_at IS
    'Set when this token was exchanged for a new one. NULL for tokens revoked by logout, which get no grace.';
