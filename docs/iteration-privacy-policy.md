# Privacy policy + user consent

- **Status:** ✅ Backend code + tests done, V32 drill PASSED. PWA done (tsc + vitest
  **56** + vite build green). Backend `./gradlew build` pending at the gate. Two
  commits (backend + PWA).
- **Migration:** `V32__add_consent_timestamps.sql`.
- **Goal:** Majstr stores real personal data — the master's and their clients'. Add a
  public privacy policy, explicit registration consent, a master acknowledgement for
  entering client data (controller/operator distinction), and a portal note.

## Decisions (this iteration)

- Consent endpoints live in `ProfileController` (`POST /api/profile/consent`,
  `POST /api/profile/acknowledge-client-data`).
- Registration enforces consent at the API too (`@AssertTrue` on `RegisterRequest`),
  not just the PWA checkbox.
- **Existing users** (registered before the checkbox): a one-time **login** consent
  modal (their `consentedToPrivacyAt` is NULL) — not "continued use = consent".

## Backend

- **V32**: `users` + `consented_to_privacy_at`, `acknowledged_client_data_at` (both
  nullable TIMESTAMPTZ). Existing rows NULL → PWA prompts them.
- `User` + the two `Instant` fields; `UserResponse` exposes both (drives the PWA modals).
- `RegisterRequest` + `@AssertTrue boolean consent`; `AuthService.register` stamps
  `consentedToPrivacyAt = now`.
- `ProfileService.recordPrivacyConsent` / `acknowledgeClientData` (idempotent — a prior
  stamp is kept) + the two `ProfileController` POSTs.
- **Portal note (Part 4):** a muted footer in `static/portal/index.html` (vanilla) —
  "цей кошторис надав вам майстер … технічний сервіс Majstr …". No portal logic touched.
- `/privacy` needs **no** Spring Security whitelist — it's a PWA SPA route, never hits
  the backend.

## PWA

- **Part 1 — /privacy page:** public route (like `verifyEmail`), `PrivacyPage` with the
  policy verbatim (inline uk — content, like the PDF/email/portal; en is a TODO).
  Footer link on the landing; added to `sitemap.xml` (robots already allows it).
- **Part 2 — registration consent:** `registerSchema` + required `consent` boolean
  (submit blocked until ticked); `RegisterPage` checkbox with a `/privacy` link (new tab);
  payload carries `consent: true`.
- **Part 2.5 — existing-user login modal:** `PrivacyConsentModal` (non-dismissable
  `Modal` variant) rendered in `AppLayout` when `me.consentedToPrivacyAt == null` →
  `POST /api/profile/consent`.
- **Part 3 — client-data acknowledgement:** `ClientDataAckModal` gated in `ClientPicker`
  — switching to the "Новий" client tab when `me.acknowledgedClientDataAt == null` opens
  it; confirm → `POST /api/profile/acknowledge-client-data` → proceeds. Shown once.
- `Modal` gained `dismissable?: boolean` (hide ✕, ignore backdrop/Escape) for the
  required consent modal.

## Tests

- Backend: `AuthControllerTest` (register without consent → 400), `AuthServiceTest`
  (register stamps `consentedToPrivacyAt`), `ProfileServiceTest` (consent /
  acknowledge stamp + idempotent). DTO fan-out fixed (`RegisterRequest`+1,
  `UserResponse`+2 in the affected tests).
- PWA: `RegisterPage.test` (consent blocks/permits submit, `consent:true` sent),
  `ClientPicker.test` (ack gate: modal on first "Новий", confirm proceeds + stamps;
  already-acked skips).
- V32 drill PASSED (prod backup → V32, invariants hold).

## Verify (live)

1. Guest opens `/privacy` (no login) → full policy; footer link present; in sitemap.
2. Register: unticked consent → submit blocked; ticked → registers; link opens /privacy.
3. Existing user logs in → consent modal → agree → gone (stays gone).
4. First "Новий" client → acknowledgement modal → confirm → proceeds; not shown again.
5. Client portal (by token) → footer note C; signing/viewing unaffected.

## Open questions added

- Lawyer review of the policy (esp. vs law №8153) before it carries legal weight.
- English translation of the policy texts (currently uk, en is a placeholder).
