# Iteration: audit batch C — data correctness

Third batch off the audits (after [A](iteration-audit-batch-a.md) — the CI/lint net — and
[B](iteration-audit-batch-b.md) — money & security). Ordered by real cost: **losing** data
outranks **wrong** data, which outranks leaked files and stale screens.

- **Status:** 🔨 code complete; backend build on the user
- **Migration:** **V67** — rejected estimates are not income
- **PWA:** 0.35.0

## PWA H1 — a write could vanish while the badge said "syncing…"

An op that failed 8 times was left as `status:'failed', attempts:8`. `flushOutbox` then did
`if (op.attempts >= MAX_ATTEMPTS) continue;` — skipped, never retried, never deleted. But
`refreshPending` computes `pending = total − blocked`, so it still counted as **pending**,
and `SyncReviewSheet` lists only `blocked` ops, so it appeared on **no screen**. The master
saw "1 pending / syncing…" forever and believed the write would land. It never would.

Now crossing `MAX_ATTEMPTS` transitions the op to the terminal `blocked` state with a new
`blockReason: 'stuck'`, so it is counted correctly and surfaced with a resolution path.
The skip branch also **heals** ops already stranded by an older build — they are promoted
on the first flush that sees them, so nobody has to lose what is already queued.

`'stuck'` is deliberately distinct from `'limit'` and `'other'`: nobody rejected these, the
app ran out of retries. The review sheet says so — telling the master "the server didn't
accept it" would be a lie — and «Спробувати синхронізувати» resets the attempts, so once the
connection is back the write genuinely lands.

## PWA H2 — deleting a blocked parent silently took its children down

`dropBlockedOps` deleted only the blocked rows. A child holds in the queue only while a
matching op is still present, so removing the parent **released** its dependents to replay
against something the server never got:

> offline, over the FREE cap: object P (blocked) + estimate E `deps:[P]` + item I `deps:[E]`.
> The master taps "delete" on the one blocked item. P goes; E fires `createForProject(P, …)`
> → 404 → retries → dies at MAX_ATTEMPTS (H1). One tap, three writes lost, no message.

`dropBlockedOps` now computes the **transitive closure** — an op dies if it targets a doomed
entity or depends on one, repeated to a fixpoint so a grandchild reaches the dropped parent
through its parent — and returns every dropped entityId so the caller purges all the matching
optimistic entries. The sheet warns **before** the tap that related changes go too.

## M6 — rejected estimates were counted as income (V67)

V57 flipped economy counting to default-ON with a blanket
`UPDATE estimates SET count_in_economy = TRUE WHERE count_in_economy = FALSE`. Its comment
justified blanket TRUE only for *consolidated* estimates — but the prior model (V51) had
deliberately set TRUE only `WHERE status = 'SIGNED'`, so rejected variants had been excluded
and V57 swept them back in. `sumIncomeCounted` filtered on the flag alone, so **every master
with a rejected estimate has been seeing inflated object income ever since**, with no hint
why and no remedy but to untick it by hand.

Fixed in three places, because one was not enough:
1. **V67** patches the existing data (the user's call: repair what masters see today).
2. `AND e.status <> 'REJECTED'` on all three counted-income queries — so it can never be
   income again even if something flags it later.
3. `EstimateService.update` clears the flag when the status becomes REJECTED — otherwise the
   checkbox would sit ticked on an estimate that is not, in fact, counted.

⚠️ The SQL guard itself is **not covered by a test** — these are native queries and the suite
has no database (audit M12). The service-level rule is tested; the query change rides on the
Testcontainers slice landing.

## M7 — deleting a project orphaned all its photo files

Photo *rows* cascade with the FK; the stored objects did not. Every project delete leaked its
files on R2/local storage permanently — cost creep, and, since receipt photos are financial
personal data, a "delete" that quietly kept the data. `delete` now collects the storage keys
**before** the rows disappear and best-effort removes each, reusing the fail-soft pattern from
the single-photo path: the row deletion is already committed, so throwing here would leave a
half-deleted project, and a leftover object is the recoverable failure.

## M5 — the receipt import refreshed only one screen

It invalidated `[ESTIMATE_KEY, estimateId]` alone, so importing a ₴5 000 receipt left the
object list, the dashboard and the object economy showing the pre-import total until they
happened to refetch. The full set already existed as a private `useInvalidateEstimate` — now
exported and reused, rather than a third hand-rolled subset.

## Tests
- `outbox.test`: a stuck op becomes blocked with `blockReason: 'stuck'` and stops being
  counted as pending; a stuck op can still be retried once the network returns; dropping a
  blocked parent cascades to child **and grandchild** and releases nothing. Both new tests
  fail against the old code (it left `failed`, and returned `['p1']`).
- Backend: `update_toRejected_stopsCountingItAsIncome`;
  `delete_alsoRemovesTheProjectsStoredPhotos` + `delete_survivesAStorageFailure`.
- PWA 290/290, lint 0, tsc + build green.

## Gotchas
- `ProjectService` gained two constructor deps, so `@InjectMocks` in `ProjectServiceTest`
  would have injected **nulls** and `delete` would NPE — both mocks added.
- The cascade closure iterates to a fixpoint on purpose. A single pass would drop the child
  but keep the grandchild, which then replays against a parent that no longer exists — the
  exact bug, one level deeper.
