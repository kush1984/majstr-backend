# P1 before the 11 November meetup (backend + PWA 1.56.2)

**Source:** `C:\Work\prompts\fix-p1-prompt.md`, from `review-golden-path-2026-10.md`. Fixes only — no new
screens. Mostly PWA; the backend part is the «mark sent» door (item 9), the email-verify and
validation-text fixes (items 7, 16), and a flaky test. Unfinished items are in `open-questions.md`,
marked «після мітапу».

## A. Back and drafts

1. **System «Назад» closes the sheet, not the screen** — `Modal` pushes one history entry per open
   modal (`lib/modalHistory.ts`); a `popstate` that leaves the TOP modal's entry closes only that one,
   so nested sheets close one at a time. The pushed state COPIES `history.state` (react-router's
   `usr`/`key`/`idx`), so the router sees the same location and its leave guard (a pathname compare)
   is never tripped. A popstate is read off where it LANDED — no «pops to ignore» counters, which a
   quick open-after-close raced past. Closing from the UI takes its entry back out (`history.go(-n)`),
   only for entries above every still-open modal and only while the browser sits on ours: a save that
   navigates from inside a sheet has pushed past it (one dead Back is left — open question).
   Once a FORM field in the sheet is typed into (`isFormEdit`: not search boxes, ticks, radios or
   files), a backdrop tap or «Назад» asks «Скасувати зміни?»; ✕ and the sheet's own buttons ask nothing.
   The act editor's «←» fallback now reads the router's `idx`, not `history.length`.
2. **Form drafts** — new object, new estimate and registration keep their fields in `sessionStorage`
   (`lib/sessionDraft.ts`) until success. Registration never stores the password or the consent tick.

## B. The path to the first estimate

3. **New estimate** — «Обʼєкт» first; the client is a folded row «Клієнт — необовʼязково».
   **Address stays required** (owner's call): the relaxation is an open question.
4. **One action name** — «Новий кошторис» everywhere; the template sheet says «Обрати (N)», the tile
   «Шаблонів: N»; «в потоці» is gone from the empty list.
5. **A DRAFT summing to 0 ₴** shows «Заповніть кількість у N позиціях» with a jump to the first such
   row, instead of «Кошторис готовий?».
6. **Registration** — «Назва компанії» copies the full name until the master edits it, with the hint
   «Можна змінити пізніше в профілі»; trades in two columns. Phone and company stay required
   (open question).
7. **Email verify** — a page opened without a session (the mail app's browser) says «Пошту
   підтверджено ✓ Поверніться в застосунок Majstr» and stays; a used or expired token of an
   ALREADY verified user is a success (`EmailVerificationService.verify`).

## C. Sending to the client, and the status

8. **«Поділитися» on a phone** (`pointer: coarse` + `navigator.share`) leads with the system share
   (Viber / Telegram / SMS) in the estimate, object and act sheets; «Копіювати посилання» stays as the
   fallback. Desktop is unchanged.
9. **The sheet no longer changes the status by opening.** Minting the `?t=` link
   (`ShareLinkService.create`) and publishing the object portal (`ProjectPortalService.update`) change
   no status; DRAFT → SENT happens only when the link LEAVES the app:
   - `POST /api/estimates/{id}/share/mark-sent` and `POST /api/projects/{projectId}/portal/mark-sent`
     (204, idempotent, owner-scoped — both in `IdorMatrixIntegrationTest`), called after a completed
     system share (an `AbortError` marks nothing) or a successful copy; offline (or on a blip) the call
     is queued in the outbox as `shareMarkSent` and coalesces;
   - email still marks by itself (the mail IS the link leaving).
   - PostHog `estimate_shared` / `act_shared` fire at the same moment, with `channel: 'share'` for the
     system share. **Released with PWA 1.56.2 on 2026-10-18**: from that date the funnel's «поділився»
     counts links that actually left, so the figure will likely DROP — that is honesty, not a regression.
   - A failed mint/publish offers «Спробувати ще раз» instead of an endless spinner (the act sheet no
     longer closes itself on a publish error); in object mode the URL field appears when the clipboard
     refused or the share was blocked.
   - Checked: the editor's «Підписано» polling, the badges and the «Очікує» filters all read
     `status === 'SENT'`, which the mark sets; the mark invalidates projects / project-estimates /
     estimate / dashboard.
   - **Acts are unchanged**: publishing an act still flips it to SENT on open (open question).
10. **The object screen polls** (`anyAwaitingClientInterval`) its estimates and acts while any one is
    SENT, and stops when none is.
11. **«Зберегти PDF»** in the next-step block has a loading state and refuses a second tap.
12. **PDF on iPhone** — `reservePdfTab()` is called on the handler's FIRST line, before any await (the
    email gate, the outbox check, the act save). In Safari it reserves a blank tab and fills it; in the
    INSTALLED app on iOS a blob window comes up blank, so the PDF is handed over as an `<a download>`
    (iOS shows it in its own viewer, with Share). Off iOS nothing is reserved. Not verified on a device.

## D. Acts and money

13. A payment or expense entered on the object invalidates «Мої гроші» and the home strip (`['cash']`).
14. A load error on the acts list or the economy is an `ErrorState`, not «Актів ще немає»; «+ Новий
    акт» is disabled offline with «Новий акт потребує звʼязку».
15. **«Зберегти» on the act** is a bar pinned to the bottom with its spinner; the FAB keeps Sign /
    Share / PDF / Delete and no longer carries Save.

## E. Error texts

16. No technical text reaches a person: fetch errors (`Failed to fetch` / `Load failed`) map to
    `NETWORK`; the raw `fetch` APIs throw `HttpStatusError` built from the JSON body; a backend field
    error is said in words — `GlobalExceptionHandler.handleValidation` names the field through
    `field.<leaf>` keys («Перевірте поле «Імʼя».»), the raw detail goes to the debug log.

## Also

- `PortalRateLimiterTest` (CI flake after P0): with greedy refill the exact count of allowed requests
  depends on the clock, so it asserts a range and that a refusal came.

## Not done (P2, optional)

None of the P2 list was started — see open questions «після мітапу».
