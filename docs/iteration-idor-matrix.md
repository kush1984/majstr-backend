# IDOR matrix + the secret-scan gate, 2026-10-06

**Status:** built, `./gradlew build` green (Docker up, all ITs ran). **No migration.**
Backend only; the PWA gets the CI workflow and a version bump, no source change.

Answer to «а як у нас на рахунок секюріті, тестів по тому чи можемо ми прогнати якісь пентести?» —
the two rungs of that ladder a scanner cannot climb for us.

---

## Why a test and not a scanner

An IDOR is the one class of bug a generic scanner structurally cannot find here: it needs **two
authenticated masters, a complete data graph under each, and a verdict about whose row came back**.
A scanner sees a 200 and moves on. So the matrix is an integration test that stays in CI forever
rather than a one-off run.

The gate it complements already existed: `SecurityMatrixIntegrationTest` answers «was I let in».
This one answers **«whose row did I get»**.

Until now no test exercised ownership through the real chain at all — the controller tests are
standalone MockMvc with **no Spring Security in the context**, so every ownership guard in the
product was covered only by its own service's unit test with a mocked repository.

---

## `IdorMatrixIntegrationTest` — the shape

Two masters are seeded once for the class (`victim` = A, `attacker` = B), both on **`Plan.TEAM`**
(only TEAM unlocks every gated feature, so a 403 is never the plan's) and both **`emailVerified`**
(else `POST /estimates/{id}/share` answers 403 `EMAIL_NOT_VERIFIED` and the case proves nothing about
ownership). Each gets a full graph — client, object, estimate + line, bundle + item, room +
measurement item, expense, payment + receipt, note, photo + folder, message + file, object receipt,
shopping-list item, act + act receipt, cash entry, catalog item + update notice, custom trade,
share links. The seed asserts none of the ids is null before a single request is sent.

Then B's token is pointed at A's ids, in three classes:

| | What it probes | Requests |
|---|---|---|
| **Foreign parent** | A's project / estimate / act / bundle id straight in the path | 154 |
| **Foreign child under B's OWN parent** | B's parent + A's child — the guard that is easy to forget | 44 |
| **Foreign id in the BODY** | a path entirely B's own, A's id in the payload | 11 |

Real HTTP over a real socket (JDK `HttpClient`; Boot 4 removed `TestRestTemplate`), so the whole
filter chain runs.

### The verdict: 403 or 404, and why 400 fails too

`DENIED = {403, 404}`. A **2xx is a leak**. A **400 fails as well** — a body rejected by validation
never reached the ownership check, so the case was vacuous; two of them were real own-goals here
(`B_ROOMS_COMMIT` shipped an empty `items[]`, which is `@NotEmpty`). A **5xx fails** — untrusted
input crashed the handler. 404 is the preferred answer: 403 confirms the id belongs to someone
(review B-45 settled exactly that for a foreign cash id).

### The one legitimate 2xx — a WITNESS, not a status

The first run surfaced 17 answers of 204/200 that were **not** leaks, and that is the central finding
of the round: **this codebase makes deletes idempotent on purpose.** `findByIdAnd<Parent>Id(…)
.ifPresent(…)` — or `findAllById` plus a parent-equality filter — means a replayed offline delete is
never reported back to the master as a failure, and the deliberate price is that a foreign id answers
«done» having touched nothing. Five services document that in prose (`EstimateService.deleteItems`
says «Idempotent by construction»; `CashFlowService.delete` names B-45 and the replay).

So for those routes **status is the wrong oracle**. Such a case carries a witness instead:

```java
"select coalesce((select md5(t::text) from <table> t where t.id = ?), 'gone')"
```

the victim's **whole row, hashed** — and a 2xx passes only when the fingerprint is identical before
and after. Hashing the whole row rather than naming columns means a no-op has to be a no-op in every
field (a soft delete that only stamps `cleared_at` moves it), and a deleted row prints `'gone'`, so a
successful foreign delete can never read as unchanged. A 2xx with **no** witness still fails, and
400/5xx still fail with a witness.

15 cases carry one: the two catalog-notice verbs, the PERSONAL cash delete, estimate items, bundle
items (PATCH and DELETE), measurement room + item, expense, payment, payment receipt, note, the
inbox delete under both aliases, the shopping-list item.

### The body-borne class asserts the same way by construction

Those 11 cases run against a path that is entirely B's own, so a 2xx is a legitimate answer — what
must not happen is A's row moving. The fingerprint queries cover `estimate_items`,
`estimate_template_items`, the estimate header, receipts-of-a-payment, templates-of-a-trade and
projects-of-a-client: `items/markup`, `items/delete`, `items/order`, template `items/order`,
`portal`, `portal/economy`, `payments/receipts` with A's `planPaymentId`, `transfer-surplus`,
`templates/{id}/trade` with A's `customTradeId`, and `POST`/`PUT /api/projects` with A's `clientId`.

### A coverage guard in BOTH directions

A matrix is only as honest as its completeness, so a fourth test enumerates
`RequestMappingHandlerMapping` at runtime:

- **`uncovered`** — every id-bearing owner-scoped `/api` route (not `public|admin|auth|billing`,
  contains a `{`) with no case and no exemption. **151 routes, all covered**; `EXEMPT` is empty on
  purpose.
- **`phantom`** — a case naming a route the application does not serve. Without it, renaming an
  endpoint away would leave its case 404ing for the wrong reason and passing forever.

`template` is always the registered pattern, never the filled path — which is why a query string is
added through `withQuery(…)` rather than into the template.

---

## The defect the matrix found

**`POST /api/acts/{id}/receipts/{receiptId}/recognize` answered 500** for B's own act + A's receipt.

`WorkActService.requireOwned` compares `act.getProject().getOwner().getId()`. `WorkAct.project` is
`LAZY`, so the **second** hop needs a session — and `WorkActReceiptService.recognizeStored` is
deliberately **not** `@Transactional` (a vision call runs for seconds and holding a pooled connection
across it starves the pool). It called its own `@Transactional readOwnedFile`, and a **self-invocation
skips the proxy**, so the guard ran with no session and threw `LazyInitializationException`.

Nothing leaked — the act's ownership had already been proven by `actService.get(...)`, which is a real
bean call — but **the guard itself crashed**, the receipt-to-act binding never ran, and the master saw
«Внутрішня помилка сервера».

**Fix:** `@Transactional(readOnly = true)` on `WorkActService.loadOwned`. `REQUIRED` joins the
caller's transaction wherever there is one, so every write path still gets a managed entity, and a
guard can no longer depend on its caller having opened a session. One hop is safe without one —
Hibernate answers a proxy's **id getter** without initializing it, which is why
`ProjectService.loadOwned` (`project.getOwner().getId()`) never had the bug and why only the two-hop
act guard surfaced.

Everything else the matrix flagged triaged to a documented idempotent no-op, verified service by
service: `EstimateService.deleteItems`, `CatalogTemplateService.dismissUpdateNotice`/`acceptUpdateNotice`,
`CashFlowService.delete` (PERSONAL), `EstimateTemplateService.updateItem`,
`MeasurementService.deleteRoom`/`deleteItem`, `ObjectExpenseService.delete`,
`PaymentService.delete`/`deleteReceipt`, `ProjectNoteService.delete`, `ShoppingListService.delete`,
`MessageService.delete`.

**No IDOR was found.** The product's ownership model held on all 151 routes.

### Three cash deletes that were never probed

`CashFlowService.delete` branches on `kind`: without one it is PERSONAL and a no-op, but
`OBJECT_PAYMENT` / `OBJECT_EXPENSE` / `OBJECT_RECEIPT` really do run `requireOwner` /
`requireOwnedObject`. Those branches are unreachable from the path alone, so three cases now carry
the query string (`withQuery`) and point at A's payment receipt, expense and object receipt. They
answer denied.

---

## gitleaks — the secret-scan gate

The scan itself: **both repos clean**, run with `--redact` over the full history (backend 214
commits, PWA 197), pinned **v8.30.1**.

The durable half is `.github/workflows/secrets.yml` in **both** repos:

- `actions/checkout@v4` with **`fetch-depth: 0`** — the history IS the point. A key pushed once stays
  readable in the object store after the file is «fixed» in a later commit, so the only honest answer
  to a leak is to rotate; this job turns that into a red build on the push that introduces it, while
  rotating is still the only cost.
- The **binary, pinned** (`gitleaks_8.30.1_linux_x64.tar.gz`) and `gitleaks git . --redact`. Not the
  official action: it asks **organization** accounts for a licence key, and a floating `latest` would
  mean a push could redden on a rule nobody added.
- `--redact` because a run log quoting the key it found would be a second leak.
- **No baseline file**, deliberately: both repos were clean when this landed, so the first finding
  this job ever reports is a real one.

The PWA's copy carries its own header, because a bundle is public by construction — every `VITE_*` is
compiled into JavaScript anyone can download — so the only legitimately secret-shaped string there is
the public VAPID key, and anything else the scanner flags is a mistake.

---

## Not changed / confirmed

- **No migration, no schema change, no DTO change** — so the PWA contract is untouched.
- **`loadOwnedForUpdate` is NOT annotated.** A self-started transaction would commit and release the
  `FOR UPDATE` lock before the caller used it — the exact opposite of what that method is for.
- **`EstimateService.loadOwned` has the same two-hop shape and is NOT changed**: every one of its
  callers is `@Transactional`, and the matrix is the proof — all those routes answered 403/404.
  If one ever stops being transactional, the matrix reddens with a 500.
- **`EXEMPT` stays empty.** `/api/files/**` carries no `{`, so `isOwnerScopedIdBearing` never reaches
  it; it is covered by its own tests.
- **Dependency scanning and a DAST pass (ZAP) are the next two rungs** and are out of this round —
  neither repo has a `dependabot.yml` and the PWA's CI has no `npm audit`. Tracked in
  `docs/open-questions.md` → Security → «Automated security scanning».

---

## Gotchas

- **A guard that walks TWO lazy hops needs its own transaction.** One hop is free (Hibernate answers
  a proxy's id getter without initializing it); the second one is a `LazyInitializationException`
  waiting for a non-transactional caller, and it reads as a 500, not as a denial.
- **A self-invoked `@Transactional` method is not transactional.** The annotation lives on the proxy;
  `this.method()` never touches it. The three AI verbs here are non-transactional on purpose, which
  is exactly the shape that trips over it.
- **An idempotent delete cannot be asserted by status.** It answers 204 for an id that is not his —
  by design, for offline replay. Assert the victim's row, not the status code.
- **A case's `template` must be the REGISTERED pattern.** The coverage guard matches on it, so a
  query string or a filled-in id there makes the case a phantom.
- **The Testcontainers schema is SHARED across the whole run.** The seed creates its own rows and
  every assertion is scoped to them; nothing is counted globally.
