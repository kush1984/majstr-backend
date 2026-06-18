# Estimate UX — signed-lock UI, name, delete, reopen

- **Status:** ✅ Backend + PWA complete. Backend `./gradlew build` pending a local
  run; PWA verified (vitest 31 green, `tsc -b` clean).
- **Migration:** V25 (`estimates.name`, `reopened_at`, `reopened_by`).
- **Goal:** Four linked estimate-state improvements found in E2E testing.

## State machine (the agreed model)

- **DRAFT** — free to edit, rename, delete.
- **SENT** — sent to client, still editable/deletable (not yet signed).
- **SIGNED** — view-only: no edits (409 `ESTIMATE_SIGNED`), **no delete**, edit/
  delete UI hidden. The only forward action is **reopen** (owner) → back to DRAFT.

## 1. Signed estimate — UI edit-blocking

The backend 409 guards already existed. Added UI **prevention** so the user
never walks into a wall: when `status === SIGNED` the editor shows a banner
"✓ Підписано клієнтом — лише перегляд" with the reopen button, the "add item"
buttons are hidden/disabled, and item rows are non-clickable (tooltip explains).

## 2. Estimate name

- **V25** `estimates.name VARCHAR(255)` (nullable). Added to
  `EstimateCreateRequest`/`EstimateUpdateRequest`/`EstimateResponse`/`EstimateSummary`.
- Set on create and editable via `PUT` while not signed.
- PWA: optional "Назва кошторису" field on the create screen, and a rename action
  in the editor's ⋮ menu. Lists show `estimateName(name, createdAt)` — the label,
  or a dated default ("Кошторис від …") so unnamed variants stay distinguishable.

## 3. Delete + empty drafts

- `DELETE /api/estimates/{id}` now **rejects SIGNED** (409 `ESTIMATE_SIGNED`) —
  signed estimates are legally significant. DRAFT/SENT delete freely.
- PWA: ⋮ → "Видалити кошторис" → **in-app** `ConfirmDialog` ("Цю дію не можна
  скасувати"), then navigate back to the project. The ⋮ menu is hidden entirely
  for SIGNED (no delete option shown, not just disabled).
- **Empty-draft choice:** kept "create on +New" + manual delete (the simpler,
  reliable option) rather than deferring creation until the first item — deferral
  would complicate the whole add-item/autosave flow for a minor cleanup; a stray
  empty draft is now one tap to delete and counts toward the FREE cap anyway.

## 4. Reopen (owner-only)

- `POST /api/estimates/{id}/reopen` (`EstimateService.reopen`) — **owner only**
  (`loadOwned`); the public `PublicEstimateService` has no reopen path, so the
  client portal cannot trigger it. Only valid on SIGNED (else 400
  `error.estimate.not-signed-reopen`).
- Effect: status → DRAFT, signature fields cleared (`signedAt`/`signerName`/
  `signerPhone`/`signerIp` = null), `reopenedAt`/`reopenedBy` stamped (V25 audit).
  The **project status is left as-is** (work already started — only the estimate
  becomes editable again). The client must **sign again** (transparency).
- PWA: "Переоткрити для змін" in the SIGNED banner → `ConfirmDialog` ("Його треба
  буде підписати заново клієнтом") → owner edits → share → client re-signs.

## Tests

- Backend: `EstimateServiceTest` — delete removes DRAFT / rejects SIGNED (no
  delete); reopen SIGNED→DRAFT + clears signature + audit stamp; reopen rejects
  non-signed (400) and other-user (AccessDenied); create stores trimmed name.
  Existing constructor calls updated for the new record fields.
- PWA: `estimateName.test.ts` (label vs dated default). Full suite 31 green,
  `tsc -b` clean.

## Not changed / follow-up (open-questions)

- "What changed since last signature" highlighting — **future**, important for
  trust (client currently re-approves the actual current estimate).
- Full signed-version history (audit of every signed version) — **future**;
  ties into the existing `Estimate versioning / history` item.

## Verify

1. SIGNED → "add item" disabled + tooltip + "view only" banner.
2. Two estimates named "Економ"/"Преміум" → both visible in the project list.
3. DRAFT ⋮ → Delete → confirm → gone. SIGNED → ⋮ absent (no delete).
4. SIGNED → owner Reopen → DRAFT → add item → share → client re-signs → SIGNED.
   Portal has no reopen. Project not broken.
5. Sentry clean.
