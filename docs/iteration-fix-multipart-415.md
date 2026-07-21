# Fix: 415 on every file upload (multipart sent as application/json)

Production bug, real masters: `HttpMediaTypeNotSupportedException: Content-Type
'application/json' is not supported` on `POST .../measurements/sketch/parse`
(Sentry **JAVA-SPRING-BOOT-D**, 2 users × 5 attempts). The brand-new sketch feature was
dead on its very first step.

- **Status:** ✅ Fixed — PWA green (tsc / 141 tests). **PWA-only, no backend change.**
- **Scope:** `src/api/client.ts` + `src/api/client.test.ts` — nothing else.
- **Commit line:** `Fixes JAVA-SPRING-BOOT-D`

## Root cause (shared, as suspected)
`api = axios.create({ headers: { 'Content-Type': 'application/json' } })` — an
**instance-wide** default. It rides along on FormData bodies too, so an upload reaches
Spring as JSON and is rejected 415 before any handler runs.

Five upload call sites had a hand-written workaround (`'Content-Type': undefined`):
`catalogImport`, `estimateImport`, `photos`, `profile`, `receiptImport`. **Two did not:**
- `sketchImport.ts` — the production bug;
- `electricalPlan.ts` — written the same day, would have shipped broken identically.

So the workaround had to be remembered on every new endpoint, and twice it wasn't. That's
a cause worth fixing centrally, not a typo worth patching twice.

## The fix
One guard in the existing request interceptor:

```ts
if (typeof FormData !== 'undefined' && req.data instanceof FormData) {
  req.headers.setContentType(false);
}
```

- `setContentType(false)` is the AxiosHeaders way to clear it. A plain
  `delete req.headers['Content-Type']` **does not work** — AxiosHeaders keeps a normalised
  entry, and the first attempt at this fix left `application/x-www-form-urlencoded` behind
  (the test caught it).
- `false` is axios's "send no Content-Type" sentinel → the browser fills in
  `multipart/form-data; boundary=…`, which only the browser can generate.
- JSON requests are untouched — they still get `application/json`.

The per-call `'Content-Type': undefined` workarounds were left in place: they are harmless,
and a minimal diff is right for a hotfix. New endpoints no longer need them.

## Tests (both new, in `client.test.ts`)
- FormData body → the outgoing Content-Type is **never** `application/json` (and is falsy,
  so the browser sets the boundary).
- Ordinary object body → still `application/json`.

The first is the regression guard: this bug reached production, so it now has a test.

## Verified
- tsc clean; **141/141** PWA tests (was 139).
- Backend needed no change — all six upload endpoints already declare
  `consumes = MULTIPART_FORM_DATA_VALUE` with `MultipartFile`
  (sketch, receipt, estimate import, catalog import, electrical plan, project photo).

## Not changed
- Backend endpoint declarations, CORS, Sentry filtering.
- **Sentry was deliberately not filtered.** The prompt's own lesson applies: this 415 was a
  real front-end bug, not 4xx noise. Muting client 4xx wholesale would have hidden it.
  Logged as an open question instead (filter only clearly external/bot traffic).

## Gotcha for the future
**FormData → never set Content-Type by hand.** A manual `application/json` on an upload is a
guaranteed 415; even a manual `multipart/form-data` without a boundary fails. Let the browser
do it. With this interceptor the rule is enforced centrally, so it can't be forgotten again.
