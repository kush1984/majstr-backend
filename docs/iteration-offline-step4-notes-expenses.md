# Iteration: offline step 4 — notes + economy expenses

The last two ordinary-CRUD holes. After this, everything a master does in a normal working
day authors offline except photos.

- **Status:** 🔨 backend unverified by me (cannot run Gradle); PWA verified green
- **Migration:** none
- **PWA:** 0.38.0

## Why these two

A note is the most "on site" thing in the app — «ключі в консьєржа», a phone number scribbled
at the door. An expense gets logged standing in the shop. Both are written exactly where the
signal dies, and both went straight to the network and simply failed there.

## Backend

Same shape as every other offline-authorable entity, no new ideas:

- `ProjectNoteService.add` and `ObjectExpenseService.add` gain a `requestedId` overload +
  `X-Entity-Uuid` on their controllers. An id already belonging to a **different object** is
  refused rather than re-homed.
- Both deletes become idempotent no-ops when the row is already gone — the same lost-response
  case that steps 1–3 were about. A 404 on replay is classified as a permanent rejection, so
  the master would be shown "не збережено в хмару" for something that saved.
- No migration: both entities' `@PrePersist` already honours a supplied id.

The expense idempotency check earns its keep more than the others: **money must not
double-count.** A duplicated expense silently understates the object's profit, and a wrong
number about the master's own cash is worse than a missing one.

## PWA

All six mutations go through `offlineMutate` with `deps: [objectId]`, so a note or expense
authored on a freshly-created offline object never lands before that object exists.

**The expense list is patched optimistically; the profit summary is not.** That figure mixes
estimate income, deposits and a completed-object settlement rule that lives on the server —
re-deriving it locally risks showing a number that disagrees with the real one. The journal is
the honest part; the summary refreshes on sync. There is a test asserting the summary stays
untouched, so a future "improvement" can't quietly add a fake profit.

Economy is PRO-gated and the prefetch already skips it on FREE, so a FREE master never has a
cached journal to edit — the gate stays aligned with no extra work.

Neither UI needed changing: like `AddItemSheet`, they never gated themselves offline — they
just failed.

## Testing

Backend: a replayed add returns the existing row without saving (notes + expenses); a client
id belonging to another object is refused; both deletes are no-ops when the row is gone.

PWA: notes add optimistically newest-first and queue with `deps`, edit and remove offline;
expenses add/remove optimistically, and the profit summary is explicitly asserted unchanged.

`NotesSection.test` needed updating — it pinned the exact `notesApi.add` arguments, which now
include the client id. Updated to assert the id is passed rather than loosened.

`npm run build`, 307 tests, `eslint`, `typecheck:tests` green.

## What's left
Photos (**O6**) — a blob outbox, deferred multipart upload, dedup and a storage-quota story.
Moved to the backlog at the user's call; `PhotosSection` stays `useOnlineGuard`-disabled
offline, which is honest rather than broken.
