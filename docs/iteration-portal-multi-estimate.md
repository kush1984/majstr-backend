# Iteration: project-level portal (multi-estimate) + stale-name cache fix

- **Status:** 🔨 code complete; backend build on the user
- **Migration:** **V62** — `project_share_links` + `estimates.portal_visible`
- **PWA:** 0.25.0

## The bug that started it

An object had two estimates, but the client only ever received **one** — the object's
share CTA silently targeted the NEWEST estimate. The master expected to choose.

## Model (decided with the user — "варіант 3, зробити правильно")

**One portal per object, one link, master-curated content:**

- `project_share_links` — an object-level token, minted **idempotently** (one live URL per
  object, reused on every publish). URL form: `/portal/index.html?p=<token>`.
- `estimates.portal_visible` — the master's explicit per-estimate flag. **Nothing is shared
  by default**; the share sheet is a checkbox list and copy/email always `PUT`s the ticked
  set first, so the URL matches what was just chosen.
- The portal page renders a **section per visible estimate** (name band, items, totals,
  deposit/balance, terms) — each section signs separately, asks its own questions, and has
  its own PDF link.
- Questions were already estimate-linked in the DB (`estimate_questions.estimate_id`); the
  portal now files them under the right estimate, `QuestionView` gained `estimateName`, and
  the app inbox shows «щодо “Економ”» next to the author. Push bodies carry «Назва»: prefix.
- Photos stay **per-object** (one gallery under the sections) — deliberate, per the user.

## Legacy links keep working

Old per-estimate URLs (`?t=`) were already in clients' inboxes — they resolve through the
untouched `/api/public/estimates/{token}` endpoints. The page has **one render path**: the
legacy response is adapted client-side into the portal shape (a 1-section portal). V62 also
seeds `portal_visible = TRUE` for estimates that had a live per-estimate link, so switching
models doesn't empty anyone's portal. The PWA no longer mints estimate links (`estimatesApi`
share methods removed); `ShareLinkService` stays server-side for the legacy endpoints.

## Backend surface

Owner (`ProjectPortalController`, auth):
- `GET  /api/projects/{id}/portal` — `{url|null, estimates: [{id, name, status, createdAt, visible}]}`
- `PUT  /api/projects/{id}/portal` `{estimateIds}` — sets the exact visible set (foreign id
  → 404, nothing half-applied), flips newly-visible DRAFTs → SENT, mints/reuses the link.
  Same `CLIENT_PORTAL` + verified-email gate as the legacy share.
- `POST /api/projects/{id}/portal/send-email` — portal URL via the existing share email
  (400 `CLIENT_EMAIL_MISSING` without a client email).

Public (`PublicPortalController`, `/api/public/portal/{token}`, covered by the existing
`/api/public/` rate-limit filter):
- `GET /{token}` — `PublicPortalView` (contractor, project, sections, shared photos)
- `POST /{token}/estimates/{id}/sign` · `POST .../question` · `GET .../pdf`
- `GET /{token}/photos/{photoId}/file` — SHARED-only, same as legacy

Scoping rule: a portal-addressed estimate must belong to the token's project **and** be
`portal_visible` — every failure mode is the same neutral 404.

`PublicEstimateService` was refactored so both token families share one core (`doSign`,
`doAsk`, `totalsOf`/`sectionOf`) — sign semantics (409 on signed, project → IN_PROGRESS,
push, `@Version` guard) exist once.

## PWA

- `SharePortalSheet` (replaces `ShareEstimateSheet`, both entry points): checkbox list
  seeded from server state (`estimateName` fallback naming, ✓ signed chip), copy/email
  publish-first; editor entry pre-ticks its own estimate. Unticking everything offers
  «Прибрати все з порталу» (PUT []). Client-attach / add-email / verify-bounce logic kept.
- **Stale-name fix:** `useInvalidateEstimate` didn't invalidate `['project-estimates']`, so
  a rename never reached the object screen without a manual refresh. One-line fix + test.

## Tests

- `ProjectPortalServiceTest` — exact-set semantics, DRAFT→SENT flip, idempotent link,
  foreign-id rejection, email gates, null-url state.
- `PublicEstimateServiceTest` — portal sections, hidden/foreign estimate rejected on sign,
  portal sign happy path, question push prefixed with the estimate name (+ constructor
  fan-out for the two new repos).
- PWA: `SharePortalSheet.test` (seeding, publish-exact-set-then-copy, editor pre-tick,
  hide-all), `useEstimate.test` rename-invalidation regression.
- Portal page verified in-browser against fixtures: portal mode (2 sections, per-section
  dialogs/PDF/sign-disabled-when-signed) and legacy mode (1 section, legacy endpoints),
  both at 375px with zero horizontal overflow.

## Gotchas

- The portal link dies only by revocation/expiry — unticking an estimate hides its section
  but keeps the URL alive (it may show fewer sections than before; that's the master's
  explicit action).
- `PortalStateResponse.estimates` carries `createdAt` so the PWA names unnamed estimates
  with the same `estimateName()` helper as everywhere else («Кошторис від 1 липня»).
- `EstimateQuestionRepository.findByEstimateProjectId...` now `JOIN FETCH`es the estimate —
  `QuestionView.from` reads its name, which would otherwise N+1.
