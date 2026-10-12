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
