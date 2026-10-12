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
