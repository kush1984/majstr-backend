# Iteration: Hide Прибуток/Витрати (parked) + rewrite the FREE teaser

- **Status:** DONE
- **Prompt:** `economy-hide-internals-prompt.md` — two small PWA-only fixes after a live trial.
  Backend untouched by design (code/endpoints/data for object-economy internals stay live).
- **Version:** majstr-pwa 1.13.1 → 1.13.2 (patch — UI-only hide + copy change, no new capability).

## Why

After a real trial, `Прибуток = contracted − Σ all expenses` (the economy-rework formula) reads
to a master as "what I earned" but isn't honest yet — it never accounts for what he actually pays
a crew unless he remembers to log that as a LABOR expense. Rather than ship a number that misleads,
the block is parked (same playbook as `REOPEN_ENABLED` / the old `UNFORESEEN_EXPENSES_ENABLED`)
until there's a clearer model to show, decided with the master.

## What changed

**1) Прибуток/Витрати + expense journal hidden (PWA only).** `ObjectEconomySection.tsx` gained
`const INTERNALS_ENABLED = false` (module-level, same pattern as `EstimateEditorPage.tsx`'s
`REOPEN_ENABLED`). The profit/expenses card and the expense-journal list are now wrapped in
`INTERNALS_ENABLED && (...)`, sitting below the (still-visible) Σ summary panel and payment
schedule. **Decision on the recon question ("keep fetching internals, or stop?"):** `internals`
itself keeps arriving on `ObjectEconomyResponse` (same endpoint as `estimates`/`payments` — can't
selectively drop one field of one response) and is harmless unused data; but `useExpenses`, a
**separate** endpoint (`GET .../expenses`), is now called with `isPro && INTERNALS_ENABLED` instead
of just `isPro` — no point fetching a journal nobody sees. Backend is completely untouched: the
expense-journal CRUD and `ObjectEconomyResponse.internals` stay fully live and reachable by direct
call; only this component stopped rendering/requesting them.

**2) FREE lock teaser rewritten.** The old text ("Бачити, скільки реально заробляєш на кожному
об'єкті — у PRO") described earnings specifically — but earnings are now the ONE thing NOT behind
this lock (it's parked). What's actually behind the lock after hiding internals is the Σ summary
panel + payment schedule, so the teaser now says "Платіжний графік і зведення по об'єкту — у PRO"
(en: "Payment schedule and object summary — in PRO"). CTA unchanged («Відкрити PRO →»).

## Tests

`ObjectEconomySection.test.tsx` — the PRO test rewritten: no longer expects «Заробіток»/«Витрати»/
the expense journal to render, and asserts `economyApi.listExpenses` is never called. The FREE
test's teaser assertion (`/у PRO/`) needed no change — the new copy still ends in «— у PRO». Full
PWA gate green (lint, `tsc -b`, `typecheck:tests`, vitest) — 540/540, same count as before (one test
rewritten in place, none added/removed). No live browser click-through this session (no test
account available) — same honest caveat as the economy-rework iteration; flagged rather than
assumed fine, per the mobile-first rule.

## Not changed (confirmed)

- Backend: `ObjectExpenseService`/`ObjectExpenseController` (CRUD), `ObjectEconomyInternalsResponse`,
  `ObjectEconomyResponse.internals` — all unchanged and still return real data on direct call.
- FREE gate shape (acts always visible; summary+payments+internals still computed together and
  nulled together for FREE) — economy-polish's gate is untouched, this iteration only hides a
  slice of what PRO already unlocks.
- Portal/PDF — this iteration is the owner-side Економіка tab only.
