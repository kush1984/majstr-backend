# Iteration — sharing ONE estimate mints its own link

PWA `1.19.2`. Backend: no migration (the path this reuses already existed).

Sharing from a single estimate's editor used to open the same object-level sheet as sharing from
the object root: a picker listing every estimate of the object, publishing whichever set was ticked
onto the OBJECT's one portal link. So "надішли клієнту цей кошторис" turned into a small decision
about the whole object, and the client opened a page offering a choice of documents.

This iteration splits the two intents by the link they mint, not by a display filter.

---

## 1. Two scopes, two links

- **From the object** (root / Економіка tab) — unchanged. The master ticks a set, and the set
  publishes onto the object's `?p=` (SIGNATURE) or `?e=` (ECONOMY) link, as sections of one page.
- **From one estimate's editor** — mints that ESTIMATE's own `?t=` link
  (`POST /api/estimates/{id}/share`). One link, one document: no picker, and the object's portal is
  **neither read nor written**.

The per-estimate `?t=` link is the pre-existing per-estimate share path, alive end to end and
untouched by the two-contexts iteration: `ShareLinkService` (create/sendByEmail/revoke),
`EstimateController`'s `/share` + `/share/send-email`, `PublicEstimateController`
(view/sign/question/pdf/shared photos, with `QuestionRateLimiter`), and the static portal page's
`adaptLegacy()` + `sectionApi()` branch. Nothing new was needed server-side — no
`ShareLinkKind.ESTIMATE`, no migration, no fourth public controller. It is no longer "legacy": it
is the single-estimate share path.

**The rejected alternative** (built first, then reverted): publish only that one estimate onto the
object's portal link. It gave the client the right page, but silently dropped every sibling
estimate off a link the master may already have sent. Repointing an already-shared link is a much
bigger side effect than the master asked for by tapping "поділитися" inside one estimate.

## 2. `ShareLinkService.create` is now idempotent

It used to mint a fresh token on **every** call. With the share sheet minting on open, that would
leave one live, untracked token per sheet-open: the master has no idea how many exist, so none of
them is revokable in practice, and a client who bookmarked yesterday's URL is silently on a
different link from the one just sent.

`create` now reuses the estimate's current link when there is a usable one:

```java
EstimateShareLink link = usableLink(estimateId).orElseGet(() -> repository.save(newLink(estimate)));
```

`usableLink` adds the check the repository query does not do — the query filters only `revoked`, so
an **expired** link would otherwise be handed out as a fresh share and 404 the moment the client
opened it. `sendByEmail` routes through the same helper (it had the same expired-link hole).

## 3. PWA: one sheet, two scopes, enforced by the prop type

`SharePortalSheet`'s props are a discriminated union, so the two scopes can never both be passed:

```ts
| { singleEstimateId: string; mode?: never }              // one estimate, its own ?t= link
| { singleEstimateId?: never; mode: 'portal' | 'economy' } // the object's link, a picked set
```

In single mode the sheet:

- does not run the portal-state query at all (`enabled: open && !singleEstimateId`) — there is no
  set to seed a picker from;
- mints on open via `estimateShareApi.create` (a POST that mints/reuses server state, deliberately
  kept out of react-query rather than dressed up as a cacheable read) and shows the URL in a
  readonly `Input`. On a phone the master usually pastes it into a messenger, and seeing the URL is
  what makes "this opens only that кошторис" believable;
- renders no picker, no payments toggle (a `?t=` link has no payments card to gate), and no
  "hide all" (there is no published set to withdraw);
- copies the minted URL without publishing anything, and emails via
  `estimateShareApi.sendEmail(estimateId)`.

A failed mint routes through the same `handleError` a failed publish does — `EMAIL_NOT_VERIFIED`
bounces to the parent's verify modal, `CLIENT_EMAIL_MISSING` reveals the inline add-email field —
so the plan/verify gates behave identically in both scopes. `handleError` is declared after the
client-email state it needs, so the mint effect reaches it through a ref instead of re-running
whenever its identity changes.

Copy: `portal.singleHint` — «Окреме посилання на цей кошторис — клієнт побачить лише його. Портал
об'єкта не змінюється.» The second sentence is the point: it answers the question the master would
otherwise have to guess at.

## 4. Tests

- `ShareLinkServiceTest` — `create` reuses an existing usable link (same URL, `repository.save`
  never called) and does **not** reuse an expired one.
- `SharePortalSheet.test.tsx` — single mode mints the estimate's own link and never touches
  `portalApi.state`/`economyPortalApi.state`; copying calls neither `update`; email goes through
  `estimateShareApi.sendEmail`; a 403 `EMAIL_NOT_VERIFIED` mint bounces to the verify modal; a
  SIGNED estimate shared from its editor gets the same link with no payments toggle.
