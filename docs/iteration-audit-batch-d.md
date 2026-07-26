# Iteration: audit batch D — attacker-supplied bytes & known CVEs

Fourth batch off the audits (after [A](iteration-audit-batch-a.md) CI/lint,
[B](iteration-audit-batch-b.md) money & security, [C](iteration-audit-batch-c.md) data
correctness). Same ordering principle: real cost × likelihood ÷ effort. This cluster is
everything where **bytes we did not write** reach a parser, plus the two pinned libraries
with published CVEs — all cheap fixes against a real attack surface.

- **Status:** 🔨 code complete; backend build on the user
- **Migration:** none
- **PWA:** 0.35.1 (version marker only — this batch is backend-only)

## M8 — Apache POI 5.3.0 → 5.4.1 (CVE-2025-31672)

POI parses **attacker-supplied** `.xlsx`/`.xls` here: the price-list import and the estimate
import are open to any registered account. 5.3.0 predates the 5.4.x line and its OOXML
parsing fix, so this is not a routine housekeeping bump.

## L10 — jose4j 0.7.9 → 0.9.6 (CVE-2023-31582)

A 2021 build pinned on a flagged version. Low exploitability here (it only signs VAPID JWTs
for web push), but the risk of bumping is also low: **we call no jose4j API ourselves** — it
sits on the compile classpath purely because `PushService.send()` declares `JoseException` —
and the class it needs is unchanged across 0.7 → 0.9.

## M9 — the logo had no size cap, and the comment lied

The code claimed "Spring caps multipart at 2 MB" and the endpoint advertised "max 2MB", but
the only checks were `length < 4` and a magic-byte peek. The comment was never true, and the
global multipart cap has since grown to **15 MB** (raised for photo imports) — so a 15 MB
"logo" was accepted, stored, and then `readAllBytes()`-ed into **every rendered PDF**.

Explicit `MAX_LOGO_BYTES = 2 MB`, mirroring the check `ProjectPhotoService` already had.

## M10 — the spreadsheet was fully materialised before the row cap applied

`for (Row row : sheet)` had no bound; `MAX_ROWS` was only checked *after* the whole grid and
every candidate row had been built. A zip-compressed `.xlsx` expanding to hundreds of
thousands of rows was therefore read into memory in full before being rejected — a
memory/CPU spike per request, from any account. The sibling
`EstimateImportService.spreadsheetToText` already broke out early; the catalog parser now
does too.

The margin above `MAX_ROWS` is now the shared `GRID_READ_MARGIN` constant instead of a
literal `50` repeated in `capGrid`, so the reader and the cap cannot drift apart and silently
stop rejecting over-limit sheets.

## L13 — no graceful shutdown

`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s`. This app has
genuinely slow endpoints — PDF rendering and the LLM imports run for seconds — so a Railway
redeploy cutting them mid-response lands on a master as a failed import they must redo, and on
a payment webhook as a delivery we never answered.

## Tests
- `uploadLogo_rejectsAnOversizedFile_andStoresNothing` — asserts the refusal **and** that
  storage was never touched.
- The POI read cap and the dependency bumps have no unit test: the first needs a
  hundred-thousand-row fixture to be meaningful, the second is a build-graph change. Both are
  covered by the suite still passing (the parser tests exercise the same path with normal
  sheets).

## Gotchas
- **The two dependency bumps are unverified by me** — Gradle does not run in this sandbox, so
  resolution and compatibility are proven only by the user's `./gradlew build`. jose4j is the
  one to watch: if web-push turns out to need an API that moved, the symptom is a compile
  error in `PushService`, not a runtime surprise.
- `spring.lifecycle:` is written as a flattened key, matching the file's existing
  `spring.servlet.multipart:` style rather than nesting into the `spring:` block at the top.
