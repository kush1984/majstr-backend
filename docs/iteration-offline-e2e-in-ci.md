# Iteration: the offline e2e actually runs — and now runs in CI

A follow-up to the offline programme, triggered by the suite failing on the user's machine.
Two separate problems were hiding behind one error message, and the second one meant this
suite had **never** been able to pass for anyone.

- **Status:** ✅ verified — the CI-bound spec passes locally
- **Migration:** none
- **PWA:** 0.38.2

## 1. The visible problem: no browser

`npm run test:e2e:offline` died with Playwright's "Executable doesn't exist". Just
`npx playwright install chromium`. With that done, **`shell.spec.ts` passed** — which is the
real news: it is the service-worker / deep-route test, so the four offline steps shipped in
this programme did not break the app shell.

## 2. The hidden one: the journey test could never have worked

`journey.spec.ts` then failed after 2 minutes staring at a filled-in registration form. Not
the backend — it was up, and registering through `curl` returned 201.

The cause: this suite serves the **production build** from `vite preview` on **:4173**, while
the dev server uses :5173 — and a production build has no Vite proxy, so the app calls the API
on :8080 **cross-origin**. The backend allowed only :3000 and :5173, so the preflight from
:4173 came back **403**. Registration silently did nothing and the test timed out with no clue
why.

Fixed in three places, because one is not enough:
- `application.yml` ships `:4173` in the default allow-list (covers CI and a fresh clone).
- The local `.env` got the same origin appended — **an env override REPLACES the default list
  rather than extending it**, which is the trap that makes the config fix alone insufficient.
- `playwright.offline.config.ts` now spells out all three prerequisites in its header, so the
  next person meets the requirement instead of the symptom.

**The backend must be restarted to pick this up** — it reads `.env` at startup.

## 3. Why it rotted unnoticed: CI ran no e2e at all

Neither suite was in the workflow. The type gate was made real earlier in this sweep, but the
one class of bug that unit tests **structurally cannot see** — the service worker — was still
guarded by nothing but someone remembering to run it. That is precisely how "Ви не в мережі"
on a refreshed deep route reached a real master.

`shell.spec.ts` now runs in CI on every push: it needs no backend, only the production build
and a browser. A dedicated `test:e2e:offline:shell` script means CI and a human run the exact
same command.

`journey.spec.ts` stays local/manual: it registers a real account and drains the outbox, so it
needs Postgres + the API. Wiring services into the workflow is its own step — worth doing, but
not silently bundled into this one.

## Also here: long catalog names were unreadable

Reported from a phone. Catalog positions are long and specific («Профіль/куточок для плитки
алюмінієвий 10 мм»), and the **tail** is what tells two of them apart — but the list truncated
to one line, so a master was tapping rows they could not read. Now `break-words`, matching the
template rows and the catalog picker inside the estimate editor, which both already wrapped.

The test asserts the mechanism, not pixels (jsdom does no layout) — verified by restoring
`truncate` and watching it go red. Mobile layout itself could not be checked in the Browser
pane here (it needs auth and the pane was not displaying), and that is stated rather than
glossed over.

## Gotchas
- A local `CORS_ALLOWED_ORIGINS` **replaces** the default list. Adding an origin to
  `application.yml` does nothing for a developer who has the env var set.
- `shell.spec.ts` must stay backend-free. The moment it needs an account, it stops being
  CI-runnable and the SW regression net is gone again.
