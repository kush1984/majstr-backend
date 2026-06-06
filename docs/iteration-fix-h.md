# Fix H — Editable contractor profile (conditional email)

- **Status:** ✅ Backend code complete — `./gradlew build` + restart pending a
  local run (Gradle isn't reachable from the agent's sandbox).
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** none (no schema change).
- **Goal:** the profile screen could show the contractor's data but not edit it.
  Add a profile update endpoint, with email editable **only while unverified**.
  **Backend only** — the PWA edit form / email field states are a separate task.

## Endpoint

`PUT /api/profile` (Bearer JWT) → `ProfileService.updateProfile` → `UserResponse`.
Ownership is implicit: the target user is the authenticated principal, so a
contractor can only edit their own profile. `ProfileUpdateRequest` is validated
like registration (`fullName`, `phone`, `companyName` non-blank; `trades`
non-empty; `email` optional, `@Email` when present).

## Fields

- `fullName`, `phone`, `companyName` — trimmed and saved.
- `trades` — the set is **replaced** (`setTrades(new LinkedHashSet<>(...))`).
  This never touches the contractor's catalog items, which are independent once
  seeded at registration — changing trades does not re-seed or delete catalog.

## Conditional email rule

Handled in `ProfileService.applyEmailChange`:

- Email omitted/blank, or equal to the current one → no-op.
- **Verified email is locked:** if `emailVerified == true` and a *different*
  email arrives, the change is **ignored** and the rest of the profile still
  saves (returns 200 with the unchanged email). Uniqueness isn't even checked.
  Chosen over a 409 so a stale/tampering client can't block legitimate edits;
  the PWA disables the field when verified anyway.
- **Unverified email is editable** (fix a registration typo): the new value is
  lowercased/trimmed, checked for uniqueness (`existsByEmailIgnoreCase` → 409
  `EmailAlreadyExistsException` if taken), set on the user (account stays
  unverified), and `EmailVerificationService.replaceForNewEmail` drops every
  pending token for the user (`EmailVerificationTokenRepository.deleteByUserId`,
  bulk `@Modifying` delete) and sends a fresh verification to the **new** address
  (Resend, async, fail-soft — same as register).

## Not changed (confirmed)

- Logo upload/delete, `/auth/me`, registration and the verification flow are
  untouched. `EmailVerificationService.issueAndSend` / `verify` / `resendFor`
  are reused as-is; only `replaceForNewEmail` was added.
- No migration: all fields already exist on `users`.

## Tests

- `ProfileServiceTest` — basic fields saved + trades replaced; an unverified
  email change normalizes, sets the new email and reissues verification; a taken
  email → `EmailAlreadyExistsException`, old email kept; a verified email change
  is ignored while the rest of the profile saves (uniqueness never checked).
- `EmailVerificationServiceTest` — `replaceForNewEmail` deletes the user's old
  tokens and sends a fresh verification.

## Build-verification note

Gradle can't run in the agent sandbox (loopback socket). Run `./gradlew build`
locally. No new dependency, no migration. Reminder from Fix G: when a `record`
gains/loses a component, update both `.from(...)` callers and direct
`new <Record>(...)` constructions (incl. tests) — `UserResponse` is unchanged
here, so no such fan-out this time.
