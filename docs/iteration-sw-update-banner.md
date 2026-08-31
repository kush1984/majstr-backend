# Iteration: the update banner nobody had ever seen

**Status:** complete — PWA green on the full CI mirror (lint · tsc -b · typecheck:tests · **762**
vitest · vite build) plus the offline service-worker e2e spec. NOT committed (awaiting the user's
approval).
**Source:** the master, in passing, while checking the PostHog fix: «доречі оцього банеру оновлення я
ніколи не бачив». Scoped and approved: «давай перший варіант, щоб банер працював».
**Migration:** none. **Backend:** untouched. **PWA version:** 1.29.2 → **1.29.3** (patch).

---

## 1. It was never a rendering bug — the banner was unreachable code

`<UpdateBanner>` has been mounted at the app root since the password-reset iteration (2026-07-17),
with a test, with `lib/swUpdate.ts` behind it, and with three separate source comments promising
"we never reload silently". It could not appear. **Two independent causes, either one sufficient:**

1. **`registerType: 'autoUpdate'` in `vite.config.ts`.** In that mode the registration
   vite-plugin-pwa generates **destructures `onNeedRefresh` and never calls it**, and instead
   handles `activated` by calling `window.location.reload()` itself. `updateSW` comes back as a
   no-op (`const m = async (h = !0) => { await x }`). So the callback `main.tsx` passes was dead on
   arrival, and the "silent reload" the banner exists to prevent was in fact the shipped behaviour.
2. **`self.addEventListener('install', () => void self.skipWaiting())` in `src/sw.ts`.** Even in
   `'prompt'` mode, `onNeedRefresh` only fires for a worker sitting in `waiting`. A worker that
   skips waiting on install never sits there, so nothing is ever reported.

Both have been present since the initial commit of the SW (`git log` on `src/sw.ts`); the banner was
added on top of a worker that could never wait. Nothing in the test suite could see it: the unit
test drives `swUpdate.ts` directly, and no test reads the build config.

## 2. What shipped

`vite.config.ts` — `registerType: 'prompt'`. That single word switches the generated registration to
`d.addEventListener("waiting", v)` where `v` calls our `onNeedRefresh`, registers
`controlling → window.location.reload()`, and makes `updateSW` real
(`m = () => { d?.messageSkipWaiting() }`).

`src/sw.ts` — the install listener is gone, replaced by the other half of the handshake:

```ts
self.addEventListener('message', (event) => {
  if (event.data?.type === 'SKIP_WAITING') void self.skipWaiting();
});
self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});
```

`messageSkipWaiting()` posts `{type: 'SKIP_WAITING'}`; a custom SW (this one is `injectManifest`, not
`generateSW`) has to handle it itself or the master's tap does nothing. **`clients.claim()` is not
optional either** — without it the already-open page keeps the OLD worker as its controller,
`controlling` never fires, and the reload after the tap never lands. Three parts, all required; the
comment in `src/sw.ts` says so, because each one is individually easy to "clean up".

Order of events now: deploy → new SW installs and **waits** → plugin calls `onNeedRefresh` →
`markUpdateReady` → banner → master taps «Оновити» → `updateSW(true)` → `messageSkipWaiting()` →
`SKIP_WAITING` → `skipWaiting()` + `claim()` → `controlling` → reload onto the new build.

## 3. Why this is worth a version bump rather than a footnote

The old behaviour reloaded the page under the master mid-work. On the two **explicit-save** screens —
the act editor and the template editor — that is unsaved work. `useLeaveGuard` registers
`beforeunload`, so in practice the master got a native browser dialog they had no context for, at a
moment they did not act; the choice they wanted («дай дописати, оновлюся потім») was never offered.
It also explains a class of "воно саме перезавантажилось" reports that had no other cause.

## 4. Tests

`src/lib/swUpdate.test.ts` (new, 5 cases). One drives the bridge; the other four **read the source
files as text** and assert the contract:

| Assertion | Regression it catches |
|---|---|
| `vite.config.ts` contains `registerType: 'prompt'` | someone "simplifies" back to autoUpdate |
| `src/sw.ts` has no `addEventListener('install'` | someone re-adds `skipWaiting()` on install |
| `src/sw.ts` contains `'SKIP_WAITING'` + `self.skipWaiting()` | the handler is dropped as unused |
| `src/sw.ts` contains `self.clients.claim()` | claim is removed as "not needed" |

Reading source in a test is unusual and deliberate. **This feature's failure mode is that nothing
ever appears** — there is no exception, no red anywhere, and the mocked-registration tests stay green
in every broken configuration, which is exactly what happened for a year. Behaviour that lives in a
build config and in a service-worker lifecycle is not reachable from jsdom; the file contents are the
only place the contract exists.

Proven to bite: both regressions were temporarily reintroduced and 2 of the 5 failed, then reverted.

## 5. What was verified, and what could not be

- Full CI mirror, in order: `lint` → `tsc -b` → `typecheck:tests` → `vitest` (762/762, 108 files) →
  `vite build`. Green.
- `npm run test:e2e:offline:shell` — the offline shell spec (deep route opens the app, not a browser
  error page) — **passed** against a real `vite build` + preview. This is the spec CI runs; the
  `journey.spec` half needs a backend on :8080 and was **not** run.
- The generated output was inspected rather than assumed: `dist/sw.js` has no install listener of
  ours, has the `SKIP_WAITING` handler and `activate → clients.claim()`; the generated registration
  wires `waiting → onNeedRefresh` and `controlling → reload`.
- **Not verified:** the banner appearing on a real device across two real deploys. That needs two
  builds live in sequence and cannot be done from here. The honest local check is
  `vite build && vite preview`, load the page, build again, reload once — the second load should show
  the banner instead of swapping silently. (Dev mode proves nothing: the SW is disabled there.)

## 6. Mobile

`UpdateBanner` is unchanged — same fixed bottom placement, same full-width tap target, already
verified at 375 px when it shipped. Nothing in this iteration touches layout; it only makes the
existing component reachable.

## 7. A doc that was wrong, and the rule from it

The open-questions item **"Service worker update UX (silent reload can drop form input)"** has been
marked `RESOLVED` since 2026-07-17. It was not. The resolution described the banner's code, which was
written and correct, and never checked that the banner could fire. That is the trap worth naming:

> **A resolution that names the code it added is not a resolution. A resolution names what it
> OBSERVED.** For anything that only appears under a condition the dev machine does not naturally
> reach — a second deploy, an offline start, an expired token — "the code is there" and "the feature
> happens" are different claims, and only the second one closes an item.
