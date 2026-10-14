# UX small fixes after the video review (PWA 1.52.2)

**Status:** done 2026-10-14, PWA only. **Source:** `C:\Work\prompts\ux-small-fixes-prompt.md`.

1. **Line sum in the item form** — `ItemForm` shows «Сума: …» under quantity × price, live, through
   `roundMoney` (the server's HALF_UP, 2 places). Empty or non-numeric field → empty line, never
   «0 ₴». The line always takes its height, so it cannot shove the buttons while the keypad is
   open. `PERCENT` lines keep their own preview.
2. **«Клієнт побачить зміну за тим самим посиланням»** — `noticeSentChange` in `useEstimate.ts`,
   read off the cached estimate's status, called from the `onSuccess` of all eight line hooks (add,
   from catalog, batch, update, reorder, remove, delete many, markup) and after the dictation and
   receipt commits. Once per estimate per session; SENT only; offline says «після синхронізації».
   Backend check: the portal already closes the dialog, alerts and `load()`s the estimate on 409
   `ESTIMATE_CHANGED` (both the estimate and the act paths) — the client is never stuck. No change.
3. **Empty «Повідомлення»** — explains where messages come from, plus a quiet «Отримати
   посилання на чат →» that opens `ChatLinkSheet` through the same `guard` as the FAB item; not
   offered on a closed object (the FAB hides it there too).
4. **Calculator: stale figures** — a card he has TYPED into since its last apply is «dirty» (our
   pre-filled suggestions are not): its button turns primary, a quiet line above the results says
   to recalculate, and «У список покупок» asks once («Додати як є?») instead of refusing.
