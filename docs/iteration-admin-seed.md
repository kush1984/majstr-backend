# First-admin auto-seed (backend)

- **Status:** ✅ Backend code complete — `./gradlew build` pending a local run
  (Gradle isn't reachable from the agent's sandbox).
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** none.
- **Goal:** a fresh production DB has no `ADMIN`, so the admin panel is
  unreachable. Auto-create the first admin on startup from env, without
  hand-editing the DB. **Backend only.** Closes the SPEC §H TODO.

## How it works

`AdminSeeder` (`bootstrap/`, an `ApplicationRunner`) runs once on startup, in
**every profile**, and is a safe no-op unless **both**:
1. `ADMIN_EMAIL` + `ADMIN_PASSWORD` are set (`app.admin.*`, env only,
   `AdminSeedProperties.isConfigured()`), and
2. no `ADMIN` exists yet (`UserRepository.existsByRole(Role.ADMIN)` — new).

When both hold it creates one `User` with `role=ADMIN`, the email normalized
(lower-cased/trimmed), the password **BCrypt-hashed** via the shared
`PasswordEncoder`, `plan=TEAM`, `emailVerified=true`, and placeholder profile
fields (`fullName="Majstr Admin"`, `company="Majstr"`, `phone="-"`,
`trades={GENERAL}`) — it's a panel account, not a contractor.

## Idempotency & safety

- **Repeat start never duplicates:** once any `ADMIN` exists, `existsByRole`
  short-circuits → no-op. So leaving the env vars set across restarts is safe.
- **Env taken by a non-admin:** if `ADMIN_EMAIL` already belongs to a USER, it
  logs a warning and skips (doesn't hijack the account or trip the unique
  constraint).
- **Multi-node race:** a `DataIntegrityViolationException` on save (two nodes
  inserting at once) is caught and logged as "created concurrently" — startup
  doesn't crash.
- **No secrets in logs:** only a fixed message is logged on creation; the
  password is never logged (and `app.admin.password` isn't exposed — actuator
  shows only `health`, and Spring masks `password`-named config anyway).
- **Env only:** email/password have blank defaults in `application.yml`; nothing
  is hard-coded.

## Profile interaction

Not `@Profile`-gated, unlike `DevDataSeeder` (`@Profile("dev")`). In dev the env
vars are blank → `AdminSeeder` no-ops, while `DevDataSeeder` still seeds
`admin@majstr.dev`. If both somehow run with env set, whichever creates an admin
first makes the other skip (idempotent) — order doesn't matter.

## Files

- `bootstrap/AdminSeeder.java` (new) — the runner.
- `config/AdminSeedProperties.java` (new) — `app.admin.{email,password}` +
  `isConfigured()`; registered in `MajstrApplication`.
- `repository/UserRepository.java` — added `existsByRole(Role)`.
- `application.yml` — `app.admin.email/password` (`ADMIN_EMAIL`/`ADMIN_PASSWORD`,
  blank). `.env.example` — documented blanks.

## Tests

`AdminSeederTest` (pure Mockito): clean DB + env → creates an `ADMIN` with a
BCrypt hash (not the raw password), normalized email, `emailVerified=true`,
`plan=TEAM`; blank env → no DB/encoder interaction; existing admin → no save;
env email taken by a non-admin → no save.

## Build-verification note

Gradle can't run in the agent sandbox. Run `./gradlew build` locally — no new
dependency, no migration. Manual smoke: on a clean DB set `ADMIN_EMAIL` +
`ADMIN_PASSWORD`, start → one ADMIN created (log line, no password); restart →
"an ADMIN already exists — skipping"; unset env on a clean DB → nothing created.
