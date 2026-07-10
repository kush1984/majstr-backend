# Iteration: capture the device / OS a master logs in from (admin)

Product wants to see whether masters use a phone or a computer (and the OS) to
steer mobile-first decisions. Browser is deliberately not tracked (not useful).

- **Status:** 🔨 Backend code complete; `./gradlew build` pending at the gate.
- **Migration:** `V44__add_user_last_device.sql` (two nullable columns on `users`).
- **App version:** PWA `0.5.3 → 0.5.4` (backend-only iteration; the PWA version is
  the product's single visible version).

## How it works

- `DeviceInfo.parse(userAgent)` → `{deviceType: MOBILE/TABLET/DESKTOP/UNKNOWN, os}`
  (heuristic over the UA string; OS ∈ iOS/Android/Windows/macOS/Linux/ChromeOS).
- Captured in `LastActiveTracker.touch(userId, userAgent)`, called from
  `JwtAuthenticationFilter` on **every authenticated request** and **throttled to
  once per 5 min per user** (same mechanism as `last_active_at`). So the stored
  device is always the *current* one — login itself is covered by the immediate
  `/api/auth/me` that follows it. A blank/unrecognized UA (curl, API tools) does
  **not** overwrite a previously-known device (`isKnown()` guard).
- Stored on `users.last_device_type` + `users.last_os`; surfaced in
  `AdminUserSummary` and `AdminUserDetail`.

## Where it shows

There is **no admin UI in the PWA** — admin data is the `AdminUserController`
JSON (viewed via Swagger / an external tool). The two new fields ride along on
`GET /api/admin/users` and `GET /api/admin/users/{id}`. If a real admin screen is
built later, they're already in the payload.

## Tests

- `DeviceInfoTest` — iPhone→MOBILE/iOS, Android phone→MOBILE, Android tablet→TABLET,
  iPad→TABLET/iOS, Windows/mac/Linux→DESKTOP, blank/curl→UNKNOWN.
- `LastActiveTrackerTest` — known UA routes to `touchLastActiveAndDevice`, unknown
  falls back to `touchLastActive`, repeat inside the window is throttled.

## Gotchas / limitations

- iPadOS 13+ Safari sends a desktop ("Macintosh") UA → such iPads read as
  DESKTOP/macOS. Acceptable for a phone-vs-computer split; noted in `DeviceInfo`.
- "Installed PWA vs browser tab" is **not** in the UA — needs a client-sent hint
  (`display-mode: standalone`), like push already sends `userAgent`. Out of scope.
- Privacy: device type is low-sensitivity technical data; the policy's "technical
  data / anonymized analytics" line covers it — mention explicitly at the next
  policy review (see open-questions).
