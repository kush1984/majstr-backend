# Object-first: one way to start (PWA 1.57.0, with objects-list)

**Source:** `C:\Work\prompts\object-first-prompt.md` and `objects-list-prompt.md` (2026-10-19). PWA
only — no new entities, no backend change. The meetup demo (11.11) shows exactly this path.

## The rule

**One creation action — «Новий об'єкт».** An object is the container (name, address, client);
estimates, measurements, photos, acts and payments are added INSIDE it. The **FAB does the main
action of the screen it stands on** — one action, no menu. Both rules are in the PWA's `CLAUDE.md`.

## What it replaces

- **P1 §3** (field order of the new-estimate form) and **§4** (the template sheet's buttons): the
  combined form is gone, so its «Обрати (N)» / «Шаблонів: N» went with it; the sheet on the object
  says «Створити кошторис (N)», because there a pick DOES create.
- **objects-list §2** (the «＋» on «Об'єкти» = «Новий кошторис»): it is «Новий об'єкт» now. The rest
  of objects-list stands — the «Архів · N» chip, a dead-end-free empty list («Активних об'єктів
  немає» + «Показати архів (N)»), and the 409 `PROJECT_HAS_SIGNED_MONEY` explained in a sheet with
  one «Зрозуміло» instead of a red toast about «закриті місяці».
- **P0 §4** (a retry made a second object): the combined form and its `created` ref are deleted, so
  the bug has nowhere to live. `NewObjectPage` keeps its own «reuse the client an earlier attempt
  made» guard. The dead `TemplatePickerSheet.confirmLabel` prop and its locale keys are gone too.

## The path

1. **«Новий об'єкт»** (`/projects/new`; `/new` redirects there) — name\*, address\* (still required:
   backend unchanged), «Клієнт — необов'язково» as a folded row; no estimate name, no estimate kind.
   Draft in `sessionStorage`. «Створити об'єкт».
2. The object opens with **«Об'єкт створено. Що далі?»** once — the flag rides the navigation state
   (`justCreated`) and is cleared on arrival, so a reload or a later visit does not reopen it:
   «📋 Кошторис із шаблону» (leads), «Порожній кошторис», «З файлу або фото», «Пізніше». The same
   sheet, without «створено», is what the estimates tab's «Створити кошторис» (empty), the full-width
   «＋ Новий кошторис» under the list, and the tab's «＋» open. Offline: the object is queued, the sheet
   works the same, and an uncached template says `offline.templateNotCached`.
3. Taps to an estimate from a template: 6 before (Новий кошторис → name → address → «З шаблону» tile
   → pick → Створити), 6 now (Новий об'єкт → name → address → Створити об'єкт → Кошторис із шаблону
   → pick + «Створити кошторис (N)»).

## The object page

- **Header ⋮** carries what the FAB menu held: «Поділитися з клієнтом» and «Посилання на чат»
  (through `guard`, hidden on a closed object), «Нотатки» with the count (readable on a closed object),
  and the status moves (`ObjectStatusMenuItems`, the list row's own).
- **«＋» per tab**, each rendered by the tab's own section: Кошториси → «Що далі?»; Заміри → the room
  dialog; Фото → inside a folder, that folder's own gallery input (a «Чеки» upload stays a RECEIPT),
  on the folder list → into «Інше» with a toast «Додано в «Інше»» + «Перемістити в папку»;
  Економіка → «Новий платіж» (it lives with the PRO payments card, so FREE has no «＋» there); Акти →
  «Новий акт», refused with the button's own reason (open act / final act / offline).

## Entry points

«Об'єкти»: one «Новий об'єкт» (header, under the list, empty states, «＋»). Головна: the header CTA,
the mobile CTA, the empty state («Створіть перший об'єкт») and the quick action. The landing and SEO
copy were left alone (they already say «Створи об'єкт»). PostHog `project_created` /
`estimate_created` fire in the hooks, so their names did not change.

## Not verified here

- `npm run test:e2e:offline`: the scenarios were rewritten for the new path (and the `e2e/` journey
  and smoke specs), but the local backend has no CORS for :4173, so they were not run.
- The manual pass at 375 × 812.
