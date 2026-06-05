# Fix F — Client questions visible to the contractor + client editing

- **Status:** ✅ Backend code complete — `./gradlew build` + app restart (V22)
  pending a local run (Gradle isn't reachable from the agent's sandbox).
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** `V22__add_is_read_to_estimate_questions`
- **Goal:** close two gaps — the contractor never saw the questions clients leave
  on the portal (#12), and a client couldn't be edited after creation (#13).
  **Backend only** — the PWA question section, card indicator and client-edit
  modal are a separate task.

## #12 — Client questions the contractor can read

Read-only inbox: the contractor sees questions and marks them read, then follows
up via their own channel. No in-app reply thread.

- `EstimateQuestion.read` (column `is_read`, V22, default false). Existing rows
  default to unread — the contractor has never acknowledged them. A partial index
  `idx_estimate_questions_unread ON estimate_questions(estimate_id) WHERE is_read
  = FALSE` keeps unread counts cheap.
- `QuestionView` DTO (id, authorName, authorPhone, message, isRead, createdAt) —
  the contractor's richer view, distinct from the minimal `QuestionResponse`
  acknowledgement the client gets on submit.
- `QuestionService` (scoped to an owned project via `ProjectService.loadOwned` —
  404 unknown / 403 foreign):
  - `listForProject` — `findByEstimateProjectIdOrderByCreatedAtDesc`, newest first.
  - `markRead` — loads the question, asserts it belongs to the named project
    (else 404), sets `read = true`.
- `ProjectQuestionController` (`/api/projects/{projectId}/questions`):
  - `GET` — list (Bearer JWT, own project).
  - `PATCH /{questionId}/read` — mark read.
- `GET /api/projects` now returns `unreadQuestions` per project. Counted with a
  single grouped query `countUnreadByProjectIds` folded into the list (no N+1),
  mirroring the latest-estimate-summary aggregate. Single-project responses
  (`get`/`update`/`updateStatus`) use the derived `countByEstimateProjectIdAndReadFalse`.
  A freshly created project reports 0.
- `GET /api/dashboard/metrics` gains a global `unreadQuestions`
  (`countByEstimateProjectOwnerIdAndReadFalse`) for a home-screen badge.

## #13 — Client editing

Already shipped in step 2 and confirmed correct, not rebuilt:
- `PUT /api/clients/{id}` → `ClientService.update` sets fullName, phone, address,
  email (trimmed, blank → null) on the owned client. `@Valid ClientRequest`
  validates all fields (email format only when present). Ownership via
  `loadOwned` (404 unknown / 403 foreign). No code change needed; added tests.

## Not changed (confirmed)

- The public `POST /api/public/estimates/{token}/question` flow is untouched;
  new questions still default to unread and still fire the Fix-8 web push. The
  minimal `QuestionResponse` returned to the client is unchanged.
- `ClientService.update` / `ClientController` PUT were already present — only
  test coverage was added.

## Tests

- `QuestionServiceTest` — list maps to `QuestionView` for an owned project;
  foreign project propagates `AccessDenied` and reads nothing; `markRead` flips
  the flag and returns the view; a question under another project → 404, flag
  untouched.
- `ProjectServiceTest` — list folds in unread counts from the aggregate; absent
  project → 0. (Mock for the new repository added.)
- `DashboardServiceTest` — metrics include the global unread count.
- `ClientServiceTest` — `update` saves all fields; editing a foreign client →
  `AccessDenied`, original data untouched.

## Gotcha

The entity field is named `read` (not `isRead`) so the JPA attribute, JPQL path
(`q.read`) and Spring-Data derived queries (`...AndReadFalse`) all agree on one
property name. The `QuestionView` **record component** is named `isRead`, which
is the JSON key the PWA receives. Column stays `is_read`.

## Build-verification note

Gradle can't run in the agent sandbox (loopback socket blocked). Run
`./gradlew build` locally to confirm compile + tests. No new external
dependency this step, so the risk is lower than Fix 8.
