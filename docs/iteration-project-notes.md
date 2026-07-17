# Iteration: object notes (Нотатки tab, replacing the «Зміни» placeholder)

A master keeps object-scoped things handy — a subcontractor's contact, access conditions,
agreements ("keys are with the concierge", "shut the riser before 9:00"). A note is **free
text + an optional title + an optional phone**; a phone turns into a one-tap `tel:` call. We
swapped an unvalidated assumption («Зміни» — a placeholder from the original vision that no
master ever asked for) for a voiced need, freeing only the tab slot.

- **Status:** 🔨 Code complete — PWA green (tsc / 131 tests / build); backend build on the user.
- **App version:** PWA `0.12.0 → 0.13.0` (new migration-backed feature → minor).
- **Migration:** **V58** (`project_note`).
- **No PRO gate** (a retention utility, not monetization); **no per-object count limit** (it's
  text, not files) — only field-length caps.

## Recon (confirmed)
- Latest Flyway = V57 → **V58**. Object = `Project` (`projects(id)`); TIMESTAMPTZ + FK
  `ON DELETE CASCADE`, `idx_<table>_project` — the `project_photo`/`measurement_room` convention.
- «Зміни» tab: `ProjectDetailPage` `Tab = '…|changes|act'`, rendered as the `🚧 soon` EmptyState.
  **No `Feature.CHANGE_ORDER` exists** — so nothing to keep/avoid; notes are simply ungated.
- Owner-scope pattern = `ObjectExpenseService` (`projectService.loadOwned` → 404/403,
  `findByIdAndObjectId` for edit/delete, plain `objectId`) — mirrored **without** the FeatureGuard.
- **Privacy:** the portal view (`PublicEstimateView`) is built in `PublicEstimateService` and
  carries the estimate's own client-facing `notes` **String** — a different thing from object notes.
  `ProjectNote` is a separate entity, never referenced there → structurally can't leak.

## Backend
- `V58__project_notes.sql` — `project_note {id, project_id FK cascade, title?, phone?, body NOT NULL,
  sort_order, created_at, updated_at}` + `idx_project_note_project`.
- `ProjectNote` entity (plain `projectId`, `@PrePersist`/`@PreUpdate` timestamps), `ProjectNoteRepository`
  (`findByProjectIdOrderByCreatedAtDesc`, `findByIdAndProjectId`).
- `NoteRequest` (only `body` `@NotBlank`, ≤2000; `title` ≤255, `phone` ≤40 — both optional),
  `NoteResponse`.
- `ProjectNoteService` — owner-scoped CRUD, **no plan gate**; trims body, blank title/phone → null,
  **phone kept verbatim** (not normalised — "067 123 45 67" and "+380…" both work for tel:).
- `ProjectNoteController` — `GET/POST/PATCH/DELETE /api/projects/{id}/notes[/{noteId}]`, mirrors the
  expense controller (POST → 201, DELETE → 204).

## PWA
- `NotesSection` (new `features/notes/`) — cards (bold title if present, `white-space: pre-wrap` body
  so line breaks survive, a `📞 <phone>` chip → `tel:` with spaces stripped), edit/delete (delete
  confirmed). Empty state **teaches by example** ("Архітектор Олег, 067…", "ключі в консьєржа").
- `NoteSheet` — add/edit bottom-sheet; the **only required field is the body**; title/phone optional,
  phone `inputMode="tel"`.
- `notesApi` + `useNotes`/`useNoteActions` (`['project-notes', objectId]`).
- `ProjectDetailPage`: `changes` tab → `notes` (renders `NotesSection`); `act` stays the placeholder.
- i18n: `tabChanges` → `tabNotes` + a `notes.*` block (uk + en).

## Privacy (critical) — verified
- Object notes are on their **own** endpoint/entity, never added to `PublicEstimateView` /
  `PublicEstimateService` / the PDF / any share-token response.
- **Test:** `PublicEstimateIsolationTest` gained a **type-based** guard — it walks the public DTO
  record tree and asserts no component TYPE name contains "note". The estimate's legitimate
  client-facing `notes` field is a `String` (type "String"), so it stays clear, while a leaked
  `ProjectNote`/`NoteResponse`/`NoteView` would fail the test.

## Tests
- Backend `ProjectNoteServiceTest` — trim/optional handling, phone verbatim, newest-first, owner
  guard runs before any read (foreign object → `AccessDeniedException`, no repo call), edit/delete,
  missing note → 404.
- Backend `PublicEstimateIsolationTest` — the new no-note-type assertion.
- PWA `NotesSection.test` — renders title + `tel:` chip (spaces stripped from the href), pre-wrap
  body, empty-state example, add-with-body-only (Save disabled until body).

## Not changed / confirmed
- Other object tabs (Кошториси / Фото / Заміри / Економіка) untouched — only the «Зміни» slot changed.
- Portal / PDF / share, FREE limits (notes don't touch them), owner isolation, object-delete cascade
  (DB FK) — all intact.
- No feature flag consumed; «Зміни» stays an idea in open-questions (may resurface via SIGNED re-sign).

## Gotchas
- **Naming collision:** `Estimate.notes` (client-facing, in the portal) ≠ object `ProjectNote`
  (private). The isolation test guards the boundary by TYPE, not by the field-name substring "note"
  (which would false-positive on the legitimate estimate notes).
- `tel:` strips spaces from the stored phone for the href but shows it verbatim to the master.
