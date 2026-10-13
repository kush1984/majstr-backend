# Signed snapshots — a signature certifies what was printed, and it stays printed (B-76)

**Status:** built, `./gradlew build` green — see the commit. **Migration:** V150. **PWA:** 1.50.0
(version only; no PWA source changed — every surface keeps its JSON shape).

## Why

Review round 3 found the act's `doc_hash` unverifiable. It was SHA-256 of the canonical PDF BYTES,
and OpenPDF writes a time-based `/ID` and `CreationDate`, so two renders a second apart differed and
no one could ever recompute the stamp. Worse, every later render read LIVE data — the master's
requisites, IBAN and logo, the client's and the object's names — so correcting an IBAN changed every
act the master had ever had signed. A signed estimate had the same live render and no stamp at all.
Nothing re-verified a hash, which is the only reason it had cost nobody anything yet.

## Owner rulings (2026-10-12)

- **Option Б** — store a canonical JSON snapshot, hash THAT, render signed documents FROM it; plus
  keep the stamped PDF (option А) as the copy the client actually received.
- **Acts and estimates together.**
- **Documents signed before the deploy stay as they are** — no snapshot, live render, their stored
  hash untouched (it was never reproducible; inventing a snapshot from today's data would certify
  something nobody signed).

## What shipped

### Schema (V150)
- `signed_document`: `kind` ACT/ESTIMATE, `work_act_id` / `estimate_id` (exactly one, CHECK; both
  `ON DELETE CASCADE`), `schema_version`, `snapshot` TEXT (verbatim), `doc_hash`, `pdf_key`,
  `logo_key`, `signed_at`. Partial unique index on `work_act_id` — an act is signed once.
- `work_act.signed_document_id`, `estimates.signed_document_id` → the CURRENT signature
  (`ON DELETE SET NULL`).

### The freeze — `SignedDocumentService.freezeAct` / `freezeEstimate`
- Called from `ActSignedCopyService.freezeAndSend` (both act sign paths: portal and offline) and
  from `PublicEstimateService.doSign`, after the signer fields are set, inside the signing tx.
- `SignedSnapshot` records every printed value: contractor (requisites, `branded` decision of the
  day, logo SHA-256), project, client, header, signer (name, phone, time, offline — never IP or user
  agent), lines, act receipts, client-safe estimate names, «ДОВІДКОВО» as of the signature (B-94).
- The canonical text comes from the class's OWN `JsonMapper` — sorted properties and map keys,
  `WRITE_BIGDECIMAL_AS_PLAIN` (scale kept), ISO dates, no whitespace — so it does not move with the
  application's mapper config. `doc_hash` = SHA-256 of the UTF-8 text; the text is stored as is.
- The stamped PDF is rendered FROM the snapshot (rebuilt entities), so the emailed copy and every
  later render share one source; stored under `signed-docs/` (fail-soft — the snapshot is the record).
- The logo is copied to `logos/signed/` (replacing a logo deletes the old file; `logos/` is the
  prefix `/api/files/**` serves, so the portal can still show it). Fail-soft.
- Render failure fails the signature, as before — wrapped unchecked so the estimate's plain
  `@Transactional` rolls back too. Stored blobs register `StorageCleanup.onRollback`.

### Ever after — `SignedDocumentService.act(live)` / `estimate(live)`
Rebuild DETACHED entities (never persisted) and feed the SAME renderers/view builders:
- owner act PDF (`WorkActService.renderPdf`), portal act PDF and view (`PublicActPortalService`);
- every estimate PDF (`EstimateService.renderPdf(Estimate, images)` — owner receipt images still
  append), the single-estimate `?t=` view (header included), and the SIGNATURE/ECONOMY portal
  SECTIONS (`sectionOf`; the page header stays live — it is the object's page, not one document).
- Not printed and moving after a signature on purpose (`countInEconomy`, `portalVisible`,
  supersede, `@Version`, `addendumEstimateId`) are read off the live row.
- Both `PdfModel`s gained a nullable `branded` (null = ask `FeatureGuard`); the estimate one a
  `docHash` printed in a footer like the act's. Old constructors kept — no caller fan-out.

### Lifecycle
- `EstimateService.applyReopen` clears `signed_document_id`; the row stays as history. A re-sign
  writes a new row.
- `EstimateService.delete` and `ProjectService.delete` collect `pdf_key`/`logo_key` before the
  cascade (the fifth key source of a project delete).

## Tests
- `SignedSnapshotTest` — map-order independence, decimal scale and ISO dates, sorted properties,
  read-then-write round trip, a changed figure changes the hash.
- `SignedDocumentIntegrationTest` — sign an act offline / an estimate on the portal; rewrite the
  company, IBAN, client, object and estimate names; every surface still reads the signed values and
  the stamp equals `sha256(snapshot)`; the same act with its pointer nulled (the pre-V150 shape)
  renders the NEW values; the stored text is already canonical; reopen clears the pointer and keeps
  the row.
- Unit tests of the touched services gained the new mocks; `PublicActPortalServiceTest` stubs
  `freezeAndSend`.

## Not changed / confirmed
- No new route, so the IDOR matrix is unchanged; no request DTO changed, so no contract refresh.
- Legacy signed documents: untouched, live-rendered, stamp as stored.
- ADDENDUM estimates (signed by an act, never by a client) are not snapshotted — their content is
  frozen copies already and the act's snapshot covers what was signed.
- No verification ENDPOINT yet: the stamp is reproducible from the stored text, but nothing outside
  the database can read that text. Worth a public `GET …/snapshot` once someone asks to verify.
- No email of a signed estimate's copy (there never was one); the stamped PDF is stored either way.

## Gotchas
- **Never re-serialise `snapshot`** — it is the hashed text. Reading it back and writing it again is
  byte-identical today (`theStoredSnapshotIsAlreadyCanonical`), but a future field would break that;
  the stored text is the truth.
- **A structural change to `SignedSnapshot` bumps `SCHEMA`** and must still read old rows (or keep
  the old records): rows are rendered from it forever.
- The rebuilt entities are DETACHED transient objects — never pass them to a repository.
- `MapperFeature.SORT_CREATOR_PROPERTIES_FIRST` must stay disabled, or record components (creator
  properties) keep declaration order instead of being sorted.
