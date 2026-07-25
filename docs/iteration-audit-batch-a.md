# Iteration: audit batch A — the safety net (CI, lint, timeouts)

First batch off the two code-quality audits (`majstr-backend-audit.md` /
`majstr-pwa-audit.md`, delivered at `a405e55` / `7d4117c`). Every finding was
**re-verified against current HEAD first** — the audits predate 0.24→0.34, so some
could have closed on their own. None had; all twelve checked were still live.

Batch A is deliberately the *infrastructure* batch: it changes how everything else
gets verified. Money/security (B), data correctness (C) and concurrency (D) follow.

- **Status:** ✅ backend build confirmed green by the user; PWA green
- **Migration:** none
- **PWA:** 0.34.1

## The backend test suite did not compile

Before any audit item: `./gradlew build` failed at `compileTestJava` with 7 errors,
i.e. **the backend tests had not run for some time and nobody knew** — which is
exactly the hole audit H4 describes. Three stale files:

- `ProjectNoteServiceTest` — `willThrow` imported from `Mockito` instead of `BDDMockito`.
- `ProjectServiceTest` — missing `import static ArgumentMatchers.any`.
- `SketchImportServiceTest` — `containsOnlyKeys(String…)` can't apply to a `capture-of-?`
  map; bound the wildcard to `Map<String, Object>` first.

Then three real failures surfaced, all pre-existing:

1. `ProjectControllerTest` stubbed the **2-arg** `projectService.create`, but the
   controller moved to the **3-arg** overload when offline `X-Entity-Uuid` landed.
   (The same signature-fan-out class that has bitten twice before.)
2. `TokenCleanupServiceTest` — the service gained `PasswordResetTokenRepository`;
   the test never mocked it, so `@InjectMocks` left it null → NPE.
3. `SketchImportServiceTest.surfaceWithOnlyUnknownShapesHasNoResult` — **a real bug,
   not a stale test.** See below.

## A silent, confident zero in sketch import (audit L2)

A SURFACE whose planes were **all** dropped as unreadable shapes computed a clean
`0.000` and **kept the model's original `high` confidence** — a confident zero going
straight to the review screen. Only the throwing path (`Shapes` rejects `a=0`) was
being caught; an empty-segments surface never throws, and audit L2 reports the same
for PARTITION/LINEAR with unreadable dimensions.

Fixed at the root in `SketchImportService` with one general rule: **a zero result means
nothing was readable, not a measurement of zero** → blank + `low` (which blocks commit
until the master fixes or removes it). Closes L2 as well. Verified the existing
zero-assertions elsewhere test `MeasurementCalc` directly and are unaffected.

## H3 — outbound HTTP had no timeouts anywhere

All three clients used `RestClient.create()` (no connect, no read timeout). A stalled
upstream pins a Tomcat worker forever; enough of them and the **whole API stops
answering for every user**. This got *worse* since the audit: the Claude extractor had
2 call sites then and has **six** now (estimate, receipt, sketch, electrical plan,
project-import ×2 passes), all sharing that one client.

New `config/HttpClients` — connect 5 s everywhere, read sized per upstream:
LLM 120 s, monobank **10 s** (it runs *inside* the webhook transaction, so a hang also
pins a Hikari connection), Resend 10 s.

## H4 / PWA H4 — CI

- `majstr-backend/.github/workflows/build.yml` — `./gradlew build` on every push.
  No DB or secrets needed (verified: **0** `@SpringBootTest`, so no Spring context starts).
- `majstr-pwa/.github/workflows/ci.yml` — `npm ci` → lint → `tsc -b` → vitest → build.

⚠️ **CI alone does not gate the deploy.** Railway → service → Settings → **“Wait for CI”**
must be switched on, or a red commit still ships. That is a console toggle, not code.

## PWA H3 — lint, from zero

ESLint was not installed at all and the script pointed at a nonexistent `./majstr-pwa`
directory. Added eslint 9 + typescript-eslint + react-hooks with a flat config whose
whole justification is two rules as **errors**: `no-floating-promises` (a dropped
`await` on an outbox/auth call silently loses a write) and `exhaustive-deps`.

**230 → 0.** How they resolved:

| Finding | Resolution |
|---|---|
| 82 `no-misused-promises` on JSX attrs | `checksVoidReturn.attributes: false` — `onClick={async …}` is idiomatic React; flagging it buried the 65 real ones |
| 45 bare `qc.invalidateQueries(…)` | `void`-prefixed — the codebase already spelled 2 of them that way |
| 19 bare `navigate(…)` | `void`-prefixed (react-router returns a Promise) |
| ~10 “Promise → void property” | **fixed at the source**: helpers like `useInvalidateCatalog` had an implicit `return qc.invalidateQueries(…)`, so their type lied. Block body + `void` makes it honest |
| `guard()` in `useOnlineGuard` | widened to `(...args) => void \| Promise<void>` — most guarded actions genuinely are async |
| 2 `exhaustive-deps` | real: `matchTrade` wrapped in `useCallback` (adding it to deps without that would have defeated the memo) |
| 4 `unbound-method`, 8 `require-await` | off with a reason: `queryFn: authApi.me` never touches `this`; an async mock stub matches a Promise signature by design |

## PWA M9 — tests were never type-checked

`tsconfig.test.json` now covers unit tests, e2e specs and the tooling configs. Turning it
on immediately surfaced **dozens** of long-invisible errors — stale mocks missing
`balance`/`floor`/`consentedToPrivacyAt`, enum values that no longer exist (`"PCS"`,
`"FINISHING"`), `HTMLElement` where `HTMLInputElement` was meant. Exactly M9's warning
that a wrong-shaped mock can mask a real regression.

**Deliberately not wired into `npm run build`** — that backlog would turn the build red.
It runs as `npm run typecheck:tests` and as an advisory (`continue-on-error`) CI step;
clearing it is batch B/C work, after which the flag comes off. ESLint reaches those files
via an explicit `project:` list rather than the root references, so linting is unaffected
by that decision.

## PWA H5 — SPA fallback

`public/_redirects` with `/* /index.html 200` (Cloudflare Pages). Without it a refresh,
a shared deep link, the monobank return redirect, and an installed PWA relaunching onto
a sub-route all 404.

## The flaky test

`ProjectImportSheet > AUTO-parses…` failed once in three full runs, on an unchanged
commit. Cause: `waitFor`'s **1 s default** against a real pdfjs page-text pass that takes
seconds on a loaded machine. Explicit 10 s waits; the test's own 20 s budget still bounds it.

## Verification
- Backend: `./gradlew build` green (confirmed by the user).
- PWA: lint 0, `tsc -b` green, **288/288** tests, production build green.

## Gotchas
- The `void`-prefix passes only ever matched a **bare statement at line start**, so
  `await …` / `return …` / assignments were never touched.
- `tsconfig.test.json` is intentionally *absent* from the root `tsconfig.json`
  references. Adding it there is what turns `npm run build` red — the type backlog must
  be cleared first.
