# Review leftovers — the items four rounds left open (2026-10-12)

`FIXES-4.md` closed on both sides (see [iteration-money-audit-4.md](iteration-money-audit-4.md)).
What the rounds still carried from earlier: B-31d, B-76, B-20, B-13, B-42's legacy IT and the
rotten offline `journey.spec`. This doc records them one at a time.

## B-31d — two receipts uploaded at once got the same «Чек №N»

**Owner ruling (2026-10-12):** number under the lock, don't accept duplicates.

An unlabelled receipt is named «Чек №N» by the server, with N = the highest `sort_order` + 2. That
max was read in the NON-transactional `add`, before the insert, so two uploads to one act or object
in flight together (two phones, or the outbox replaying while an online batch runs) both read the
same max and both saved «Чек №3».

- **Act receipts** — `WorkActReceiptCreator.attempt` already locked the act `FOR UPDATE` (B-60);
  the cap check, `maxSortOrder` and the default label moved inside it, after the lock.
- **Object receipts** — `ProjectReceiptCreator.attempt` now locks the object row first
  (`ProjectRepository.findByIdForUpdate`, new) and does the same. The transaction is short — the
  photo is stored before it starts — so the lock is held for one insert.
- `attempt` takes the typed label or `null` («name it yourself») and no longer a `sortOrder`. The
  services keep their early cap check too, so an over-limit upload is still refused before the
  photo is stored; the locked re-check is what makes the cap exact.
- The act's gallery copy («Чеки» folder) is filed under the label the insert actually wrote.

Tests: `WorkActConcurrencyIntegrationTest.twoUnlabelledUploadsAtOnce_getTwoNumbers` and
`…twoUnlabelledObjectReceiptsAtOnce_getTwoNumbers` hold the first insert's transaction open, start
the second, assert it BLOCKS, then release — «Чек №1», «Чек №2». `ProjectReceiptServiceTest` now
pins only that a blank label reaches the creator as `null`.

A number can still repeat after the LAST receipt is deleted (max drops back), which is not a
duplicate — the old one is gone. A master typing «Чек №4» by hand is his own label.

## B-13 — two replays of one shopping-list add at once answered 500

B-13's first half (Fix M) scoped the replay lookup to the object's own list and stopped a foreign id
from merging over another list's row. The remainder: two requests carrying the SAME client id in
flight together (a weak link retrying while the first is still running) both found nothing, both
inserted, and the loser died on the primary key — a 500 the outbox retried, though the row had
landed. The same window let a first add and a first recalculation create two lists for one object
(`shopping_list.project_id` is UNIQUE) and hand out one `sort_order` twice.

- `ShoppingListService.getOrCreate` — the one door every list write that can create something goes
  through (`addManual`, `applyCalculated`) — now locks the object row first
  (`ProjectRepository.findByIdForUpdate`, added for B-31d). The second request waits, then reads the
  first one's committed row and answers as a replay.
- Not the B-09 creator-bean retry: there the insert sits after a photo upload outside any
  transaction, so a lock could not cover it; here the whole add is one short transaction.

Test: `ShoppingListIntegrationTest.twoConcurrentReplaysOfOneAdd_landOneRow` holds the first add's
transaction open, asserts the second BLOCKS, then expects one row and the same id from both.

## B-42 — V140's rules had no legacy-data test

The tie itself was ruled on 2026-10-01 (leave V140's choices; the corrected rule for any re-run is
in V144's header). What stayed open was the IT the review asked for «either way».

`DraftLineRefilingOnLiveDataIntegrationTest` migrates a scratch database to V139, seeds every shape
V140 answers, migrates to **V140** (capped — a later migration may move the same rows) and asserts:
a shared position in a majority draft moves to the majority trade WITH that trade's category; a
name the majority trade does not ship stays; a NULL trade stays NULL; a 50/50 tie breaks to the
alphabetically first trade (pinned as shipped, not endorsed); below half nothing moves; SIGNED and
SENT are untouched. The two positions are read off `catalog_templates` at V139, not typed, so the
test cannot drift from the library it describes.

## `journey.spec` — the empty «Обʼєкти» was a real bug (PWA 1.49.5)

The open-questions item suspected it, and it reproduced on the first run: the server had the
object, `/projects` showed «Ще немає обʼєктів» for the whole 120 s.

**Cause.** The query cache is persisted to IndexedDB with `throttleTime: 1000`, and a restored
query keeps the `dataUpdatedAt` it was saved with. The spec created an object and hard-navigated
within that second, so the snapshot still held the list from BEFORE the create — and, being younger
than the 30 s `staleTime`, it restored as FRESH. No mount refetch; no focus refetch (off); nothing
afterwards either, because the screen stays mounted. A master killing the app right after a write
and reopening it within 30 s hits the same thing: his last change is missing from the screen until
something else invalidates it. Instrumented: snapshot `n=0`, no `GET /api/projects` after the
navigation, still empty after 40 s.

**Fix.** `PersistQueryClientProvider.onSuccess` → `markRestoredStale(queryClient)`
(`src/lib/offlinePersist.ts`): `invalidateQueries({ refetchType: 'none' })` once the snapshot is
restored and BEFORE `isRestoring` drops, so every mounted screen asks the server once. It fetches
nothing off screen, and the mount fetch is the one a cold start more than 30 s later already made —
offline it fails fast and the restored data stays (`offlineFirst`). No new outbox race: mount
fetches were never gated.

**The spec's two stale steps** — written before a signed-in master on `/login` was redirected home:
step 2 now asserts that a second cold navigation offline boots the shell from the cache (URL `/`,
the offline banner, the object); step 3 creates an estimate through «+ Новий» → «Порожній» and
waits for «Не синхронізовано» instead of clicking a button that no longer exists and accepting
«Офлайн» as proof of a queued write.

Verified both ways: green with the fix, red at the original spot with `onSuccess` removed. CI still
runs only `shell.spec` — `journey.spec` needs a backend on :8080. `offlinePersist.test.ts` pins
`markRestoredStale` (a snapshot fresh by the clock comes back invalidated, nothing is fetched).

## B-20 — the production database lower-cases Cyrillic (recorded 2026-10-13)

The Java half shipped in Fix N (`NameKeys.of` and both catalog key builders on `Locale.ROOT`). What
stayed open was confirming that the SQL half — `lower(btrim(name))` in V126 and every migration that
builds a name key — folds Cyrillic the same way on production. Under a `C`/`POSIX` ctype `lower()`
leaves Cyrillic untouched, and the two keys would quietly stop matching.

Checked on the Railway production database (`railway`, PostgreSQL via `pg_database`):

| provider | `lc_collate` | `lc_ctype` | `lower('ҐЄІЇ ЩЬЮЯ Шпаклювання')` |
|---|---|---|---|
| `c` (libc) | `en_US.utf8` | `en_US.utf8` | `ґєії щьюя шпаклювання` ✓ |

So the SQL keys and the Java keys agree; no change. If the database is ever recreated (a new
provider, a restore into another cluster), re-run the check — a `C` ctype would mean computing the
keys on the Java side instead of in SQL:

```sql
SELECT d.datlocprovider, d.datcollate, d.datctype,
       lower('ҐЄІЇ ЩЬЮЯ Шпаклювання') = 'ґєії щьюя шпаклювання' AS lower_ok
  FROM pg_database d WHERE d.datname = current_database();
```
