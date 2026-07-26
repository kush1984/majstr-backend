# Iteration: audit batch B — money & security

Second batch off the code-quality audits (after [batch A](iteration-audit-batch-a.md), which
built the CI/lint safety net). These are the findings with a real, present-tense cost: one
that gives away paid subscriptions, one that skips a money check, and two that expose
credentials or private files.

- **Status:** 🔨 code complete; backend build on the user
- **Migration:** **V66** — hash verification + reset tokens
- **PWA:** unchanged

## H1 — a retried webhook could grant PRO twice

Idempotency was `if (payment.getStatus() == SUCCESS) return;` at the top and
`payment.setStatus(SUCCESS)` further down — check-then-act with nothing in between. When
monobank retries a delivery while the first is still in flight, **both transactions read
PENDING**, both pass the guard, and both call `extendPro`: one month's payment buys two.
The class comment ("a repeated success never extends PRO twice") only ever held for
*sequential* retries. Referral rewards were safe (a DB `UNIQUE` backstops them); the plan
extension was not.

Fixed with an atomic claim — `PaymentRepository.claimForGrant`, a conditional
`UPDATE … WHERE id = ? AND status <> SUCCESS`. The row lock serialises the second
transaction, which then sees **0 rows affected** and grants nothing. The top-of-method
status read stays, but only as a cheap early-out; the claim is what decides.

Two JPA details that would have bitten:
- **No `clearAutomatically`** on the `@Modifying` query — clearing the persistence context
  would detach the `Payment` and `User` the service keeps using right afterwards.
- **Both written columns are mirrored** onto the in-memory entity. Without that, a later
  flush of the stale copy would write `paidAt` back to `null` and erase the claim's stamp.

## H2 — the amount check silently didn't run

```java
if (amount instanceof Number n && n.longValue() != expected) { … return; }
```
The guard only fired when `amount` **was** a `Number`. A payload that omitted it — or sent
it as a string — skipped the comparison entirely and went on to grant PRO. Now a missing or
non-numeric amount **is** a mismatch; a quoted numeric string is accepted and parsed.

⚠️ **Deliberate risk, worth watching:** a signed webhook with no `amount` will no longer
grant PRO. The feature's own doc says "amount must equal the invoice amount, else not
granted", and the test fixture mirrors monobank's real shape — but if monobank ever omits
the field legitimately, this surfaces as "paid, but no PRO". Worth a look at the first few
live payments.

## M1 — verification and reset tokens were stored raw (V66)

`refresh_tokens.token_hash` already hashed correctly; these two did not. They are bearer
credentials — holding the value **is** the user for that operation — so anyone with read
access to a DB dump or a backup had live, unexpired **password-reset links** and could take
over accounts without touching the running system.

- New `security/TokenHash` — SHA-256 → base64url, one implementation instead of a third copy
  of the same private method. No salt and no work factor **on purpose**: the input is 32–48
  bytes of `SecureRandom`, so there is nothing to brute-force, and the lookup must stay a
  single indexed equality match.
- Both services now email the **raw** token and persist only `TokenHash.of(raw)`; lookup
  re-hashes the incoming value. The raw value exists only in the sent email and in the
  request that spends it.
- **V66** renames `token` → `token_hash` on both tables (so the schema says what it holds,
  matching `refresh_tokens`) and **deletes existing rows**.

⚠️ **Migration consequence, accepted:** already-emailed links stop working. Existing rows
hold raw tokens, which can't be converted here without leaving the raw values in the
WAL/backups anyway — and they'd fail lookup regardless now that the code hashes first.
Reset tokens live 45 min and verification tokens 24 h, so the blast radius is whoever has an
unclicked link at deploy time; both flows have a "send again" path.

## M3 — private photos were served unauthenticated

`/api/files/**` is in `PUBLIC_PATHS` and `FileController` resolved **any** storage key, with
`Cache-Control: public`. So `photos/<uuid>.jpg` — a receipt photo, i.e. financial personal
data — was world-readable to anyone who learned the key, from a log line, a proxy log, a
backup or a bucket listing. `ProjectPhotoService`'s own javadoc promised the opposite.

Locked to the `logos/` prefix — the one class of object that is public by design (client
PDFs, the anonymous portal). Verified nothing else legitimately used the route: logos are
stored under `LOGO_PREFIX = "logos"`, both `/api/files/…` URL builders emit exactly those,
and photos use a different prefix behind their own authenticated / portal-token endpoints.

The 404 is decided **from the key alone**, never by asking storage — otherwise response
timing would hint at whether a private object exists.

## Tests
- `webhook_amountMissing_doesNotGrant`, `webhook_concurrentDelivery_losesTheClaimAndGrantsNothing`.
- `FileControllerTest` — serves a logo; refuses a photo key *and never touches storage*;
  refuses traversal.
- Reset/verification tests now assert the **relationship**: capture what was emailed and
  prove `TokenHash.of(emailed) == saved.tokenHash` and that the two differ — i.e. the raw
  value never reached the row.

## Gotchas
- `claimForGrant` is mocked in tests and Mockito returns **0** for an `int` by default, so
  every existing "grant" test would have silently started failing. Stubbed in the four that
  actually grant — and deliberately **not** in the two that return earlier, where strict
  stubbing would flag it as unnecessary.
- `AuthController.request.token()` is the raw token off the wire and is unchanged — only
  storage and lookup moved to hashes.
- Share-link tokens (`project_share_links`, `EstimateShareLink`) are still raw **by design**
  — the contractor re-copies the URL later. That trade-off is its own open question.
