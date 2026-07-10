# Iteration: confirmation dialogs for every destructive / logout action

Follow-up to the estimate-editor redesign: the user asked that **everywhere we
delete something or log out** there be a "are you sure?" confirmation. Audited the
PWA and closed the two gaps that had none.

- **Status:** ✅ PWA-only. Gate green (tsc + vitest 80/80 + vite build). PWA `0.5.2 → 0.5.3`.
- **Migrations:** none.

## Audit result

Already guarded (no change): estimate delete, estimate line-item delete (edit
form), catalog-item delete (`CatalogItemForm`), object-expense delete
(`ObjectEconomySection`), estimate-template delete + project-detail estimate
delete/reopen (`TemplatesPage`, `ProjectDetailPage`). Not wired to any UI:
`useDeleteProject`, client delete — nothing to guard.

Gaps fixed:
1. **Logout** (`ProfilePage`) — the "Вийти" button called `logout` immediately.
   Now opens a `ConfirmDialog` ("Вийти з акаунта на цьому пристрої?"); logout runs
   only on confirm.
2. **Remove template item** (`TemplatesPage` → `EditModal`) — the 🗑 on a template
   position deleted instantly. Now opens a `ConfirmDialog`
   ("Прибрати позицію? Позицію «…» буде прибрано з шаблону.") before removing.

## Notes

- Both reuse the shared `ConfirmDialog`; the template-item confirm stacks over the
  edit modal (same pattern as the estimate line-item delete — Cancel returns to the
  editor unchanged).
- i18n uk + en: `profile.logoutConfirm`, `templates.removeItemTitle`,
  `templates.removeItemConfirm`.
