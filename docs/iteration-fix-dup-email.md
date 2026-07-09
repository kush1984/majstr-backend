# Fix: clean 409 on duplicate-email registration (Sentry JAVA-SPRING-BOOT-C)

- **Status:** 🔨 Backend code complete; `./gradlew build` pending at the gate. **No
  migration.** PWA verified: **tsc + vitest(74) + vite build green.** Closes Sentry issue
  JAVA-SPRING-BOOT-C (commit message carries `Fixes JAVA-SPRING-BOOT-C`).
- **Symptom:** registering with an email that races past the pre-check hit the DB unique
  constraint (`users_email_unique`) → an unhandled `DataIntegrityViolationException` → **500
  + Sentry noise** instead of a clean 409. The DB did its job; the reaction was wrong.

## Recon (what already existed)

- **Level 1 (pre-check) already present:** `AuthService.register` calls
  `existsByEmailIgnoreCase` and throws `EmailAlreadyExistsException` (→ 409). The common
  case was already clean.
- **Normalization already consistent:** register stores `email.toLowerCase().trim()`; login
  uses `findByEmailIgnoreCase(email.trim())`. Both case-insensitive → `Bogdan@` and `bogdan@`
  are one account. The `users_email_unique` constraint is on the always-normalized column, so
  **no functional index / migration needed** (nothing to reconcile).
- **The real gap = Level 2:** no `DataIntegrityViolationException` handler → the race fell to
  the 500 fallback.

## Fix (backend, `GlobalExceptionHandler`)

- Added an `@ExceptionHandler(DataIntegrityViolationException.class)` that maps **only** the
  `users_email_unique` violation to the same clean **409** — walking the cause chain for a
  Hibernate `ConstraintViolationException` with that constraint name (plus a defensive
  message check). **Every other integrity violation is re-routed to the existing 500 + Sentry
  path** (`handleAny`) — no other constraint is silently swallowed.
- Gave both dup-email paths (the pre-check exception and the constraint catch) a
  machine-readable code **`EMAIL_ALREADY_REGISTERED`** on the 409 `ErrorResponse`, so the PWA
  can branch precisely. Message reuses the existing `error.email-taken` bundle key (no
  email/DB detail leaked).
- Net: both the fast path (pre-check) and the race (constraint) now return the same coded
  409; neither reaches Sentry as an unhandled 500.

## PWA

- `RegisterPage`: on `code === EMAIL_ALREADY_REGISTERED` (or 409) it already showed a field
  error; now it also renders a **"Ця пошта вже зареєстрована — Увійти"** shortcut that
  navigates to `/login` with the email **prefilled** (via router state). `LoginPage` reads
  `location.state.email` into the form default. i18n uk + en. App version → **0.4.1**.

## Not broken

- Successful new-user registration, login, email verification, `RegisterRateLimitFilter`,
  the `@Transactional` register boundary (pre-check runs in the same tx). Other
  `DataIntegrityViolation` cases keep their 500 + Sentry behavior.

## Tests

- `AuthControllerTest` (standalone MockMvc through the real advice): dup-email pre-check →
  409 + `code: EMAIL_ALREADY_REGISTERED`; email-constraint race
  (`DataIntegrityViolationException` wrapping a `users_email_unique`
  `ConstraintViolationException`) → 409 + same code; a **different** constraint → still 500
  (not swallowed). PWA `RegisterPage.test`: 409 shows the "log in" shortcut and navigates with
  the email prefilled.

## Verify (after backend build green)

1. Register with an existing email → 409 "вже зареєстрована", not 500; Sentry silent.
2. Same email in a different case (`Bogdan@` vs `bogdan@`) → also 409.
3. A brand-new email → registers as before; existing users still log in.
4. Simulated race (two parallel registers, same email) → one succeeds, the other 409, no 500.
