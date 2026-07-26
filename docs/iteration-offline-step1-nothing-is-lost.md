# Iteration: offline step 1 — "nothing disappears"

The first chunk of the offline programme, and deliberately not a feature: it closes the path
where a master's finished work was destroyed by something they never saw. Every later chunk
(statuses offline, deletes offline, catalog offline, notes, photos) only gives them *more*
work to lose until this is fixed, which is why it went first.

- **Status:** 🔨 backend unverified by me (cannot run Gradle); PWA verified green
- **Migration:** V69
- **PWA:** 0.36.0

## The bug, traced

Reported as "офлайн не працює коректно, скидають користувачів". It is not an offline bug —
offline is the condition, not the cause.

1. `RefreshTokenService.rotate` revoked the old refresh token the instant it was used, with no
   record of what replaced it and no second chance.
2. The PWA stores the replacement only **after** the response arrives.
3. So a rotation whose request landed but whose reply did not — a lift, a basement, a
   half-built flat, an ordinary working day for these users — left the client holding a token
   the server had already killed.
4. The next call 4xx'd. `doRefresh` returns `null` on any 4xx → `forceLogin()`.
5. `forceLogin` cleared the tokens **and called `clearOutbox()`**.

So a lost packet logged the master out and deleted work they had genuinely done. A second
route to the same place: two contexts (PWA + a browser tab) share one refresh token in
localStorage, and the single-flight guard is a module-level variable — per context, not per
device. Whichever loses the race is logged out.

## Fix, part 1 — the server stops punishing a lost reply

`refresh_tokens.rotated_at` (V69) records **when** a token was rotated away. A token presented
again within `app.jwt.refresh-rotation-grace-seconds` (default 60, env
`REFRESH_ROTATION_GRACE_SECONDS`, 0 disables) is accepted once more and issues a fresh pair.

Three decisions worth keeping:

- **A separate column, not a reuse of `revoked`.** The grace must never cover logout: a token
  the master explicitly logged out of has to die immediately. `revoke()` leaves `rotated_at`
  null, so only rotation is forgiving. There is a test for exactly this.
- **The replay does not re-stamp `rotated_at`.** The window stays anchored to the original
  rotation, so a client stuck in a retry loop cannot push it forward indefinitely.
- **A replacement already handed out is left valid.** Re-revoking it would just move the logout
  to whichever tab was holding it — solving the race for one context by breaking the other.

Expiry is still absolute: a 30-day-old session does not get 60 extra seconds because it
happened to be rotated on its way out.

The cost is stated plainly rather than hidden: within the window a stolen *old* token is
usable once. That is the trade against logging real masters out on every bad connection, and
it is why the window is 60s and configurable down to 0.

## Fix, part 2 — the queue outlives the session (O3)

Even with the grace, a session can legitimately end. It must not take unsynced work with it.

- Every op is stamped with `ownerId` (the access token's `sub`) **at enqueue time** — by the
  time the queue drains, the session may have died and been rebuilt, so stamping at replay
  would be too late.
- `forceLogin` no longer touches the outbox. Neither does an explicit logout: planned or not,
  logging out should not be what throws away work.
- `discardForeignOps(ownerId)` runs right after login and destroys everything not authored by
  the master who just signed in — **before any request goes out**. That is what makes retention
  safe; cross-account replay is impossible.
- Ops with no owner (written by a pre-v2 build) are dropped rather than claimed. Guessing would
  risk replaying one master's work into another's account, and dropping them matches the old
  behaviour exactly, so nobody is worse off.
- Dexie schema v2 adds the `ownerId` index.

The logout confirmation text changed with the behaviour: it used to say the changes would be
wiped, which is no longer true — it now says they will sync on the next login to that account.
A toast reports what was restored so the sync isn't silent.

## Testing

Backend: rotation stamps `rotated_at`; a replay inside the window issues a fresh pair and
writes only the new row; a replay after it is rejected; a token revoked by **logout** gets no
grace; an expired token gets none either.

PWA: `ownerId` is stamped on enqueue; `discardForeignOps` keeps only the signed-in master's
ops, drops un-owned ones, and drops everything when nobody is signed in.

**`deadSessionKeepsOutbox.test.ts` is a separate file on purpose, twice over.** `forceLogin`
latches `redirectingToLogin` after its first run, so in `client.test.ts` — where earlier tests
already trip that latch — the cleanup block is skipped and the assertion would pass with or
without the fix. And the first version of the test passed against the *old* wiping code anyway,
because that cleanup is fire-and-forget and the assertion ran before it: the test now waits it
out. Verified by restoring the old behaviour and watching it go red, then removing it again.

## Gotchas
- The grace only works if the client keeps its old refresh token on a transient failure — which
  it does (`doRefresh` throws rather than returning null on network/5xx). If that ever changes
  to "clear tokens on any failure", the server-side grace becomes unreachable.
- `discardForeignOps` must stay wired into the login path. Remove it and the retained queue
  becomes a cross-account leak, not a feature.
- `TokenCleanupService` sweeps revoked tokens daily at 3am; a token rotated seconds before that
  run could lose its remaining grace. One logout a day at worst — not worth complicating.
